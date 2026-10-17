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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonTypeCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Generated codecs against the production slice-route mapper ([Baseline]) on shapes that once broke them:
/// value objects inside records, helper names derived from components, the 64-field mask limit, and empty
/// Option values in every position the generator supports.
class GeneratedCodecEdgeCasesTest {
    @Json
    record Email(String value) {
        public static Result<Email> email(String value) {
            return value.contains("@")
                   ? Result.success(new Email(value))
                   : Causes.cause("not an email").result();
        }
    }

    @Json
    record Contact(Email email) {}

    @Json
    record Clash(List<String> nested0, List<List<String>> other) {}

    @Json
    record PrefixClash(List<List<String>> readNested0, List<List<String>> readNested1, List<String> read_readNested0) {}

    @Json
    record Wide(String f0, String f1, String f2, String f3, String f4, String f5, String f6, String f7, String f8, String f9, String f10, String f11, String f12, String f13, String f14, String f15, String f16, String f17, String f18, String f19, String f20, String f21, String f22, String f23, String f24, String f25, String f26, String f27, String f28, String f29, String f30, String f31, String f32, String f33, String f34, String f35, String f36, String f37, String f38, String f39, String f40, String f41, String f42, String f43, String f44, String f45, String f46, String f47, String f48, String f49, String f50, String f51, String f52, String f53, String f54, String f55, String f56, String f57, String f58, String f59, String f60, String f61, String f62, String f63) {}

    @Json
    record WideOptional(String f0, String f1, String f2, String f3, String f4, String f5, String f6, String f7, String f8, String f9, String f10, String f11, String f12, String f13, String f14, String f15, String f16, String f17, String f18, String f19, String f20, String f21, String f22, String f23, String f24, String f25, String f26, String f27, String f28, String f29, String f30, String f31, String f32, String f33, String f34, String f35, String f36, String f37, String f38, String f39, String f40, String f41, String f42, String f43, String f44, String f45, String f46, String f47, String f48, String f49, String f50, String f51, String f52, String f53, String f54, String f55, String f56, String f57, String f58, String f59, String f60, String f61, String f62, Option<String> f63) {}

    @Json
    record Maybe(String id, Option<String> note, List<Option<String>> list, Map<String, Option<String>> byKey) {}

    @Test
    void valueObjectInRecord_generatedBytesEqualProductionMapper_andDecodeEachOther() {
        var contact = new Contact(new Email("a@b.c"));
        var generated = GeneratedCodecEdgeCasesTest_ContactJsonCodecs.GeneratedCodecEdgeCasesTest_ContactJson.CODEC.encode(contact);

        assertEquals("{\"email\":\"a@b.c\"}", text(generated));
        assertEquals(text(Baseline.MAPPER.writeAsBytes(contact).unwrap()), text(generated));
        assertEquals(contact, Baseline.MAPPER.readBytes(generated, new TypeToken<Contact>() {}).unwrap());
    }

    @Test
    void helperNamesDerivedFromComponents_neverCollide_andRoundTrip() {
        var clash = new Clash(List.of("a"), List.of(List.of("b", "c"), List.of()));
        var prefix = new PrefixClash(List.of(List.of("x")), List.of(), List.of("y"));

        assertRoundTrip(GeneratedCodecEdgeCasesTest_ClashJsonCodecs.GeneratedCodecEdgeCasesTest_ClashJson.CODEC, clash);
        assertRoundTrip(GeneratedCodecEdgeCasesTest_PrefixClashJsonCodecs.GeneratedCodecEdgeCasesTest_PrefixClashJson.CODEC, prefix);
    }

    @Test
    void sixtyFourComponents_roundTrip_andEveryMissingFieldIsRefused() {
        var codec = GeneratedCodecEdgeCasesTest_WideJsonCodecs.GeneratedCodecEdgeCasesTest_WideJson.CODEC;
        var full = Baseline.MAPPER.readBytes(wideJson(-1).getBytes(StandardCharsets.UTF_8), Wide.class).unwrap();

        assertRoundTrip(codec, full);
        assertTrue(codec.decode(wideJson(0).getBytes(StandardCharsets.UTF_8)).isFailure(), "missing f0 accepted");
        assertTrue(codec.decode(wideJson(63).getBytes(StandardCharsets.UTF_8)).isFailure(), "missing f63 accepted");
    }

    @Test
    void optionalSixtyFourthComponent_absentKeyDecodesAsNone() {
        var codec = GeneratedCodecEdgeCasesTest_WideOptionalJsonCodecs.GeneratedCodecEdgeCasesTest_WideOptionalJson.CODEC;

        assertEquals(Option.none(), codec.decode(wideJson(63).getBytes(StandardCharsets.UTF_8)).unwrap().f63());
        assertEquals(Option.some("v63"), codec.decode(wideJson(-1).getBytes(StandardCharsets.UTF_8)).unwrap().f63());
    }

    @Test
    void emptyOption_inComponentListAndMapValue_generatedBytesEqualProductionMapper() {
        var maybe = new Maybe("1", Option.none(), List.of(Option.none(), Option.some("x")), Map.of("k", Option.none()));
        var generated = GeneratedCodecEdgeCasesTest_MaybeJsonCodecs.GeneratedCodecEdgeCasesTest_MaybeJson.CODEC.encode(maybe);

        assertEquals("{\"id\":\"1\",\"note\":null,\"list\":[null,\"x\"],\"byKey\":{\"k\":null}}", text(generated));
        assertEquals(text(Baseline.MAPPER.writeAsBytes(maybe).unwrap()), text(generated));
    }

    @Test
    void emptyOption_absentKeyAndLiteralNull_bothDecodeAsNone_generatedAndProductionMapper() {
        var expected = new Maybe("1", Option.none(), List.of(Option.none()), Map.of("k", Option.none()));
        var codec = GeneratedCodecEdgeCasesTest_MaybeJsonCodecs.GeneratedCodecEdgeCasesTest_MaybeJson.CODEC;

        for (var json : List.of("{\"id\":\"1\",\"list\":[null],\"byKey\":{\"k\":null}}",
                                "{\"id\":\"1\",\"note\":null,\"list\":[null],\"byKey\":{\"k\":null}}")) {
            var bytes = json.getBytes(StandardCharsets.UTF_8);

            assertEquals(expected, codec.decode(bytes).unwrap(), "generated: " + json);
            assertEquals(expected, Baseline.MAPPER.readBytes(bytes, new TypeToken<Maybe>() {}).unwrap(), "production mapper: " + json);
        }
    }

    // All 64 fields "v<i>", except field `missing` (-1: none missing).
    private static String wideJson(int missing) {
        return IntStream.range(0, 64)
                        .filter(i -> i != missing)
                        .mapToObj(i -> "\"f" + i + "\":\"v" + i + "\"")
                        .collect(Collectors.joining(",", "{", "}"));
    }

    private static <T> void assertRoundTrip(JsonTypeCodec<T> codec, T value) {
        var generated = codec.encode(value);

        assertEquals(text(Baseline.MAPPER.writeAsBytes(value).unwrap()), text(generated));
        assertEquals(value, codec.decode(generated).unwrap());
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
