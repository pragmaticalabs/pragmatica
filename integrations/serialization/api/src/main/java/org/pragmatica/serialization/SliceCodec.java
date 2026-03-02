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
import io.netty.buffer.ByteBufUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    // --- Tag space ---

    int TAG_SPACE_SIZE = 16384;

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
    /// For variant types (e.g. Boolean -> TAG_TRUE/TAG_FALSE), returns a tag based on the value.
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
        int utf8Bytes = ByteBufUtil.utf8Bytes(value);
        writeCompact(buf, utf8Bytes);
        ByteBufUtil.writeUtf8(buf, value);
    }

    static String readString(ByteBuf buf) {
        var len = readCompact(buf);
        var value = buf.toString(buf.readerIndex(), len, StandardCharsets.UTF_8);
        buf.skipBytes(len);
        return value;
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
        var tagArray = new TypeCodec<?>[TAG_SPACE_SIZE];

        if (parent instanceof CodecHolder holder) {
            byClass.putAll(holder.byClass());
            copyTagArray(holder.tagArray(), tagArray);
        }

        for (var codec : codecs) {
            byClass.put(codec.type(), codec);
            validateAndSetTag(tagArray, codec);
        }

        return new CodecHolder(Map.copyOf(byClass), tagArray);
    }

    private static void copyTagArray(TypeCodec<?>[] source, TypeCodec<?>[] target) {
        System.arraycopy(source, 0, target, 0, source.length);
    }

    private static void validateAndSetTag(TypeCodec<?>[] tagArray, TypeCodec<?> codec) {
        int tag = codec.tag();

        if (tag < 0 || tag >= TAG_SPACE_SIZE) {
            throw new IllegalArgumentException("Tag %d for %s is out of range [0, %d)"
                .formatted(tag, codec.type().getName(), TAG_SPACE_SIZE));
        }

        var existing = tagArray[tag];

        if (existing != null && !existing.type().equals(codec.type())) {
            throw new IllegalArgumentException("Tag collision: tag %d claimed by both %s and %s"
                .formatted(tag, existing.type().getName(), codec.type().getName()));
        }

        tagArray[tag] = codec;
    }

}

final class CodecHolder implements SliceCodec {

    private final Map<Class<?>, SliceCodec.TypeCodec<?>> byClass;
    private final SliceCodec.TypeCodec<?>[] tagArray;
    private final ConcurrentHashMap<Class<?>, SliceCodec.TypeCodec<?>> classCache;
    private volatile SliceCodec.TypeCodec<?> lastLookup;

    CodecHolder(Map<Class<?>, SliceCodec.TypeCodec<?>> byClass,
                SliceCodec.TypeCodec<?>[] tagArray) {
        this.byClass = byClass;
        this.tagArray = tagArray;
        this.classCache = new ConcurrentHashMap<>(byClass);
    }

    Map<Class<?>, SliceCodec.TypeCodec<?>> byClass() {
        return byClass;
    }

    SliceCodec.TypeCodec<?>[] tagArray() {
        return tagArray;
    }

    @Override
    public SliceCodec.TypeCodec<?> lookupByClass(Class<?> type) {
        var cached = lastLookup;

        if (cached != null && cached.type() == type) {
            return cached;
        }

        var codec = classCache.get(type);

        if (codec != null) {
            lastLookup = codec;
            return codec;
        }

        // Supertype fallback -- handles collection implementations (e.g. ImmutableCollections$ListN -> List)
        codec = findBySupertype(type);
        lastLookup = codec;
        return codec;
    }

    private SliceCodec.TypeCodec<?> findBySupertype(Class<?> type) {
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
        if (tag < 0 || tag >= tagArray.length) {
            throw new IllegalArgumentException("No codec registered for tag: " + tag);
        }

        var codec = tagArray[tag];

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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof CodecHolder other)) {
            return false;
        }

        return byClass.equals(other.byClass) && Arrays.equals(tagArray, other.tagArray);
    }

    @Override
    public int hashCode() {
        return 31 * byClass.hashCode() + Arrays.hashCode(tagArray);
    }

    @Override
    public String toString() {
        return "CodecHolder[byClass=%s, tagCount=%d]".formatted(byClass.keySet(), classCache.size());
    }
}
