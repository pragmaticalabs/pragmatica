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
package org.pragmatica.serialization.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.spi.JsonReader;
import org.pragmatica.serialization.json.spi.JsonWriter;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A generated codec: one write function and one read function for a type. Not to be confused with
/// `org.pragmatica.http.JsonCodec`, the HTTP seam a slice's generated codecs are adapted to.
///
/// Every decode reads one complete UTF-8 document and returns its value or the [JsonError] that refused it;
/// every encode returns the UTF-8 bytes. The reuse overloads take a [JsonReader] or [JsonWriter] the caller
/// keeps (one per thread: neither is thread-safe) to avoid allocating one per call.
///
/// `reader` may return anything (including `null`) once it has recorded a failure in the [JsonReader]'s
/// error slot; no decode method lets such a value escape.
///
/// A codec has two forms. `writer` and `reader` are the **document** form: what [#decode] and [#encode] read
/// and write when the value is the whole document. `nestedWriter` and `nestedReader` are the **nested** form:
/// what a value is inside another one (a record component, a collection element, a map value). The two are
/// the same for every type except a value object, which is a JSON object of its one component as a document
/// (`{"value":"a@b.c"}`) and its bare component when nested (`"a@b.c"`).
///
/// The static combinators ([#listOf], [#setOf], [#optionOf], [#mapOf]) wrap a codec into one for a container
/// of its type. They write the same bytes and accept and refuse the same documents as the container code the
/// generator emits for a component of that type: elements and map values take the nested form. An option is no
/// container of its own: [#optionOf] keeps the form its value has where the option stands.
///
/// Every codec reads and writes under its `options` ([JsonOptions#DEFAULTS] unless bound by [#withOptions]). They
/// govern the whole document: a combinator's codec applies its own options to everything inside it, whatever the
/// options of the element codec it was built from.
public record JsonTypeCodec<T>(BodyWriter<T> writer,
                               BodyReader<T> reader,
                               BodyWriter<T> nestedWriter,
                               BodyReader<T> nestedReader,
                               JsonOptions options) {
    /// Appends `value` to the writer; cannot fail. `void` because it drives the [JsonWriter] carve-out.
    @FunctionalInterface
    public interface BodyWriter<T> {
        @Contract
        void write(JsonWriter out, T value);
    }

    @FunctionalInterface
    public interface BodyReader<T> {
        T read(JsonReader in);
    }

    /// A codec whose document and nested forms are the same, under [JsonOptions#DEFAULTS].
    public static <T> JsonTypeCodec<T> jsonTypeCodec(BodyWriter<T> writer, BodyReader<T> reader) {
        return new JsonTypeCodec<>(writer, reader, writer, reader, JsonOptions.DEFAULTS);
    }

    /// A codec whose document form (`writer`, `reader`) differs from its nested form (`nestedWriter`,
    /// `nestedReader`): a value object's. Under [JsonOptions#DEFAULTS].
    public static <T> JsonTypeCodec<T> jsonTypeCodec(BodyWriter<T> writer,
                                                     BodyReader<T> reader,
                                                     BodyWriter<T> nestedWriter,
                                                     BodyReader<T> nestedReader) {
        return new JsonTypeCodec<>(writer, reader, nestedWriter, nestedReader, JsonOptions.DEFAULTS);
    }

    /// This codec reading and writing under `options`: `BuyRequestJson.CODEC.withOptions(options)`,
    /// `JsonTypeCodec.listOf(BuyRequestJson.CODEC).withOptions(options)`, or a codec from `JsonCodecs.codecFor`.
    public JsonTypeCodec<T> withOptions(JsonOptions options) {
        return new JsonTypeCodec<>(writer, reader, nestedWriter, nestedReader, options);
    }

    /// Decodes the whole of `json`.
    public Result<T> decode(byte[] json) {
        return decode(JsonReader.jsonReader(), json);
    }

    /// Decodes `json[offset, offset + length)` in place: the region is read where it lies, never copied. A region
    /// that does not lie inside `json` is refused with [JsonError.InvalidRange] before anything is read. Because the
    /// region is not copied, a failure's position is an offset into the whole of `json`, not into the region.
    public Result<T> decode(byte[] json, int offset, int length) {
        return isInside(json.length, offset, length)
               ? read(JsonReader.jsonReader().reset(json, offset, length, options))
               : JsonError.InvalidRange.FACTORY.apply(offset, length, json.length).result();
    }

    /// Decodes `json`, encoded as UTF-8 first.
    public Result<T> decode(String json) {
        return decode(json.getBytes(StandardCharsets.UTF_8));
    }

    /// Decodes the whole of `json` with a reader the caller reuses; the reader is reset first, so its previous
    /// input and failure are forgotten.
    public Result<T> decode(JsonReader in, byte[] json) {
        return read(in.reset(json, 0, json.length, options));
    }

    /// Encodes `value` into a fresh writer.
    public byte[] encode(T value) {
        return encode(JsonWriter.jsonWriter(256), value);
    }

    /// Encodes `value` with a writer the caller reuses; the writer is reset first and returns a copy of its bytes.
    public byte[] encode(JsonWriter out, T value) {
        writer.write(out.reset(options), value);

        return out.toByteArray();
    }

    /// A JSON array of `elements` in their nested form, decoded into a mutable `ArrayList` in document order.
    public static <T> JsonTypeCodec<List<T>> listOf(JsonTypeCodec<T> elements) {
        return jsonTypeCodec((out, values) -> writeArray(out, values, elements.nestedWriter()),
                             in -> readArray(in, new ArrayList<>(), elements.nestedReader()));
    }

    /// A JSON array of `elements` in their nested form, decoded into a mutable `LinkedHashSet`: document order, a
    /// repeated element kept once.
    public static <T> JsonTypeCodec<Set<T>> setOf(JsonTypeCodec<T> elements) {
        return jsonTypeCodec((out, values) -> writeArray(out, values, elements.nestedWriter()),
                             in -> readArray(in, new LinkedHashSet<>(), elements.nestedReader()));
    }

    /// An optional value: `null` decodes to empty and empty encodes to `null`; anything else goes through `value`,
    /// in its document form as a document and its nested form when nested.
    public static <T> JsonTypeCodec<Option<T>> optionOf(JsonTypeCodec<T> value) {
        return jsonTypeCodec((out, option) -> writeOption(out, option, value.writer()),
                             in -> readOption(in, value.reader()),
                             (out, option) -> writeOption(out, option, value.nestedWriter()),
                             in -> readOption(in, value.nestedReader()));
    }

    /// A JSON object with free-form string keys and `values` in their nested form, decoded into a mutable
    /// `LinkedHashMap` in document order; a repeated key keeps its last value, unless the options refuse duplicates.
    public static <V> JsonTypeCodec<Map<String, V>> mapOf(JsonTypeCodec<V> values) {
        return jsonTypeCodec((out, map) -> writeObject(out, map, values.nestedWriter()),
                             in -> readObject(in, values.nestedReader()));
    }

    private Result<T> read(JsonReader in) {
        var value = reader.read(in);

        in.finish();

        return in.result(value);
    }

    private static boolean isInside(int size, int offset, int length) {
        return offset >= 0
               && length >= 0
               && offset <= size - length;
    }

    // The container bodies below mirror the generator's container code (JsonCodecEmitter#writeList, #writeMap,
    // #writeOptional and #readContainerBody) statement for statement; change both or neither.
    @Contract
    private static <T> void writeArray(JsonWriter out, Collection<T> values, BodyWriter<T> element) {
        out.writeByte('[');
        var first = true;

        for (var value : values) {
            if (!first) {
                out.writeByte(',');
            }

            first = false;
            element.write(out, value);
        }

        out.writeByte(']');
    }

    @Contract
    private static <V> void writeObject(JsonWriter out, Map<String, V> map, BodyWriter<V> value) {
        out.writeByte('{');
        var first = true;

        for (var entry : map.entrySet()) {
            if (!first) {
                out.writeByte(',');
            }

            first = false;
            out.writeString(entry.getKey());
            out.writeByte(':');
            value.write(out, entry.getValue());
        }

        out.writeByte('}');
    }

    @Contract
    private static <T> void writeOption(JsonWriter out, Option<T> option, BodyWriter<T> value) {
        switch (option) {
            case Option.Some<T>(var present) -> value.write(out, present);
            case Option.None<T>_ -> out.writeNull();
        }
    }

    private static <T, C extends Collection<T>> C readArray(JsonReader in, C values, BodyReader<T> element) {
        if (in.beginArray()) {
            for (var more = in.firstElement(); more; more = in.nextElement()) {
                values.add(element.read(in));
            }
        }

        return values;
    }

    private static <V> Map<String, V> readObject(JsonReader in, BodyReader<V> value) {
        var values = new LinkedHashMap<String, V>();

        if (in.beginObject()) {
            for (var more = in.firstEntry(); more; more = in.nextEntry()) {
                if (values.put(in.readKey(), value.read(in)) != null) {
                    in.repeatedKey();
                }
            }
        }

        return values;
    }

    private static <T> Option<T> readOption(JsonReader in, BodyReader<T> value) {
        return in.isNull()
               ? none()
               : some(value.read(in));
    }
}
