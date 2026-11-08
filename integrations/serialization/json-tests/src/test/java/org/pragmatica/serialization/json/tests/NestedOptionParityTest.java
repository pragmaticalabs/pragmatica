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
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.tests.ValueObjectFixtures.Count;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// An Option nested in a container decodes to the same typed value on the Jackson path (the fallback a refused route
/// type takes) as through the generated codec. Value-object shapes go through the value-object mapper, the only one
/// that shares the generated codec's bare wire form; the plain-record shape goes through both mappers.
class NestedOptionParityTest {
    private static final JsonMapper VALUE_OBJECTS = JsonMapper.valueObjectJsonMapper();
    private static final JsonMapper DEFAULT = JsonMapper.defaultJsonMapper();

    @Json
    record ListOptVo(List<Option<Count>> x) {}

    @Json
    record MapOptVo(Map<String, Option<Count>> x) {}

    @Json
    record OptOptVo(Option<Option<Count>> x) {}

    record Plain(long a) {}

    @Json
    record ListOptRecord(List<Option<Plain>> x) {}

    @Test
    void listOfOptionalValueObjects_jacksonEqualsGenerated() {
        var json = bytes("{\"x\":[1,null]}");
        var generated = NestedOptionParityTest_ListOptVoJson.decode(json).unwrap();

        assertEquals(new ListOptVo(List.of(Option.some(new Count(1)), Option.none())), generated);
        assertEquals(generated, VALUE_OBJECTS.readBytes(json, ListOptVo.class).unwrap());
    }

    @Test
    void mapOfOptionalValueObjects_jacksonEqualsGenerated() {
        var json = bytes("{\"x\":{\"k\":1,\"n\":null}}");
        var generated = NestedOptionParityTest_MapOptVoJson.decode(json).unwrap();

        assertEquals(Option.some(new Count(1)), generated.x().get("k"));
        assertEquals(generated, VALUE_OBJECTS.readBytes(json, MapOptVo.class).unwrap());
    }

    @Test
    void optionOfOptionalValueObject_jacksonEqualsGenerated() {
        var json = bytes("{\"x\":1}");
        var generated = NestedOptionParityTest_OptOptVoJson.decode(json).unwrap();

        assertEquals(new OptOptVo(Option.some(Option.some(new Count(1)))), generated);
        assertEquals(generated, VALUE_OBJECTS.readBytes(json, OptOptVo.class).unwrap());
    }

    @Test
    void listOfOptionalRecords_bothMappersEqualGenerated() {
        var json = bytes("{\"x\":[{\"a\":1},null]}");
        var generated = NestedOptionParityTest_ListOptRecordJson.decode(json).unwrap();

        assertEquals(new ListOptRecord(List.of(Option.some(new Plain(1)), Option.none())), generated);
        assertEquals(generated, read(VALUE_OBJECTS, json));
        assertEquals(generated, read(DEFAULT, json));
    }

    private static ListOptRecord read(JsonMapper mapper, byte[] json) {
        return mapper.readBytes(json, ListOptRecord.class).unwrap();
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
