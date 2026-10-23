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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonError;
import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.JsonOptions.NullInclusion;
import org.pragmatica.serialization.json.NamingStrategy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.PropertyNamingStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// The one-wire-form guarantee under configuration: generated codecs bound to [JsonOptions] (and compiled under a
/// [NamingStrategy]) against the production Jackson mapper given the same options (`withOptions`) and naming
/// (`withNaming`). Encoding must produce identical bytes; decoding must accept and refuse the same documents, each
/// limit probed at its boundary minus one, at it, and plus one, with the expected outcome pinned too, so that both
/// sides agreeing on a wrong answer still fails.
class OptionsConformanceTest {
    static final int MAX = Integer.MAX_VALUE;
    private static final Map<List<Object>, JsonMapper> MAPPERS = new ConcurrentHashMap<>();

    static JsonOptions options(NullInclusion inclusion,
                               boolean failOnUnknown,
                               boolean failOnDuplicate,
                               int maxDepth,
                               int maxStringLength,
                               int maxNumberLength,
                               int maxDocumentSize) {
        return JsonOptions.jsonOptions(inclusion,
                                       failOnUnknown,
                                       failOnDuplicate,
                                       maxDepth,
                                       maxStringLength,
                                       maxNumberLength,
                                       maxDocumentSize).unwrap();
    }

    static JsonOptions inclusion(NullInclusion inclusion) {
        return options(inclusion, false, false, 1000, MAX, MAX, MAX);
    }

