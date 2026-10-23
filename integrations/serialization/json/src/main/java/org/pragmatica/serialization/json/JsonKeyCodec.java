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

import org.pragmatica.lang.Contract;
import org.pragmatica.serialization.json.spi.JsonReader;
import org.pragmatica.serialization.json.spi.JsonWriter;


/// How a map key travels: a JSON object member name, written and read as the generator's own map code writes and
/// reads one. [JsonTypeCodec#mapOf(JsonKeyCodec, JsonTypeCodec)] takes one for a map whose keys are not strings.
///
/// The keys a member name can carry are the ones the generator accepts for a `Map` component: [#STRING], an enum
/// (its constant's JSON name) and a value object over a `String` (its bare value, read back through its factory). The
/// generated entry point of an enum or such a value object exposes its key codec as `KEY` (`ProductIdJson.KEY`).
///
/// `reader` may return anything (including `null`) once it has recorded a failure in the reader's error slot, as a
/// codec's reader may.
public record JsonKeyCodec<K>(KeyWriter<K> writer, KeyReader<K> reader) {
    /// Writes `key` as a quoted member name (without the `:`); cannot fail.
    @FunctionalInterface
    public interface KeyWriter<K> {
        @Contract
        void write(JsonWriter out, K key);
    }

    /// Reads one member name and its `:`.
    @FunctionalInterface
    public interface KeyReader<K> {
        K read(JsonReader in);
    }

    /// Free-form string keys.
    public static final JsonKeyCodec<String> STRING = jsonKeyCodec(JsonWriter::writeString, JsonReader::readKey);

    public static <K> JsonKeyCodec<K> jsonKeyCodec(KeyWriter<K> writer, KeyReader<K> reader) {
        return new JsonKeyCodec<>(writer, reader);
    }
}
