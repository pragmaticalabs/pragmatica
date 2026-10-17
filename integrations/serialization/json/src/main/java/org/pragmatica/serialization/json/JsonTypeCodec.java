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

import org.pragmatica.lang.Result;

/// A generated codec: one write function and one read function for a type. Not to be confused with
/// `org.pragmatica.http.JsonCodec`, the HTTP seam a slice's generated codecs are adapted to.
///
/// `reader` may return anything (including `null`) once it has recorded a failure in the
/// [JsonReader]'s error slot; [#decode] never lets such a value escape.
public record JsonTypeCodec<T>(BodyWriter<T> writer, BodyReader<T> reader) {
    @FunctionalInterface
    public interface BodyWriter<T> {
        void write(JsonWriter out, T value);
    }

    @FunctionalInterface
    public interface BodyReader<T> {
        T read(JsonReader in);
    }

    public static <T> JsonTypeCodec<T> jsonTypeCodec(BodyWriter<T> writer, BodyReader<T> reader) {
        return new JsonTypeCodec<>(writer, reader);
    }

    public Result<T> decode(JsonReader in) {
        var value = reader.read(in);
        in.finish();
        return in.result(value);
    }

    /// Decodes with a reused reader: the unwrapped value, or `null` on failure. Benchmark entry point.
    public T decode(JsonReader in, byte[] json) {
        var value = reader.read(in.reset(json));
        in.finish();
        return in.hasError()
               ? null
               : value;
    }

    public Result<T> decode(byte[] json) {
        return decode(JsonReader.jsonReader().reset(json));
    }

    public byte[] encode(JsonWriter out, T value) {
        writer.write(out.reset(), value);
        return out.toByteArray();
    }

    public byte[] encode(T value) {
        return encode(JsonWriter.jsonWriter(256), value);
    }
}
