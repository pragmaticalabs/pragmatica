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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface SliceCodec extends Serializer, Deserializer {

    // --- Wire format tag constants ---

    int TAG_UNIT = 0;
    int TAG_NONE = 1;
    int TAG_SOME = 2;
    int TAG_SUCCESS = 3;
    int TAG_FAILURE = 4;
    int TAG_TRUE = 5;
    int TAG_FALSE = 6;
    int TAG_NULL = 7;
    int TAG_BYTE_ARRAY = 8;
    int TAG_SET = 9;
    int TAG_BYTE = 10;
    int TAG_SHORT = 11;
    int TAG_CHAR = 12;
    int TAG_INT = 13;
    int TAG_LONG = 14;
    int TAG_FLOAT = 15;
    int TAG_DOUBLE = 16;
    int TAG_STRING = 17;
    int TAG_ARRAY = 18;
    int TAG_TUPLE = 19;
    int TAG_MAP = 20;

    // --- Stream header ---

    int MAGIC = 0xAE01;
    int FORMAT_VERSION = 1;

    static void writeHeader(ByteBuf buf) {
        buf.writeShort(MAGIC);
        buf.writeByte(FORMAT_VERSION);
    }

    static void verifyHeader(ByteBuf buf) {
        int magic = buf.readShort() & 0xFFFF;

        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic: " + Integer.toHexString(magic));
        }

        int version = buf.readByte() & 0xFF;

        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }

    // --- Nested types ---

    @FunctionalInterface
    interface TypeWriter<T> {
        void writeBody(SliceCodec codec, ByteBuf buf, T value);
    }

    @FunctionalInterface
    interface TypeReader<T> {
        T readBody(SliceCodec codec, ByteBuf buf);
    }

    /// Maps a value to its wire tag. For most types, returns the constant tag.
    /// For variant types (e.g. Boolean → TAG_TRUE/TAG_FALSE), returns a tag based on the value.
    @FunctionalInterface
    interface TagMapper<T> {
        int tagFor(T value);
    }

    record TypeCodec<T>(Class<T> type, int tag, TagMapper<T> tagMapper,
                        TypeWriter<T> writer, TypeReader<T> reader) {

        /// Convenience constructor for types with a constant tag.
        public TypeCodec(Class<T> type, int tag, TypeWriter<T> writer, TypeReader<T> reader) {
            this(type, tag, _ -> tag, writer, reader);
        }
    }

    // --- VLQ encoding ---

    static void writeCompact(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }

        buf.writeByte(value);
    }

    static int readCompact(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        int b;

        do {
            b = buf.readByte() & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return value;
    }

    // --- Tag computation ---

    static int deterministicTag(String className) {
        return (className.hashCode() & 0x7FFFFFFF) % 16256 + 128;
    }

    // --- String helpers ---

    static void writeString(ByteBuf buf, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeCompact(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    static String readString(ByteBuf buf) {
        var len = readCompact(buf);
        var bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // --- Body helpers for generated code ---

    @SuppressWarnings("unchecked")
    default <T> void writeBodyFor(ByteBuf buf, T value, Class<T> type) {
        var codec = (TypeCodec<T>) lookupByClass(type);
        codec.writer().writeBody(this, buf, value);
    }

    @SuppressWarnings("unchecked")
    default <T> T readBodyFor(ByteBuf buf, int tag) {
        var codec = (TypeCodec<T>) lookupByTag(tag);
        return codec.reader().readBody(this, buf);
    }

    // --- Internal lookup (package-private contract) ---

    TypeCodec<?> lookupByClass(Class<?> type);

    TypeCodec<?> lookupByTag(int tag);

    // --- Factory methods ---

    static SliceCodec sliceCodec(List<TypeCodec<?>> codecs) {
        return sliceCodec(null, codecs);
    }

    static SliceCodec sliceCodec(SliceCodec parent, List<TypeCodec<?>> codecs) {
        var byClass = new HashMap<Class<?>, TypeCodec<?>>();
        var byTag = new HashMap<Integer, TypeCodec<?>>();

        if (parent instanceof CodecHolder holder) {
            byClass.putAll(holder.byClass());
            byTag.putAll(holder.byTag());
        }

        for (var codec : codecs) {
            byClass.put(codec.type(), codec);
            byTag.put(codec.tag(), codec);
        }

        return new CodecHolder(Map.copyOf(byClass), Map.copyOf(byTag));
    }

}

record CodecHolder(
    Map<Class<?>, SliceCodec.TypeCodec<?>> byClass,
    Map<Integer, SliceCodec.TypeCodec<?>> byTag,
    ConcurrentHashMap<Class<?>, SliceCodec.TypeCodec<?>> classCache
) implements SliceCodec {

    CodecHolder(Map<Class<?>, SliceCodec.TypeCodec<?>> byClass,
                Map<Integer, SliceCodec.TypeCodec<?>> byTag) {
        this(byClass, byTag, new ConcurrentHashMap<>(byClass));
    }

    @Override
    public SliceCodec.TypeCodec<?> lookupByClass(Class<?> type) {
        var codec = classCache.get(type);

        if (codec != null) {
            return codec;
        }

        // Supertype fallback — handles collection implementations (e.g. ImmutableCollections$ListN → List)
        for (var entry : byClass.entrySet()) {
            if (entry.getKey().isAssignableFrom(type)) {
                classCache.put(type, entry.getValue());
                return entry.getValue();
            }
        }

        throw new IllegalArgumentException("No codec registered for class: " + type.getName());
    }

    @Override
    public SliceCodec.TypeCodec<?> lookupByTag(int tag) {
        var codec = byTag.get(tag);

        if (codec == null) {
            throw new IllegalArgumentException("No codec registered for tag: " + tag);
        }

        return codec;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> void write(ByteBuf byteBuf, T object) {
        if (object == null) {
            SliceCodec.writeCompact(byteBuf, SliceCodec.TAG_NULL);
            return;
        }

        var type = (Class<T>) object.getClass();
        var codec = (TypeCodec<T>) lookupByClass(type);
        SliceCodec.writeCompact(byteBuf, codec.tagMapper().tagFor(object));
        codec.writer().writeBody(this, byteBuf, object);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T read(ByteBuf byteBuf) {
        var tag = SliceCodec.readCompact(byteBuf);
        var codec = (TypeCodec<T>) lookupByTag(tag);
        return codec.reader().readBody(this, byteBuf);
    }
}
