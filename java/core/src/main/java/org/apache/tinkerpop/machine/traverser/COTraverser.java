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
package org.apache.tinkerpop.machine.traverser;

import org.apache.tinkerpop.machine.coefficient.Coefficient;
import org.apache.tinkerpop.machine.function.CFunction;
import org.apache.tinkerpop.util.StringFactory;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class COTraverser<C, S> implements Traverser<C, S> {

    protected Coefficient<C> coefficient;
    protected S object;

    COTraverser(final Coefficient<C> coefficient, final S object) {
        this.coefficient = coefficient.clone();
        this.object = object;
    }

    @Override
    public Coefficient<C> coefficient() {
        return this.coefficient;
    }

    @Override
    public S object() {
        return this.object;
    }

    @Override
    public Path path() {
        return EmptyPath.instance();
    }

    @Override
    public void incrLoops() {
    }

    @Override
    public int loops() {
        return 0;
    }

    @Override
    public void resetLoops() {

    }

    @Override
    public <E> Traverser<C, E> split(final CFunction<C> function, final E object) {
        final COTraverser<C, E> clone = (COTraverser<C, E>) this.clone();
        clone.object = object;
        clone.coefficient.multiply(function.coefficient());
        return clone;
    }

    @Override
    public int hashCode() {
        return this.object.hashCode();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof COTraverser && this.object.equals(((COTraverser) other).object);
    }

    @Override
    public String toString() {
        return StringFactory.makeTraverserString(this);
    }

    @Override
    public Traverser<C, S> clone() {
        try {
            final COTraverser<C, S> clone = (COTraverser<C, S>) super.clone();
            clone.coefficient = this.coefficient.clone();
            return clone;
        } catch (final CloneNotSupportedException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}