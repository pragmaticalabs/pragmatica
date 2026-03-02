/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.serialization;

import io.netty.buffer.ByteBuf;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Option.None;
import org.pragmatica.lang.Option.Some;
import org.pragmatica.lang.Result.Failure;
import org.pragmatica.lang.Result.Success;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec.TagMapper;
import org.pragmatica.serialization.SliceCodec.TypeCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.serialization.SliceCodec.*;

public sealed interface FrameworkCodecs {
    record unused() implements FrameworkCodecs {}

    TagMapper<Boolean> BOOLEAN_TAG_MAPPER = b -> b ? TAG_TRUE : TAG_FALSE;

    static SliceCodec frameworkCodecs() {
        return SliceCodec.sliceCodec(List.of(
            unitCodec(),
            noneCodec(),
            someCodec(),
            successCodec(),
            failureCodec(),
            booleanTrueCodec(),
            booleanFalseCodec(),
            byteCodec(),
            shortCodec(),
            charCodec(),
            intCodec(),
            longCodec(),
            floatCodec(),
            doubleCodec(),
            stringCodec(),
            listCodec(),
            setCodec(),
            mapCodec(),
            byteArrayCodec()
        ));
    }

    // --- Framework types ---

    private static TypeCodec<Unit> unitCodec() {
        return new TypeCodec<>(Unit.class, TAG_UNIT, (_, _, _) -> {}, (_, _) -> unit());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<None> noneCodec() {
        return new TypeCodec<>((Class) None.class, TAG_NONE, (_, _, _) -> {}, (_, _) -> Option.empty());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<Some> someCodec() {
        return new TypeCodec<>((Class) Some.class, TAG_SOME, FrameworkCodecs::writeSome, FrameworkCodecs::readSome);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<Success> successCodec() {
        return new TypeCodec<>((Class) Success.class, TAG_SUCCESS, FrameworkCodecs::writeSuccess, FrameworkCodecs::readSuccess);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<Failure> failureCodec() {
        return new TypeCodec<>((Class) Failure.class, TAG_FAILURE, FrameworkCodecs::writeFailure, FrameworkCodecs::readFailure);
    }

    private static TypeCodec<Boolean> booleanTrueCodec() {
        return new TypeCodec<>(Boolean.class, TAG_TRUE, BOOLEAN_TAG_MAPPER, (_, _, _) -> {}, (_, _) -> Boolean.TRUE);
    }

    private static TypeCodec<Boolean> booleanFalseCodec() {
        return new TypeCodec<>(Boolean.class, TAG_FALSE, BOOLEAN_TAG_MAPPER, (_, _, _) -> {}, (_, _) -> Boolean.FALSE);
    }

    // --- Numeric types ---

    private static TypeCodec<Byte> byteCodec() {
        return new TypeCodec<>(Byte.class, TAG_BYTE, (_, buf, val) -> buf.writeByte(val), (_, buf) -> buf.readByte());
    }

    private static TypeCodec<Short> shortCodec() {
        return new TypeCodec<>(Short.class, TAG_SHORT, (_, buf, val) -> buf.writeShort(val), (_, buf) -> buf.readShort());
    }

    private static TypeCodec<Character> charCodec() {
        return new TypeCodec<>(Character.class, TAG_CHAR, (_, buf, val) -> buf.writeChar(val), (_, buf) -> buf.readChar());
    }

    private static TypeCodec<Integer> intCodec() {
        return new TypeCodec<>(Integer.class, TAG_INT, (_, buf, val) -> buf.writeInt(val), (_, buf) -> buf.readInt());
    }

    private static TypeCodec<Long> longCodec() {
        return new TypeCodec<>(Long.class, TAG_LONG, (_, buf, val) -> buf.writeLong(val), (_, buf) -> buf.readLong());
    }

    private static TypeCodec<Float> floatCodec() {
        return new TypeCodec<>(Float.class, TAG_FLOAT, (_, buf, val) -> buf.writeFloat(val), (_, buf) -> buf.readFloat());
    }

    private static TypeCodec<Double> doubleCodec() {
        return new TypeCodec<>(Double.class, TAG_DOUBLE, (_, buf, val) -> buf.writeDouble(val), (_, buf) -> buf.readDouble());
    }

    // --- String ---

    private static TypeCodec<String> stringCodec() {
        return new TypeCodec<>(String.class, TAG_STRING, (_, buf, val) -> writeString(buf, val), (_, buf) -> readString(buf));
    }

    // --- Collections ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<List> listCodec() {
        return new TypeCodec<>((Class) List.class, TAG_ARRAY, FrameworkCodecs::writeList, FrameworkCodecs::readList);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<Set> setCodec() {
        return new TypeCodec<>((Class) Set.class, TAG_SET, FrameworkCodecs::writeSet, FrameworkCodecs::readSet);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<Map> mapCodec() {
        return new TypeCodec<>((Class) Map.class, TAG_MAP, FrameworkCodecs::writeMap, FrameworkCodecs::readMap);
    }

    private static TypeCodec<byte[]> byteArrayCodec() {
        return new TypeCodec<>(byte[].class, TAG_BYTE_ARRAY, FrameworkCodecs::writeByteArray, FrameworkCodecs::readByteArray);
    }

    // --- Body writers/readers for container types ---

    @SuppressWarnings("rawtypes")
    private static void writeSome(SliceCodec codec, ByteBuf buf, Some value) {
        codec.write(buf, value.value());
    }

    @SuppressWarnings("rawtypes")
    private static Some readSome(SliceCodec codec, ByteBuf buf) {
        return new Some(codec.read(buf));
    }

    @SuppressWarnings("rawtypes")
    private static void writeSuccess(SliceCodec codec, ByteBuf buf, Success value) {
        codec.write(buf, value.value());
    }

    @SuppressWarnings("rawtypes")
    private static Success readSuccess(SliceCodec codec, ByteBuf buf) {
        return new Success(codec.read(buf));
    }

    @SuppressWarnings("rawtypes")
    private static void writeFailure(SliceCodec codec, ByteBuf buf, Failure value) {
        codec.write(buf, value.cause());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Failure readFailure(SliceCodec codec, ByteBuf buf) {
        return new Failure(codec.read(buf));
    }

    // --- Collection writers/readers ---

    @SuppressWarnings("rawtypes")
    private static void writeList(SliceCodec codec, ByteBuf buf, List value) {
        writeCompact(buf, value.size());

        for (var elem : value) {
            codec.write(buf, elem);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List readList(SliceCodec codec, ByteBuf buf) {
        var len = readCompact(buf);
        var list = new ArrayList(len);

        for (int i = 0; i < len; i++) {
            list.add(codec.read(buf));
        }

        return List.copyOf(list);
    }

    @SuppressWarnings("rawtypes")
    private static void writeSet(SliceCodec codec, ByteBuf buf, Set value) {
        writeCompact(buf, value.size());

        for (var elem : value) {
            codec.write(buf, elem);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Set readSet(SliceCodec codec, ByteBuf buf) {
        var len = readCompact(buf);
        var set = new LinkedHashSet(len);

        for (int i = 0; i < len; i++) {
            set.add(codec.read(buf));
        }

        return Set.copyOf(set);
    }

    @SuppressWarnings("rawtypes")
    private static void writeMap(SliceCodec codec, ByteBuf buf, Map value) {
        writeCompact(buf, value.size());

        for (var entry : ((Map<?, ?>) value).entrySet()) {
            codec.write(buf, entry.getKey());
            codec.write(buf, entry.getValue());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map readMap(SliceCodec codec, ByteBuf buf) {
        var len = readCompact(buf);
        var map = new LinkedHashMap(len);

        for (int i = 0; i < len; i++) {
            var key = codec.read(buf);
            var val = codec.read(buf);
            map.put(key, val);
        }

        return Map.copyOf(map);
    }

    private static void writeByteArray(SliceCodec codec, ByteBuf buf, byte[] value) {
        writeCompact(buf, value.length);
        buf.writeBytes(value);
    }

    private static byte[] readByteArray(SliceCodec codec, ByteBuf buf) {
        var len = readCompact(buf);
        var bytes = new byte[len];
        buf.readBytes(bytes);
        return bytes;
    }
}
