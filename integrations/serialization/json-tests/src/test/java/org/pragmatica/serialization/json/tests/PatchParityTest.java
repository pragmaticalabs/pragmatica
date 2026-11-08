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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.JsonOptions.NullInclusion;
import org.pragmatica.serialization.json.JsonTypeCodec;
import org.pragmatica.serialization.json.tests.ValueObjectFixtures.Count;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #2884: a `Patch` record component reads and writes the same way through the generated codec and the Jackson
/// mapper: a missing member is `Absent` and is left out on write, `null` is `Null` and is written as `null`, a value is
/// a `Value`; and under either null-inclusion option. `Option` keeps its own behaviour (pinned here beside it): a
/// missing member and `null` are both empty. Every position other than a record component is refused by both paths.
class PatchParityTest {
    private static final JsonMapper VALUE_OBJECTS = JsonMapper.valueObjectJsonMapper();
    private static final JsonMapper DEFAULT = JsonMapper.defaultJsonMapper();
    private static final JsonOptions EXCLUDE = new JsonOptions(NullInclusion.EXCLUDE,
                                                               false,
                                                               false,
                                                               JsonOptions.DEFAULTS.maxDepth(),
                                                               Integer.MAX_VALUE,
                                                               Integer.MAX_VALUE,
                                                               Integer.MAX_VALUE);
    private static final JsonMapper VALUE_OBJECTS_EXCLUDE = JsonMapper.jsonMapper()
                                                                      .withPragmaticaTypes()
                                                                      .withValueObjects()
                                                                      .withOptions(EXCLUDE)
                                                                      .build();
    private static final String POSITION = "a Patch is supported only as a record component, over a type that is neither an Option nor a Patch";

    record Plain(long a) {}

    // `name` first: an Absent first member must not leave the next member's comma behind.
    @Json
    record Update(Patch<String> name,
                  Patch<Integer> age,
                  Patch<Count> count,
                  Patch<List<String>> tags,
                  Patch<Plain> address,
                  String id) {}

    @Json
    record PlainUpdate(Patch<String> name, Patch<Plain> address) {}

    @Json
    record OptionUpdate(Option<String> name, String id) {}

    private static final Update ALL_ABSENT = new Update(Patch.absent(),
                                                        Patch.absent(),
                                                        Patch.absent(),
                                                        Patch.absent(),
                                                        Patch.absent(),
                                                        "x");
    private static final Update ALL_NULL = new Update(Patch.nullValue(),
                                                      Patch.nullValue(),
                                                      Patch.nullValue(),
                                                      Patch.nullValue(),
                                                      Patch.nullValue(),
                                                      "x");
    private static final Update ALL_VALUES = new Update(Patch.value("n"),
                                                        Patch.value(3),
                                                        Patch.value(new Count(5)),
                                                        Patch.value(List.of("a", "b")),
                                                        Patch.value(new Plain(1)),
                                                        "x");
    private static final Update MIXED = new Update(Patch.value("n"),
                                                   Patch.nullValue(),
                                                   Patch.absent(),
                                                   Patch.value(List.of()),
                                                   Patch.absent(),
                                                   "x");

    static Stream<Object[]> states() {
        return Stream.of(new Object[]{"{\"id\":\"x\"}", ALL_ABSENT},
                         new Object[]{"{\"name\":null,\"age\":null,\"count\":null,\"tags\":null,\"address\":null,\"id\":\"x\"}", ALL_NULL},
                         new Object[]{"{\"name\":\"n\",\"age\":3,\"count\":5,\"tags\":[\"a\",\"b\"],\"address\":{\"a\":1},\"id\":\"x\"}", ALL_VALUES},
                         new Object[]{"{\"name\":\"n\",\"age\":null,\"tags\":[],\"id\":\"x\"}", MIXED});
    }

    @ParameterizedTest
    @MethodSource("states")
    void decode_eachState_sameInBothPaths(String json, Update expected) {
        var generated = PatchParityTest_UpdateJson.decode(bytes(json)).unwrap();

        assertEquals(expected, generated);
        assertEquals(generated, VALUE_OBJECTS.readBytes(bytes(json), Update.class).unwrap());
    }

    // The members in another order (and Absent ones interleaved) read the same: presence, not position, decides.
    @Test
    void decode_membersInAnyOrder_sameInBothPaths() {
        var json = bytes("{\"id\":\"x\",\"tags\":[],\"age\":null,\"name\":\"n\"}");

        assertEquals(MIXED, PatchParityTest_UpdateJson.decode(json).unwrap());
        assertEquals(MIXED, VALUE_OBJECTS.readBytes(json, Update.class).unwrap());
    }

    @ParameterizedTest
    @MethodSource("states")
    void encode_eachState_sameBytesInBothPaths(String json, Update value) {
        assertEquals(json, utf8(PatchParityTest_UpdateJson.encode(value)));
        assertEquals(json, VALUE_OBJECTS.writeAsString(value).unwrap());
    }

    // The null-inclusion option governs empty Options only: Absent is left out and Null is written either way.
    @ParameterizedTest
    @MethodSource("states")
    void encode_underExcludeOption_sameBytesAsUnderInclude(String json, Update value) {
        assertEquals(json, utf8(codec().withOptions(EXCLUDE).encode(value)));
        assertEquals(json, VALUE_OBJECTS_EXCLUDE.writeAsString(value).unwrap());
    }

