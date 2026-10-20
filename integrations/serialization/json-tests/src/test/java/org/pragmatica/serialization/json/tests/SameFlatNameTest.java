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
package org.pragmatica.serialization.json.tests;

import org.junit.jupiter.api.Test;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonTypeCodec;
import org.pragmatica.serialization.json.tests.dup.y.Foo;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// A `@Json` root reaching two types of different packages with one simple name, so one flat name (`Foo` is the `y`
/// one; the `z` one is written qualified): the entry point numbers their nested codecs and keeps them apart, compiles,
/// and round-trips both.
class SameFlatNameTest {
    @Json
    record Both(org.pragmatica.serialization.json.tests.dup.z.Foo counted,
                Foo named) {}

    @Test
    void entryPoint_roundTripsTwoReachableTypesSharingAFlatName() {
        var value = new Both(new org.pragmatica.serialization.json.tests.dup.z.Foo(7),
                             new Foo("x"));
        var json = "{\"counted\":{\"count\":7},\"named\":{\"name\":\"x\"}}";

        assertEquals(json, new String(SameFlatNameTest_BothJson.encode(value), StandardCharsets.UTF_8));
        assertEquals(value, SameFlatNameTest_BothJson.decode(json).unwrap());
    }

    @Test
    void entryPoint_listsACodecPerReachableType_underItsOwnClass() {
        var named = SameFlatNameTest_BothJson.CODECS.get(Foo.class);
        var counted = SameFlatNameTest_BothJson.CODECS.get(org.pragmatica.serialization.json.tests.dup.z.Foo.class);

        assertEquals("{\"name\":\"y\"}",
                     new String(cast(named).encode(new Foo("y")), StandardCharsets.UTF_8));
        assertEquals("{\"count\":3}",
                     new String(cast(counted).encode(new org.pragmatica.serialization.json.tests.dup.z.Foo(3)), StandardCharsets.UTF_8));
        assertSame(SameFlatNameTest_BothJson.CODEC, SameFlatNameTest_BothJson.CODECS.get(Both.class));
    }

    @SuppressWarnings("unchecked")
    private static JsonTypeCodec<Object> cast(JsonTypeCodec<?> codec) {
        return (JsonTypeCodec<Object>) codec;
    }
}