    /// The production mapper (`Baseline`: Pragmatica types and value objects) with the naming and options applied.
    static JsonMapper jackson(NamingStrategy naming, JsonOptions options) {
        return MAPPERS.computeIfAbsent(List.of(naming, options),
                                       _ -> JsonMapper.jsonMapper()
                                                      .withPragmaticaTypes()
                                                      .withValueObjects()
                                                      .withNaming(naming)
                                                      .withOptions(options)
                                                      .build());
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause,
                           value -> {
                               throw new AssertionError("expected a failure, got " + value);
                           });
    }

    // ---- encode ------------------------------------------------------------------------------------------------
    record Payload(String type, String sample, String entryPoint) {
        @Override
        public String toString() {
            return type + "." + sample;
        }
    }

    static final List<Payload> PAYLOADS = List.of(new Payload("Samples", "buyRequest", "Ticketing_BuyRequestJson"),
                                                  new Payload("Samples", "buyResponse", "Ticketing_BuyResponseJson"),
                                                  new Payload("Samples", "catalog", "Catalog_ItemListV2Json"),
                                                  new Payload("Samples",
                                                              "placeOrderRequest",
                                                              "Ecommerce_PlaceOrderRequestJson"),
                                                  new Payload("Samples",
                                                              "orderConfirmation",
                                                              "Ecommerce_OrderConfirmationJson"),
                                                  new Payload("Samples", "refundRequest", "Ecommerce_RefundRequestJson"),
                                                  new Payload("Samples", "telemetry", "Telemetry_SeriesJson"),
                                                  new Payload("OptionsFixtures",
                                                              "emptyProfile",
                                                              "OptionsFixtures_UserProfileJson"),
                                                  new Payload("OptionsFixtures",
                                                              "fullProfile",
                                                              "OptionsFixtures_UserProfileJson"));

    static Stream<Arguments> combinations() {
        return Arrays.stream(NamingStrategy.values()).flatMap(naming -> Arrays.stream(NullInclusion.values()).flatMap(inclusion -> PAYLOADS.stream().map(payload -> Arguments.of(naming,
                                                                                                                                                                                 inclusion,
                                                                                                                                                                                 payload))));
    }

    @Nested
    class Encode {
        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("org.pragmatica.serialization.json.tests.OptionsConformanceTest#combinations")
        void encode_matchesJacksonBytes_underNamingAndInclusion(NamingStrategy naming,
                                                                NullInclusion inclusion,
                                                                Payload payload) {
            var options = inclusion(inclusion);
            var value = NamedCompilation.sample(naming, payload.type(), payload.sample());
            var ours = utf8(NamedCompilation.codec(naming, payload.entryPoint(), options).encode(value));
            var theirs = utf8(jackson(naming, options).writeAsBytes(value).unwrap());

            assertEquals(theirs, ours);
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @MethodSource("org.pragmatica.serialization.json.tests.OptionsConformanceTest#combinations")
        void decode_ofJacksonBytes_equalsTheValue_underNamingAndInclusion(NamingStrategy naming,
                                                                          NullInclusion inclusion,
                                                                          Payload payload) {
            var options = inclusion(inclusion);
            var value = NamedCompilation.sample(naming, payload.type(), payload.sample());
            var theirs = jackson(naming, options).writeAsBytes(value).unwrap();

            assertEquals(value,
                         NamedCompilation.codec(naming, payload.entryPoint(), options).decode(theirs).unwrap());
        }

        // Agreement alone cannot tell a rename from no rename on both sides; these pin what the bytes must say.
        @Test
        void encode_namesFollowTheStrategy_exceptJsonNamedOnes() {
            var expected = Map.of(NamingStrategy.AS_DECLARED,
                                  "\"nickName\":\"jojo\"",
                                  NamingStrategy.SNAKE_CASE,
                                  "\"nick_name\":\"jojo\"",
                                  NamingStrategy.KEBAB_CASE,
                                  "\"nick-name\":\"jojo\"",
                                  NamingStrategy.PASCAL_CASE,
                                  "\"NickName\":\"jojo\"");

            for (var naming : NamingStrategy.values()) {
                var json = profile(naming, "fullProfile", NullInclusion.INCLUDE);

                assertTrue(json.contains(expected.get(naming)),
                           naming + ": " + json);
                assertTrue(json.contains("\"legacy_Name\":\"Jo Example\""), naming + ": " + json);
            }
        }

        @Test
        void encode_exclude_leavesOutEmptyComponents_withoutAStrayComma_butKeepsNullsInContainers() {
            var json = profile(NamingStrategy.AS_DECLARED, "emptyProfile", NullInclusion.EXCLUDE);

            assertEquals("{\"legacy_Name\":\"Jo Example\",\"planTier\":\"pro-tier\",\"aliasNames\":[null,\"jo\"],"
                        + "\"scoreBoard\":{\"a\":null,\"b\":7},\"backupContact\":{},\"planNotes\":{\"pro-tier\":\"upgraded\"}}",
                         json);
        }

        @Test
        void encode_include_writesEveryEmptyComponentAsNull() {
            var json = profile(NamingStrategy.AS_DECLARED, "emptyProfile", NullInclusion.INCLUDE);

            assertTrue(json.startsWith("{\"nickName\":null,\"legacy_Name\":"), json);
            assertTrue(json.endsWith(",\"trailingNote\":null}"), json);
            assertTrue(json.contains("\"backupContact\":{\"emailAddress\":null,\"phoneNumber\":null}"), json);
        }

        private static String profile(NamingStrategy naming, String sample, NullInclusion inclusion) {
            return utf8(NamedCompilation.codec(naming, "OptionsFixtures_UserProfileJson", inclusion(inclusion)).encode(NamedCompilation.sample(naming,
                                                                                                                                               "OptionsFixtures",
                                                                                                                                               sample)));
        }

        // The statically compiled codec (the test class path's default compilation) agrees as well.
        @Test
        void encode_defaultCompilation_matchesJackson_underBothInclusions() {
            for (var inclusion : NullInclusion.values()) {
                var options = inclusion(inclusion);

                for (var value : List.of(OptionsFixtures.emptyProfile(), OptionsFixtures.fullProfile())) {
                    assertEquals(utf8(jackson(NamingStrategy.AS_DECLARED, options).writeAsBytes(value).unwrap()),
                                 utf8(OptionsFixtures_UserProfileJson.codec(options).encode(value)),
                                 inclusion.name());
                }
            }
        }
    }

    // ---- naming ------------------------------------------------------------------------------------------------
    @Nested
    class Naming {
        static final List<String> NAMES = List.of("userId",
                                                  "URLValue",
                                                  "userID",
                                                  "HTTPServerURL",
                                                  "a",
                                                  "A",
                                                  "aB",
                                                  "ABc",
                                                  "_x",
                                                  "_X",
                                                  "x_",
                                                  "a_b",
                                                  "a_B",
                                                  "x1Y2",
                                                  "already_snake",
                                                  "Pascal",
                                                  "html5Parser",
                                                  "über",
                                                  "ÜberName",
                                                  "",
                                                  "__a",
                                                  "a__B",
                                                  "aBC");

        @Test
        void apply_translatesExactlyAsJacksonsStrategyOfTheSameMeaning() {
            var jackson = Map.<NamingStrategy, PropertyNamingStrategy> of(NamingStrategy.SNAKE_CASE,
                                                                          PropertyNamingStrategies.SNAKE_CASE,
                                                                          NamingStrategy.KEBAB_CASE,
                                                                          PropertyNamingStrategies.KEBAB_CASE,
                                                                          NamingStrategy.PASCAL_CASE,
                                                                          PropertyNamingStrategies.UPPER_CAMEL_CASE);

            jackson.forEach((ours, theirs) -> NAMES.forEach(name -> assertEquals(theirs.nameForField(null, null, name),
                                                                                 ours.apply(name),
                                                                                 ours + " of '" + name + "'")));
            NAMES.forEach(name -> assertEquals(name, NamingStrategy.AS_DECLARED.apply(name)));
        }
    }

    // ---- decode ------------------------------------------------------------------------------------------------
    @Json
    record Item(String name, long count, BigDecimal price, Map<String, Integer> tags, Option<String> note) {}

    @Json
    record Tree(List<Tree> children) {}

    static final String BASE = "{\"name\":\"n\",\"count\":1,\"price\":1.5,\"tags\":{\"a\":1}";

    static String item(String extraMembers) {
        return BASE + extraMembers + "}";
    }

    /// Decodes `json` both ways under `options`: they must agree, and the outcome must be `accept`, or a failure of
    /// type `refusal` from the generated codec.
    static void agree(String description,
                      JsonOptions options,
                      String json,
                      boolean accept,
                      Class<? extends JsonError> refusal) {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        var ours = OptionsConformanceTest_ItemJson.codec(options).decode(bytes);
        var theirs = jackson(NamingStrategy.AS_DECLARED, options).readBytes(bytes, Item.class);

        assertEquals(theirs.isSuccess(), ours.isSuccess(), description + ": ours " + ours + ", Jackson " + theirs);
        assertEquals(accept, ours.isSuccess(), description + ": " + ours);
        if (accept) {
            assertEquals(theirs.unwrap(), ours.unwrap(), description);
        } else {
            assertInstanceOf(refusal, failureOf(ours), description);
        }
    }

    static void agreeTree(String description, JsonOptions options, String json, boolean accept) {
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        var ours = OptionsConformanceTest_TreeJson.codec(options).decode(bytes);
        var theirs = jackson(NamingStrategy.AS_DECLARED, options).readBytes(bytes, Tree.class);

        assertEquals(theirs.isSuccess(), ours.isSuccess(), description + ": ours " + ours + ", Jackson " + theirs);
        assertEquals(accept, ours.isSuccess(), description);
    }

    @Nested
    class UnknownKeys {
        @Test
        void unknownMembers_areSkippedByDefault_andRefusedWhenConfigured() {
            for (var failOnUnknown : new boolean[]{false, true}) {
                var options = options(NullInclusion.INCLUDE, failOnUnknown, false, 1000, MAX, MAX, MAX);

                agree("no unknown member", options, item(""), true, null);
                agree("unknown scalar", options, item(",\"extra\":1"), !failOnUnknown, JsonError.UnknownField.class);
                agree("unknown object",
                      options,
                      item(",\"extra\":{\"deep\":[1,{\"x\":2}]}"),
                      !failOnUnknown,
                      JsonError.UnknownField.class);
                agree("unknown first",
                      options,
                      "{\"extra\":null," + BASE.substring(1) + "}",
                      !failOnUnknown,
                      JsonError.UnknownField.class);
            }
        }
    }

    @Nested
    class DuplicateKeys {
        @Test
        void repeatedKeys_keepTheLastValueByDefault_andAreRefusedWhenConfigured() {
            for (var failOnDuplicate : new boolean[]{false, true}) {
                var options = options(NullInclusion.INCLUDE, false, failOnDuplicate, 1000, MAX, MAX, MAX);

                agree("declared field", options, item(",\"count\":2"), !failOnDuplicate, JsonError.DuplicateKey.class);
                agree("optional field",
                      options,
                      item(",\"note\":\"x\",\"note\":null"),
                      !failOnDuplicate,
                      JsonError.DuplicateKey.class);
                agree("unknown field",
                      options,
                      "{\"z\":1," + BASE.substring(1) + ",\"z\":2}",
                      !failOnDuplicate,
                      JsonError.DuplicateKey.class);
                agree("map key",
                      options,
                      "{\"name\":\"n\",\"count\":1,\"price\":1.5,\"tags\":{\"a\":1,\"b\":2,\"a\":3}}",
                      !failOnDuplicate,
                      JsonError.DuplicateKey.class);
                agree("key inside a skipped value",
                      options,
                      item(",\"z\":{\"q\":1,\"r\":[],\"q\":2}"),
                      !failOnDuplicate,
                      JsonError.DuplicateKey.class);
                agree("sibling objects sharing keys",
                      options,
                      item(",\"z\":[{\"q\":1},{\"q\":2}],\"y\":{\"q\":{\"q\":1}}"),
                      true,
                      null);
            }
        }
    }

    @Nested
    class Limits {
        @Test
        void depth_atTheLimitMinusOne_atIt_andPlusOne() {
            var options = options(NullInclusion.INCLUDE, false, false, 10, MAX, MAX, MAX);

            for (var depth = 9; depth <= 11; depth++) {
                var arrays = depth - 1;

                agree("depth " + depth,
                      options,
                      item(",\"z\":" + "[".repeat(arrays) + "]".repeat(arrays)),
                      depth <= 10,
                      JsonError.NestingTooDeep.class);
            }

            agreeTree("tree depth 10",
                      options,
                      "{\"children\":[".repeat(5) + "]}".repeat(5),
                      true);
            agreeTree("tree depth 12",
                      options,
                      "{\"children\":[".repeat(6) + "]}".repeat(6),
                      false);
        }

        @Test
        void stringLength_atTheLimitMinusOne_atIt_andPlusOne_forValuesKeysAndNames() {
            var options = options(NullInclusion.INCLUDE, false, false, 1000, 64, MAX, MAX);

            for (var length = 63; length <= 65; length++) {
                var fits = length <= 64;

                agree("value " + length,
                      options,
                      "{\"name\":\"" + "x".repeat(length) + "\",\"count\":1,\"price\":1,\"tags\":{}}",
                      fits,
                      JsonError.StringTooLong.class);
                agree("non-ASCII value " + length,
                      options,
                      "{\"name\":\"" + "é".repeat(length) + "\",\"count\":1,\"price\":1,\"tags\":{}}",
                      fits,
                      JsonError.StringTooLong.class);
                agree("map key " + length,
                      options,
                      "{\"name\":\"n\",\"count\":1,\"price\":1,\"tags\":{\"" + "k".repeat(length) + "\":1}}",
                      fits,
                      JsonError.StringTooLong.class);
                agree("unknown key " + length,
                      options,
                      item(",\"" + "u".repeat(length) + "\":1"),
                      fits,
                      JsonError.StringTooLong.class);
                agree("key in a skipped value " + length,
                      options,
                      item(",\"z\":{\"" + "s".repeat(length) + "\":1}"),
                      fits,
                      JsonError.StringTooLong.class);
            }
        }

        // A skipped string value is not measured; Jackson measures one only when its text buffer grows (at 200
        // characters, then at each growth), so at the limit plus one both accept, and a long one splits them. This
        // pins the split: if Jackson starts measuring every skipped string, the generated codecs should follow.
        @Test
        void skippedStringValue_agreesJustOverTheLimit_andSplitsFarOverIt() {
            var options = options(NullInclusion.INCLUDE, false, false, 1000, 64, MAX, MAX);

            agree("skipped string value 65", options, item(",\"z\":\"" + "v".repeat(65) + "\""), true, null);
            var far = item(",\"z\":\"" + "v".repeat(500) + "\"").getBytes(StandardCharsets.UTF_8);

            assertTrue(OptionsConformanceTest_ItemJson.codec(options).decode(far).isSuccess());
            assertFalse(jackson(NamingStrategy.AS_DECLARED, options).readBytes(far, Item.class).isSuccess());
        }

        @Test
        void numberLength_atTheLimitMinusOne_atIt_andPlusOne() {
            var options = options(NullInclusion.INCLUDE, false, false, 1000, 64, 10, MAX);

            for (var digits = 9; digits <= 11; digits++) {
                var fits = digits <= 10;
                var number = "7".repeat(digits);

                agree("integer " + digits,
                      options,
                      "{\"name\":\"n\",\"count\":" + number + ",\"price\":1,\"tags\":{}}",
                      fits,
                      JsonError.NumberTooLong.class);
                agree("negative integer " + digits,
                      options,
                      "{\"name\":\"n\",\"count\":-" + number + ",\"price\":1,\"tags\":{}}",
                      fits,
                      JsonError.NumberTooLong.class);
                agree("decimal " + digits,
                      options,
                      "{\"name\":\"n\",\"count\":1,\"price\":1." + "5".repeat(digits - 1) + ",\"tags\":{}}",
                      fits,
                      JsonError.NumberTooLong.class);
                agree("exponent " + digits,
                      options,
                      "{\"name\":\"n\",\"count\":1,\"price\":1." + "5".repeat(digits - 3) + "e-12,\"tags\":{}}",
                      fits,
                      JsonError.NumberTooLong.class);
                agree("skipped number " + digits,
                      options,
                      item(",\"z\":[" + number + "]"),
                      fits,
                      JsonError.NumberTooLong.class);
            }
        }

        // Jackson measures an integer's whole text against the string limit too, and a skipped number's, but not the
        // text of a floating-point number it reads; so do the generated codecs.
        @Test
        void floatingPointText_atTheStringLimitMinusOne_atIt_andPlusOne() {
            var options = options(NullInclusion.INCLUDE, false, false, 1000, 64, 100, MAX);

            for (var length = 63; length <= 65; length++) {
                var number = "-1." + "1".repeat(length - 5) + "e1";

                assertEquals(length, number.length());
                agree("read decimal text " + length,
                      options,
                      "{\"name\":\"n\",\"count\":1,\"price\":" + number + ",\"tags\":{}}",
                      true,
                      null);
                agree("skipped decimal text " + length,
                      options,
                      item(",\"z\":" + number),
                      length <= 64,
                      JsonError.StringTooLong.class);
                agree("skipped integer text " + length,
                      options,
                      item(",\"z\":-" + "1".repeat(length - 1)),
                      length <= 64,
                      JsonError.StringTooLong.class);
                agree("read integer text " + length,
                      options,
                      "{\"name\":\"n\",\"count\":1,\"price\":-" + "1".repeat(length - 1) + ",\"tags\":{}}",
                      length <= 64,
                      JsonError.StringTooLong.class);
            }
        }

        @Test
        void documentSize_atTheLimitMinusOne_atIt_andPlusOne() {
            var document = item("");
            var limit = document.length() + 1;
            var options = options(NullInclusion.INCLUDE, false, false, 1000, MAX, MAX, limit);

            for (var size = limit - 1; size <= limit + 1; size++) {
                agree("document " + size,
                      options,
                      document + " ".repeat(size - document.length()),
                      size <= limit,
                      JsonError.DocumentTooLarge.class);
            }
        }

        @Test
        void defaults_acceptWhatJacksonsOwnDefaultsWouldRefuse() {
            var longNumber = "1".repeat(1001);
            var json = "{\"name\":\"n\",\"count\":1,\"price\":" + longNumber + ",\"tags\":{}}";

            assertTrue(OptionsConformanceTest_ItemJson.CODEC.decode(json).isSuccess());
            assertTrue(jackson(NamingStrategy.AS_DECLARED, JsonOptions.DEFAULTS).readString(json, Item.class)
                              .isSuccess());
            assertFalse(Baseline.MAPPER.readString(json, Item.class).isSuccess());
        }
    }
}