    @ParameterizedTest
    @MethodSource("states")
    void roundTrip_eachState_preservedInBothPaths(String json, Update value) {
        var generated = PatchParityTest_UpdateJson.encode(value);
        var jackson = VALUE_OBJECTS.writeAsBytes(value).unwrap();

        assertEquals(value, PatchParityTest_UpdateJson.decode(generated).unwrap());
        assertEquals(value, VALUE_OBJECTS.readBytes(jackson, Update.class).unwrap());
        assertEquals(value, VALUE_OBJECTS.readBytes(generated, Update.class).unwrap());
        assertEquals(value, PatchParityTest_UpdateJson.decode(jackson).unwrap());
    }

    // The plain default mapper (no value-object module) agrees too, for a record without value objects.
    @Test
    void plainRecord_defaultMapperEqualsGenerated() {
        for (var json : List.of("{}", "{\"name\":null,\"address\":null}", "{\"name\":\"n\",\"address\":{\"a\":2}}")) {
            var generated = PatchParityTest_PlainUpdateJson.decode(bytes(json)).unwrap();

            assertEquals(generated, DEFAULT.readBytes(bytes(json), PlainUpdate.class).unwrap());
            assertEquals(json, utf8(PatchParityTest_PlainUpdateJson.encode(generated)));
            assertEquals(json, DEFAULT.writeAsString(generated).unwrap());
        }
    }

    @Test
    void decode_absentMember_isNotRequired_butAPlainOneStillIs() {
        assertTrue(PatchParityTest_UpdateJson.decode(bytes("{}")).isFailure());
        assertTrue(PatchParityTest_UpdateJson.decode(bytes("{\"id\":\"x\"}")).isSuccess());
    }

    // ---- Option is unchanged (regression pin) ------------------------------------------------------

    @Test
    void option_missingAndNull_bothEmpty_inBothPaths() {
        var expected = new OptionUpdate(Option.none(), "x");

        for (var json : List.of("{\"id\":\"x\"}", "{\"name\":null,\"id\":\"x\"}")) {
            assertEquals(expected, PatchParityTest_OptionUpdateJson.decode(bytes(json)).unwrap());
            assertEquals(expected, VALUE_OBJECTS.readBytes(bytes(json), OptionUpdate.class).unwrap());
        }
    }

    @Test
    void option_emptyWrittenAsNull_orLeftOutUnderExclude_inBothPaths() {
        var empty = new OptionUpdate(Option.none(), "x");
        var included = "{\"name\":null,\"id\":\"x\"}";
        var excluded = "{\"id\":\"x\"}";

        assertEquals(included, utf8(PatchParityTest_OptionUpdateJson.encode(empty)));
        assertEquals(included, VALUE_OBJECTS.writeAsString(empty).unwrap());
        assertEquals(excluded, utf8(PatchParityTest_OptionUpdateJson.codec(EXCLUDE).encode(empty)));
        assertEquals(excluded, VALUE_OBJECTS_EXCLUDE.writeAsString(empty).unwrap());
    }

    // ---- unsupported positions (Jackson; the generator's refusals are pinned in CodecProcessorTest) ---

    record ListOfPatch(List<Patch<String>> x) {}

    record MapOfPatch(Map<String, Patch<String>> x) {}

    record OptionOfPatch(Option<Patch<String>> x) {}

    record PatchOfOption(Patch<Option<String>> x) {}

    record PatchOfPatch(Patch<Patch<String>> x) {}

    record PatchOfListOfPatch(Patch<List<Patch<String>>> x) {}

    static Stream<Object[]> unsupported() {
        return Stream.of(new Object[]{ListOfPatch.class, new ListOfPatch(List.of(Patch.value("a")))},
                         new Object[]{MapOfPatch.class, new MapOfPatch(Map.of("k", Patch.value("a")))},
                         new Object[]{OptionOfPatch.class, new OptionOfPatch(Option.some(Patch.value("a")))},
                         new Object[]{PatchOfOption.class, new PatchOfOption(Patch.value(Option.some("a")))},
                         new Object[]{PatchOfPatch.class, new PatchOfPatch(Patch.value(Patch.value("a")))},
                         new Object[]{PatchOfListOfPatch.class, new PatchOfListOfPatch(Patch.value(List.of(Patch.value("a"))))});
    }

    @ParameterizedTest
    @MethodSource("unsupported")
    void jackson_patchOutsideARecordComponent_refusedOnReadAndWrite(Class<?> type, Object value) {
        assertRefused(VALUE_OBJECTS.readBytes(bytes("{\"x\":null}"), type));
        assertRefused(VALUE_OBJECTS.writeAsString(value));
    }

    @Test
    void jackson_patchAsTheWholeDocument_refusedOnReadAndWrite() {
        assertRefused(VALUE_OBJECTS.readBytes(bytes("\"a\""), new TypeToken<Patch<String>>() {}));
        assertRefused(VALUE_OBJECTS.writeAsString(Patch.value("a")));
    }

    record AlwaysIncluded(@JsonInclude(JsonInclude.Include.ALWAYS) Patch<String> x) {}

    // A member whose own inclusion overrides the module's would write Absent as null ("clear it"): refused.
    @Test
    void jackson_absentThatAMemberInclusionWouldWrite_refused() {
        var failure = VALUE_OBJECTS.writeAsString(new AlwaysIncluded(Patch.absent()));

        assertTrue(failureMessage(failure).contains("an Absent Patch must be left out"), failureMessage(failure));
        assertEquals("{\"x\":null}", VALUE_OBJECTS.writeAsString(new AlwaysIncluded(Patch.nullValue())).unwrap());
    }

    private static void assertRefused(Result<?> result) {
        assertTrue(failureMessage(result).contains(POSITION), failureMessage(result));
    }

    private static String failureMessage(Result<?> result) {
        return result.fold(Cause::message, value -> "unexpected success: " + value);
    }

    private static JsonTypeCodec<Update> codec() {
        return PatchParityTest_UpdateJson.CODEC;
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
