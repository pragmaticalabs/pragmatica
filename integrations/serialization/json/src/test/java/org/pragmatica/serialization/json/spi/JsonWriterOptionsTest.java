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
package org.pragmatica.serialization.json.spi;

import java.nio.charset.StandardCharsets;

import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.JsonOptions.NullInclusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


/// Empty `Option` components under `nullInclusion`: written as `null`, or left out without leaving a comma behind,
/// whichever members are left out.
class JsonWriterOptionsTest {
    static final FieldNames NAMES = FieldNames.fieldNames("a", "b", "c");

    static final JsonOptions EXCLUDE = JsonOptions.jsonOptions(NullInclusion.EXCLUDE,
                                                               false,
                                                               false,
                                                               JsonReader.MAX_DEPTH,
                                                               Integer.MAX_VALUE,
                                                               Integer.MAX_VALUE,
                                                               Integer.MAX_VALUE).unwrap();

    // A three-member object as generated code writes it; null stands for an empty Option.
    static void object(JsonWriter out, String a, String b, String c) {
        out.writeByte('{');
        member(out, 0, a);
        member(out, 1, b);
        member(out, 2, c);
        out.writeByte('}');
    }

    static void member(JsonWriter out, int index, String value) {
        var name = index == 0
                   ? NAMES.first(index)
                   : NAMES.next(index);

        if (value == null) {
            out.writeEmpty(name);
        } else {
            out.writeRaw(name);
            out.writeString(value);
        }
    }

    static String text(JsonWriter out) {
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static String written(JsonOptions options, String a, String b, String c) {
        var out = JsonWriter.jsonWriter(64).reset(options);

        object(out, a, b, c);

        return text(out);
    }

    @Test
    void writeEmpty_include_writesTheNameAndNull() {
        assertEquals("{\"a\":null,\"b\":\"x\",\"c\":null}", written(JsonOptions.DEFAULTS, null, "x", null));
    }

    @Test
    void writeEmpty_exclude_leavesOutEveryEmptyMember_wherever_itStands() {
        assertEquals("{\"b\":\"x\"}", written(EXCLUDE, null, "x", null));
        assertEquals("{\"c\":\"y\"}", written(EXCLUDE, null, null, "y"));
        assertEquals("{\"a\":\"x\",\"c\":\"y\"}", written(EXCLUDE, "x", null, "y"));
        assertEquals("{\"a\":\"x\"}", written(EXCLUDE, "x", null, null));
        assertEquals("{}", written(EXCLUDE, null, null, null));
        assertEquals("{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"}", written(EXCLUDE, "x", "y", "z"));
    }

    @Test
    void writeEmpty_exclude_nestedObjectsAndSiblings_eachLoseOnlyTheirOwnComma() {
        var out = JsonWriter.jsonWriter(16).reset(EXCLUDE);

        out.writeByte('[');
        object(out, null, null, "1");
        out.writeByte(',');
        out.writeByte('{');
        out.writeEmpty(NAMES.first(0));
        out.writeRaw(NAMES.next(1));
        object(out, null, "2", null);
        out.writeRaw(NAMES.next(2));
        out.writeString(",");
        out.writeByte('}');
        out.writeByte(',');
        object(out, null, null, null);
        out.writeByte(']');
        assertEquals("[{\"c\":\"1\"},{\"b\":{\"b\":\"2\"},\"c\":\",\"},{}]", text(out));
    }

    @Test
    void reset_forgetsPendingOmissions_andFallsBackToTheDefaults() {
        var out = JsonWriter.jsonWriter(64).reset(EXCLUDE);

        out.writeByte('{');
        out.writeEmpty(NAMES.first(0));
        out.reset();
        object(out, null, "x", null);
        assertEquals("{\"a\":null,\"b\":\"x\",\"c\":null}", text(out));
    }

    @Test
    void sizeAndBuffer_seeTheCompactedDocument() {
        var out = JsonWriter.jsonWriter(64).reset(EXCLUDE);

        object(out, null, "x", null);
        assertEquals("{\"b\":\"x\"}".length(), out.size());
        assertEquals("{\"b\":\"x\"}",
                     new String(out.buffer(), 0, out.size(), StandardCharsets.UTF_8));
    }
}
