/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package gremlingo

import (
	"bytes"
	"encoding/binary"
	"math/big"
	"reflect"
	"strings"

	"github.com/google/uuid"
)

const graphBinaryMimeType = "application/vnd.graphbinary-v1.0"

// serializer interface for serializers.
type serializer interface {
	serializeMessage(request *request) ([]byte, error)
	deserializeMessage(message []byte) (response, error)
}

// graphBinarySerializer serializes/deserializes message to/from GraphBinary.
type graphBinarySerializer struct {
	ser *graphBinaryTypeSerializer
}

func newGraphBinarySerializer(handler *logHandler) serializer {
	serializer := graphBinaryTypeSerializer{nullType, nil, nil, nil, handler}
	return graphBinarySerializer{&serializer}
}

const versionByte byte = 0x81

func convertArgs(request *request, gs graphBinarySerializer) (map[string]interface{}, error) {
	if request.op != bytecodeProcessor {
		return request.args, nil
	}

	// Convert to format:
	// args["gremlin"]: <serialized args["gremlin"]>
	gremlin := request.args["gremlin"]
	switch gremlin.(type) {
	case bytecode:
		buffer := bytes.Buffer{}
		gremlinBuffer, err := gs.ser.write(gremlin, &buffer)
		if err != nil {
			return nil, err
		}
		request.args["gremlin"] = gremlinBuffer
		return request.args, nil
	default:
		var typeName string
		if gremlin != nil {
			typeName = reflect.TypeOf(gremlin).Name()
		}

		return nil, newError(err0704ConvertArgsNoSerializerError, typeName)
	}
}

// serializeMessage serializes a request message into GraphBinary.
func (gs graphBinarySerializer) serializeMessage(request *request) ([]byte, error) {
	args, err := convertArgs(request, gs)
	if err != nil {
		return nil, err
	}
	finalMessage, err := gs.buildMessage(request.requestID, byte(len(graphBinaryMimeType)), request.op, request.processor, args)
	if err != nil {
		return nil, err
	}
	return finalMessage, nil
}

func (gs *graphBinarySerializer) buildMessage(id uuid.UUID, mimeLen byte, op string, processor string, args map[string]interface{}) ([]byte, error) {
	buffer := bytes.Buffer{}

	// mime header
	buffer.WriteByte(mimeLen)
	buffer.WriteString(graphBinaryMimeType)

	// Version
	buffer.WriteByte(versionByte)

	// Request uuid
	bigIntUUID := uuidToBigInt(id)
	lower := bigIntUUID.Uint64()
	upperBigInt := bigIntUUID.Rsh(&bigIntUUID, 64)
	upper := upperBigInt.Uint64()
	err := binary.Write(&buffer, binary.BigEndian, upper)
	if err != nil {
		return nil, err
	}
	err = binary.Write(&buffer, binary.BigEndian, lower)
	if err != nil {
		return nil, err
	}

	// op
	err = binary.Write(&buffer, binary.BigEndian, uint32(len(op)))
	if err != nil {
		return nil, err
	}

	_, err = buffer.WriteString(op)
	if err != nil {
		return nil, err
	}

	// processor
	err = binary.Write(&buffer, binary.BigEndian, uint32(len(processor)))
	if err != nil {
		return nil, err
	}

	_, err = buffer.WriteString(processor)
	if err != nil {
		return nil, err
	}

	// args
	err = binary.Write(&buffer, binary.BigEndian, uint32(len(args)))
	if err != nil {
		return nil, err
	}
	for k, v := range args {
		_, err = gs.ser.write(k, &buffer)
		if err != nil {
			return nil, err
		}

		switch t := v.(type) {
		case []byte:
			_, err = buffer.Write(t)
		default:
			_, err = gs.ser.write(t, &buffer)
		}
		if err != nil {
			return nil, err
		}
	}
	return buffer.Bytes(), nil
}

func uuidToBigInt(requestID uuid.UUID) big.Int {
	var bigInt big.Int
	bigInt.SetString(strings.Replace(requestID.String(), "-", "", 4), 16)
	return bigInt
}

func readUUID(buffer *bytes.Buffer) (uuid.UUID, error) {
	var nullable byte
	err := binary.Read(buffer, binary.LittleEndian, &nullable)
	if err != nil {
		return uuid.UUID{}, err
	}
	uuidBytes := make([]byte, 16)
	err = binary.Read(buffer, binary.LittleEndian, uuidBytes)
	if err != nil {
		return uuid.UUID{}, err
	}
	return uuid.FromBytes(uuidBytes)
}

func readMap(buffer *bytes.Buffer, gs *graphBinarySerializer) (map[string]interface{}, error) {
	var mapSize uint32
	err := binary.Read(buffer, binary.BigEndian, &mapSize)
	if err != nil {
		return nil, err
	}
	var mapData = map[string]interface{}{}
	for i := uint32(0); i < mapSize; i++ {
		var keyType dataType
		err = binary.Read(buffer, binary.BigEndian, &keyType)
		if err != nil {
			return nil, err
		} else if keyType != stringType {
			return nil, newError(err0703ReadMapNonStringKeyError, keyType)
		}
		var nullable byte
		err = binary.Read(buffer, binary.BigEndian, &nullable)
		if err != nil {
			return nil, err
		}
		if nullable != 0 {
			return nil, newError(err0701ReadMapNullKeyError)
		}

		k, err := readString(buffer)
		if err != nil {
			return nil, err
		}
		mapData[k], err = gs.ser.read(buffer)
		if err != nil {
			return nil, err
		}
	}
	return mapData, nil
}

func readString(buffer *bytes.Buffer) (string, error) {
	var strLength uint32
	err := binary.Read(buffer, binary.BigEndian, &strLength)
	if err != nil {
		return "", err
	}

	strBytes := make([]byte, strLength)
	err = binary.Read(buffer, binary.BigEndian, strBytes)
	if err != nil {
		return "", err
	}
	return string(strBytes[:]), nil
}

// deserializeMessage deserializes a response message.
func (gs graphBinarySerializer) deserializeMessage(responseMessage []byte) (response, error) {
	var msg response
	buffer := bytes.Buffer{}
	buffer.Write(responseMessage)

	// Version
	_, err := buffer.ReadByte()
	if err != nil {
		return msg, err
	}

	// Response uuid
	msgUUID, err := readUUID(&buffer)
	if err != nil {
		return msg, err
	}

	// Status Code
	var statusCode uint32
	err = binary.Read(&buffer, binary.BigEndian, &statusCode)
	if err != nil {
		return msg, err
	}
	statusCode = statusCode & 0xFF

	// Nullable Status message
	var statusMessageNull byte
	var statusMessage string
	err = binary.Read(&buffer, binary.LittleEndian, &statusMessageNull)
	if err != nil {
		return msg, err
	}
	if statusMessageNull == 0 {
		statusMessage, err = readString(&buffer)
		if err != nil {
			return msg, err
		}
	}

	// Status Attributes
	statusAttributes, err := readMap(&buffer, &gs)
	if err != nil {
		return msg, err
	}

	// Meta Attributes
	metaAttributes, err := readMap(&buffer, &gs)
	if err != nil {
		return msg, err
	}

	// Result data
	data, err := gs.ser.read(&buffer)
	if err != nil {
		return msg, err
	}

	msg.responseID = msgUUID
	msg.responseStatus.code = uint16(statusCode)
	msg.responseStatus.message = statusMessage
	msg.responseStatus.attributes = statusAttributes
	msg.responseResult.meta = metaAttributes
	msg.responseResult.data = data

	return msg, nil
}
