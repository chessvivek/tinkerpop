/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.util;

import org.apache.tinkerpop.gremlin.process.computer.traversal.step.map.VertexProgramStep;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSideEffects;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.TraverserGenerator;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.DefaultTraverserGeneratorFactory;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.util.EmptyTraverser;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class DefaultTraversal<S, E> implements Traversal.Admin<S, E> {

    private Traverser.Admin<E> lastTraverser = EmptyTraverser.instance();
    private Step<?, E> finalEndStep = EmptyStep.instance();
    private final StepPosition stepPosition = new StepPosition();
    protected transient Graph graph;
    protected transient TraversalSource g;
    protected List<Step> steps = new ArrayList<>();
    // steps will be repeatedly retrieved from this traversal so wrap them once in an immutable list that can be reused
    protected List<Step> unmodifiableSteps = Collections.unmodifiableList(steps);
    protected TraversalParent parent = EmptyStep.instance();
    protected TraversalSideEffects sideEffects = new DefaultTraversalSideEffects();
    protected TraversalStrategies strategies;
    protected transient TraverserGenerator generator;
    protected Set<TraverserRequirement> requirements;

    protected boolean locked = false;
    protected boolean closed = false;
    protected Bytecode bytecode;

    private DefaultTraversal(final Graph graph, final TraversalStrategies traversalStrategies, final Bytecode bytecode) {
        this.graph = graph;
        this.strategies = traversalStrategies;
        this.bytecode = bytecode;
        this.g = null;
    }

    public DefaultTraversal(final Graph graph) {
        this(graph, TraversalStrategies.GlobalCache.getStrategies(graph.getClass()), new Bytecode());
    }

    public DefaultTraversal(final TraversalSource traversalSource) {
        this(traversalSource.getGraph(), traversalSource.getStrategies(), traversalSource.getBytecode());
        this.g = traversalSource;
    }

    public DefaultTraversal(final TraversalSource traversalSource, final DefaultTraversal.Admin<S,E> traversal) {
        this(traversalSource.getGraph(), traversalSource.getStrategies(), traversal.getBytecode());
        this.g = traversalSource;
        steps.addAll(traversal.getSteps());
    }

    public DefaultTraversal() {
        this(EmptyGraph.instance(), TraversalStrategies.GlobalCache.getStrategies(EmptyGraph.class), new Bytecode());
    }

    public DefaultTraversal(final Bytecode bytecode) {
        this(EmptyGraph.instance(), TraversalStrategies.GlobalCache.getStrategies(EmptyGraph.class), bytecode);
    }

    public Bytecode getBytecode() {
        return this.bytecode;
    }

    @Override
    public Traversal.Admin<S, E> asAdmin() {
        return this;
    }

    @Override
    public TraverserGenerator getTraverserGenerator() {
        if (null == this.generator)
            this.generator = isRoot() ?
                    DefaultTraverserGeneratorFactory.instance().getTraverserGenerator(this.getTraverserRequirements()) :
                    TraversalHelper.getRootTraversal(this).getTraverserGenerator();
        return this.generator;
    }

    @Override
    public void applyStrategies() throws IllegalStateException {
        if (this.locked) throw Traversal.Exceptions.traversalIsLocked();
        TraversalHelper.reIdSteps(this.stepPosition, this);
        final boolean hasGraph = null != this.graph;

        // we only want to apply strategies on the top-level step or if we got some graphcomputer stuff going on.
        // seems like in that case, the "top-level" of the traversal is really held by the VertexProgramStep which
        // needs to have strategies applied on "pure" copies of the traversal it is holding (i think). it further
        // seems that we need three recursions over the traversal hierarchy to ensure everything "works", where
        // strategy application requires top-level strategies and side-effects pushed into each child and then after
        // application of the strategies we need to call applyStrategies() on all the children to ensure that their
        // steps get reId'd and traverser requirements are set.
        if (isRoot() || this.getParent() instanceof VertexProgramStep) {

            // prepare the traversal and all its children for strategy application
            TraversalHelper.applyTraversalRecursively(t -> {
                if (hasGraph) t.setGraph(this.graph);
                t.setStrategies(this.strategies);
                t.setSideEffects(this.sideEffects);
            }, this);

            // note that prior to applying strategies to children we used to set side-effects and strategies of all
            // children to that of the parent. under this revised model of strategy application from TINKERPOP-1568
            // it doesn't appear to be necessary to do that (at least from the perspective of the test suite). by,
            // moving side-effect setting after actual recursive strategy application we save a loop and by
            // consequence also fix a problem where strategies might reset something in sideeffects which seems to
            // happen in TranslationStrategy.
            final Iterator<TraversalStrategy<?>> strategyIterator = this.strategies.iterator();
            while (strategyIterator.hasNext()) {
                final TraversalStrategy<?> strategy = strategyIterator.next();
                TraversalHelper.applyTraversalRecursively(strategy::apply, this);
            }

            // don't need to re-apply strategies to "this" - leads to endless recursion in GraphComputer.
            TraversalHelper.applyTraversalRecursively(t -> {
                if (hasGraph) t.setGraph(this.graph);
                if(!(t.isRoot()) && t != this && !t.isLocked()) {
                    t.setSideEffects(this.sideEffects);
                    t.applyStrategies();
                }
            }, this);
        }
        
        this.finalEndStep = this.getEndStep();

        // finalize requirements
        if (this.isRoot()) {
            resetTraverserRequirements();
        }
        this.locked = true;
    }

    private void resetTraverserRequirements() {
        this.requirements = null;
        this.getTraverserRequirements();
    }

    @Override
    public Set<TraverserRequirement> getTraverserRequirements() {
        if (null == this.requirements) {
            this.requirements = EnumSet.noneOf(TraverserRequirement.class);
            for (final Step<?, ?> step : this.getSteps()) {
                this.requirements.addAll(step.getRequirements());
            }
            if (!this.requirements.contains(TraverserRequirement.LABELED_PATH) && TraversalHelper.hasLabels(this))
                this.requirements.add(TraverserRequirement.LABELED_PATH);
            if (!this.getSideEffects().keys().isEmpty())
                this.requirements.add(TraverserRequirement.SIDE_EFFECTS);
            if (null != this.getSideEffects().getSackInitialValue())
                this.requirements.add(TraverserRequirement.SACK);
            if (this.requirements.contains(TraverserRequirement.ONE_BULK))
                this.requirements.remove(TraverserRequirement.BULK);
            this.requirements = Collections.unmodifiableSet(this.requirements);
        }
        return this.requirements;
    }

    @Override
    public List<Step> getSteps() {
        return this.unmodifiableSteps;
    }

    @Override
    public Traverser.Admin<E> nextTraverser() {
        try {
            if (!this.locked) this.applyStrategies();
            if (this.lastTraverser.bulk() > 0L) {
                final Traverser.Admin<E> temp = this.lastTraverser;
                this.lastTraverser = EmptyTraverser.instance();
                return temp;
            } else {
                return this.finalEndStep.next();
            }
        } catch (final FastNoSuchElementException e) {
            throw this.isRoot() ? new NoSuchElementException() : e;
        }
    }

    @Override
    public boolean hasNext() {
        // if the traversal is closed then resources are released and there is nothing else to iterate
        if (this.isClosed()) return false;

        if (!this.locked) this.applyStrategies();
        final boolean more = this.lastTraverser.bulk() > 0L || this.finalEndStep.hasNext();
        if (!more) CloseableIterator.closeIterator(this);
        return more;
    }

    @Override
    public E next() {
        // if the traversal is closed then resources are released and there is nothing else to iterate
        if (this.isClosed())
            throw this.parent instanceof EmptyStep ? new NoSuchElementException() : FastNoSuchElementException.instance();

        try {
            if (!this.locked) this.applyStrategies();
            if (this.lastTraverser.bulk() == 0L)
                this.lastTraverser = this.finalEndStep.next();
            this.lastTraverser.setBulk(this.lastTraverser.bulk() - 1L);
            return this.lastTraverser.get();
        } catch (final FastNoSuchElementException e) {
            // No more elements will be produced by this traversal. Close this traversal
            // and release the resources.
            CloseableIterator.closeIterator(this);

            throw this.isRoot() ? new NoSuchElementException() : e;
        }
    }

    @Override
    public void reset() {
        this.steps.forEach(Step::reset);
        this.finalEndStep.reset();
        this.lastTraverser = EmptyTraverser.instance();
        this.closed = false;
    }

    @Override
    public void addStart(final Traverser.Admin<S> start) {
        if (!this.locked) this.applyStrategies();
        if (!this.steps.isEmpty()) {
            this.steps.get(0).addStart(start);

            // match() expects that a traversal that has been iterated can continue to iterate if new starts are
            // added therefore the closed state must be reset.
            this.closed = false;
        }
    }

    @Override
    public void addStarts(final Iterator<Traverser.Admin<S>> starts) {
        if (!this.locked) this.applyStrategies();

        if (!this.steps.isEmpty()) {
            this.steps.get(0).addStarts(starts);

            // match() expects that a traversal that has been iterated can continue to iterate if new starts are
            // added therefore the closed state must be reset.
            this.closed = false;
        }
    }

    @Override
    public String toString() {
        return StringFactory.traversalString(this);
    }

    @Override
    public Step<S, ?> getStartStep() {
        return this.steps.isEmpty() ? EmptyStep.instance() : this.steps.get(0);
    }

    @Override
    public Step<?, E> getEndStep() {
        return this.steps.isEmpty() ? EmptyStep.instance() : this.steps.get(this.steps.size() - 1);
    }

    @Override
    public DefaultTraversal<S, E> clone() {
        try {
            final DefaultTraversal<S, E> clone = (DefaultTraversal<S, E>) super.clone();
            clone.lastTraverser = EmptyTraverser.instance();
            clone.steps = new ArrayList<>();
            clone.unmodifiableSteps = Collections.unmodifiableList(clone.steps);
            clone.sideEffects = this.sideEffects.clone();
            clone.strategies = this.strategies;
            clone.bytecode = this.bytecode.clone();
            for (final Step<?, ?> step : this.steps) {
                final Step<?, ?> clonedStep = step.clone();
                clonedStep.setTraversal(clone);
                final Step previousStep = clone.steps.isEmpty() ? EmptyStep.instance() : clone.steps.get(clone.steps.size() - 1);
                clonedStep.setPreviousStep(previousStep);
                previousStep.setNextStep(clonedStep);
                clone.steps.add(clonedStep);
            }
            clone.finalEndStep = clone.getEndStep();
            clone.closed = false;
            return clone;
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isLocked() {
        return this.locked;
    }

    /**
     * Determines if the traversal has been fully iterated and resources released.
     */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void notifyClose() {
        this.closed = true;
    }

    @Override
    public void setSideEffects(final TraversalSideEffects sideEffects) {
        this.sideEffects = sideEffects;
    }

    @Override
    public TraversalSideEffects getSideEffects() {
        return this.sideEffects;
    }

    @Override
    public void setStrategies(final TraversalStrategies strategies) {
        this.strategies = strategies;
    }

    @Override
    public TraversalStrategies getStrategies() {
        return this.strategies;
    }

    @Override
    public <S2, E2> Traversal.Admin<S2, E2> addStep(final int index, final Step<?, ?> step) throws IllegalStateException {
        if (this.locked) throw Exceptions.traversalIsLocked();
        step.setId(this.stepPosition.nextXId());
        this.steps.add(index, step);
        final Step previousStep = this.steps.size() > 0 && index != 0 ? steps.get(index - 1) : null;
        final Step nextStep = this.steps.size() > index + 1 ? steps.get(index + 1) : null;
        step.setPreviousStep(null != previousStep ? previousStep : EmptyStep.instance());
        step.setNextStep(null != nextStep ? nextStep : EmptyStep.instance());
        if (null != previousStep) previousStep.setNextStep(step);
        if (null != nextStep) nextStep.setPreviousStep(step);
        step.setTraversal(this);
        return (Traversal.Admin<S2, E2>) this;
    }

    @Override
    public <S2, E2> Traversal.Admin<S2, E2> removeStep(final int index) throws IllegalStateException {
        if (this.locked) throw Exceptions.traversalIsLocked();
        final Step previousStep = this.steps.size() > 0 && index != 0 ? steps.get(index - 1) : null;
        final Step nextStep = this.steps.size() > index + 1 ? steps.get(index + 1) : null;
        //this.steps.get(index).setTraversal(EmptyTraversal.instance());
        this.steps.remove(index);
        if (null != previousStep) previousStep.setNextStep(null == nextStep ? EmptyStep.instance() : nextStep);
        if (null != nextStep) nextStep.setPreviousStep(null == previousStep ? EmptyStep.instance() : previousStep);
        return (Traversal.Admin<S2, E2>) this;
    }

    @Override
    public void setParent(final TraversalParent step) {
        this.parent = null == step ? EmptyStep.instance() : step;
    }

    @Override
    public TraversalParent getParent() {
        return this.parent;
    }

    @Override
    public Optional<Graph> getGraph() {
        return Optional.ofNullable(this.graph);
    }

    @Override
    public Optional<TraversalSource> getTraversalSource() {
        return Optional.ofNullable(this.g);
    }

    @Override
    public void setGraph(final Graph graph) {
        this.graph = graph;
    }
    
    @Override
    public boolean equals(final Object other) {
        return other != null && other.getClass().equals(this.getClass()) && this.equals(((Traversal.Admin) other));
    }

    @Override
    public int hashCode() {
        int index = 0;
        int result = this.getClass().hashCode();
        for (final Step step : this.asAdmin().getSteps()) {
            result ^= Integer.rotateLeft(step.hashCode(), index++);
        }
        return result;
    }
}
