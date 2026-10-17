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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonError;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Generated codecs on hostile or unusual input: everything fails as a typed result, never as an exception, and
/// agrees with the production Jackson mapper where the two overlap.
class StrictDecodingTest {
    static final JsonMapper PROD = JsonMapper.defaultJsonMapper();

    @Json
    record Measured(double v) {}

    @Json
    record Priced(BigDecimal v) {}

    @Json
    record Named(String name) {}

    enum Color {
        RED,
        A,
        UNKNOWN
    }

    enum Shade {
        DARK,
        LIGHT
    }

    @Json
    record Paint(Color color, Shade shade) {}

    @Json
    record Node(List<Node> children) {}

    @Json
    record Ids(UUID id) {}

    record Inner(String x) {}

    record Email(String value) {
        public static Result<Email> email(String value) {
            return value.contains("@")
                   ? Result.success(new Email(value))
                   : Causes.cause("bad email").result();
        }
    }

    @Json
    record AllRequired(String text, long count, double ratio, Integer boxed, Inner inner, List<String> tags, Shade shade,
                       Email email) {}

    @Json
    record AllOptional(Option<String> text, Option<Long> count, Option<Double> ratio, Option<Integer> boxed,
                       Option<Inner> inner, Option<List<String>> tags, Option<Shade> shade, Option<Email> email) {}

    static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    // ---- nesting -------------------------------------------------------------------------------
    static String nodes(int depth) {
        return "{\"children\":[".repeat(depth) + "]}".repeat(depth);
    }

    @Test
    void decode_recursiveRecordFarBeyondLimit_failsAsNestingTooDeep_withoutStackOverflow() {
        var result = StrictDecodingTest_NodeJson.CODEC.decode(utf8(nodes(20_000)));

        assertInstanceOf(JsonError.NestingTooDeep.class, failureOf(result));
    }

    @Test
    void decode_recursiveRecord400Deep_succeeds() {
        var node = StrictDecodingTest_NodeJson.CODEC.decode(utf8(nodes(400)))
                                                                                     .unwrap();
        var depth = 0;

        for (var current = node; !current.children().isEmpty(); current = current.children().getFirst()) {
            depth++;
        }

        assertEquals(399, depth);
    }

    // ---- non-finite doubles --------------------------------------------------------------------
    @Test
    void nonFiniteDouble_encodesLikeJackson_andDecodesBack() {
        for (var value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var ours = StrictDecodingTest_MeasuredJson.CODEC.encode(new Measured(value));
            var jackson = PROD.writeAsBytes(new Measured(value))
                              .unwrap();

            assertEquals(new String(jackson, StandardCharsets.UTF_8), new String(ours, StandardCharsets.UTF_8));

            var back = StrictDecodingTest_MeasuredJson.CODEC.decode(jackson)
                                                                                                 .unwrap();

            assertEquals(Double.doubleToLongBits(value), Double.doubleToLongBits(back.v()));
        }
    }

    @Test
    void nonFiniteDouble_bareTokensAndOtherStrings_areRejected() {
        for (var bad : new String[]{"NaN", "Infinity", "-Infinity", "\"nan\"", "\"1.0\""}) {
            assertTrue(StrictDecodingTest_MeasuredJson.CODEC.decode(utf8("{\"v\":" + bad + "}"))
                                                                                                  .isFailure(),
                       bad);
        }
    }

    // ---- decimals ------------------------------------------------------------------------------
    @Test
    void decimal_hugeExponent_isExact_likeJackson() {
        var json = utf8("{\"v\":1e1234567}");
        var ours = StrictDecodingTest_PricedJson.CODEC.decode(json)
                                                                                         .unwrap();

        assertEquals(new BigDecimal("1e1234567"), ours.v());
        assertEquals(PROD.readBytes(json, new TypeToken<Priced>() {})
                         .unwrap(),
                     ours);
    }

    @Test
    void decimal_exponentBeyondBigDecimal_failsAsOutOfRange() {
        var result = StrictDecodingTest_PricedJson.CODEC.decode(utf8("{\"v\":1e2147483649}"));

        assertInstanceOf(JsonError.OutOfRange.class, failureOf(result));
    }

    // ---- unknown values ------------------------------------------------------------------------
    @Test
    void unknownField_withBrokenGrammar_isRejected() {
        for (var bad : new String[]{"{]", "[tru , , : xyz]", "{\"a\" 1}", "[1,]", "nul", "01", "\"\\x\"", "{\"a\":1,}", "[{\"a\":1]}", "{\"a\":[1}]"}) {
            var json = utf8("{\"name\":\"x\",\"u\":" + bad + "}");

            assertTrue(StrictDecodingTest_NamedJson.CODEC.decode(json)
                                                                                          .isFailure(),
                       bad);
        }

        assertEquals(new Named("x"),
                     StrictDecodingTest_NamedJson.CODEC.decode(utf8("{\"u\":{\"a\":[1,{\"b\":null}]},\"name\":\"x\"}"))
                                                                                       .unwrap());
    }

    // ---- escaped names -------------------------------------------------------------------------
    @Test
    void escapedEnumConstants_decodeToTheirConstants_likeJackson() {
        var json = utf8("{\"color\":\"\\u0041\",\"shade\":\"\\u004CIGHT\"}");
        var ours = StrictDecodingTest_PaintJson.CODEC.decode(json)
                                                                                       .unwrap();

        assertEquals(new Paint(Color.A, Shade.LIGHT), ours);
        assertEquals(PROD.readBytes(json, new TypeToken<Paint>() {})
                         .unwrap(),
                     ours);
    }

    @Test
    void escapedFieldName_bindsToItsField_likeJackson() {
        var json = utf8("{\"\\u006eame\":\"x\"}");
        var ours = StrictDecodingTest_NamedJson.CODEC.decode(json)
                                                                                       .unwrap();

        assertEquals(new Named("x"), ours);
        assertEquals(PROD.readBytes(json, new TypeToken<Named>() {})
                         .unwrap(),
                     ours);
    }

    // ---- UUID and UTF-8 ------------------------------------------------------------------------
    @Test
    void uuid_nonCanonical_isRejected_likeJackson() {
        for (var text : new String[]{"1-1-1-1-1", "123e4567-e89b-12d3-a456-42661417400", "123e4567e89b12d3a456426614174000"}) {
            var json = utf8("{\"id\":\"" + text + "\"}");

            assertTrue(StrictDecodingTest_IdsJson.CODEC.decode(json)
                                                                                    .isFailure(),
                       text);
            assertTrue(PROD.readBytes(json, new TypeToken<Ids>() {})
                           .isFailure(),
                       text);
        }

        var uuid = UUID.randomUUID();

        assertEquals(new Ids(uuid),
                     StrictDecodingTest_IdsJson.CODEC.decode(utf8("{\"id\":\"" + uuid + "\"}"))
                                                                                   .unwrap());
    }

    @Test
    void invalidUtf8InString_isRejectedAsMalformed_likeJackson() {
        var json = new byte[]{'{', '"', 'n', 'a', 'm', 'e', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'};
        var ours = StrictDecodingTest_NamedJson.CODEC.decode(json);

        assertInstanceOf(JsonError.Malformed.class, failureOf(ours));
        assertTrue(PROD.readBytes(json, new TypeToken<Named>() {})
                       .isFailure());
    }

    // ---- doubles -------------------------------------------------------------------------------
    // Differential: tricky decimal strings (Eisel-Lemire, subnormals, more than 19 digits) against the JDK.
    @Test
    void decodedDouble_matchesParseDouble_forTrickyDecimals() {
        var random = new Random(12345);
        var mismatches = new ArrayList<String>();

        for (int i = 0; i < 2_000_000 && mismatches.size() < 10; i++) {
            var text = trickyDecimal(random).replace("E", "e");

            if (text.contains("NaN") || text.contains("Infinity")) {
                continue;
            }

            var decoded = StrictDecodingTest_MeasuredJson.CODEC.decode(utf8("{\"v\":" + text + "}"));

            assertTrue(decoded.isSuccess(), text);

            var expected = Double.parseDouble(text);
            var actual = decoded.unwrap()
                                .v();

            if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
                mismatches.add(text + " jdk=" + expected + " ours=" + actual);
            }
        }

        assertTrue(mismatches.isEmpty(), mismatches.toString());
    }

    static String trickyDecimal(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> Double.toString(Double.longBitsToDouble(random.nextLong()));
            case 1 -> (random.nextBoolean()
                       ? "-"
                       : "") + Math.abs(random.nextLong() % 1_000_000_000_000_000_000L) + "e" + (random.nextInt(700) - 350);
            case 2 -> random.nextInt(10) + "." + String.format("%019d", Math.abs(random.nextLong() % 10_000_000_000_000_000L)) + "e" + (random.nextInt(640) - 330);
            case 3 -> new BigDecimal(Double.longBitsToDouble(random.nextLong() & 0x7FEFFFFFFFFFFFFFL)).round(new java.math.MathContext(1 + random.nextInt(25)))
                                                                                                    .toString();
            default -> (1 + random.nextInt(9)) + "e" + (random.nextInt(30) - 340);
        };
    }

    // ---- null ----------------------------------------------------------------------------------
    static final Map<String, String> REQUIRED_VALUES = Map.of("text", "\"t\"",
                                                              "count", "1",
                                                              "ratio", "1.5",
                                                              "boxed", "2",
                                                              "inner", "{\"x\":\"y\"}",
                                                              "tags", "[\"a\"]",
                                                              "shade", "\"DARK\"",
                                                              "email", "\"a@b.c\"");

    static final List<String> FIELDS = List.of("text", "count", "ratio", "boxed", "inner", "tags", "shade", "email");

    // Every field with its regular value, except `nulled`, which is null.
    static byte[] withNull(String nulled) {
        var sb = new StringBuilder("{");

        for (var field : FIELDS) {
            sb.append(sb.length() > 1
                      ? ","
                      : "")
              .append('"')
              .append(field)
              .append("\":")
              .append(field.equals(nulled)
                      ? "null"
                      : REQUIRED_VALUES.get(field));
        }

        return utf8(sb.append('}')
                      .toString());
    }

    @Test
    void nullForNonOptionalField_isRejected_namingTheField_forEveryKind() {
        assertTrue(StrictDecodingTest_AllRequiredJson.CODEC.decode(withNull(""))
                                                                                                    .isSuccess());

        for (var field : FIELDS) {
            var cause = failureOf(StrictDecodingTest_AllRequiredJson.CODEC.decode(withNull(field)));

            assertInstanceOf(JsonError.UnexpectedNull.class, cause, field);
            assertEquals(field, ((JsonError.UnexpectedNull) cause).field());
            assertTrue(cause.message().contains(field), cause.message());
        }
    }

    @Test
    void nullInsideNonOptionalValues_isRejected_namingTheInnermostField() {
        var nestedNull = utf8(new String(withNull(""), StandardCharsets.UTF_8).replace("{\"x\":\"y\"}", "{\"x\":null}"));
        var elementNull = utf8(new String(withNull(""), StandardCharsets.UTF_8).replace("[\"a\"]", "[\"a\",null]"));

        assertEquals("x",
                     ((JsonError.UnexpectedNull) failureOf(StrictDecodingTest_AllRequiredJson.CODEC.decode(nestedNull))).field());
        assertEquals("tags",
                     ((JsonError.UnexpectedNull) failureOf(StrictDecodingTest_AllRequiredJson.CODEC.decode(elementNull))).field());
    }

    static final AllOptional NOTHING = new AllOptional(Option.none(), Option.none(), Option.none(), Option.none(), Option.none(),
                                                       Option.none(), Option.none(), Option.none());

    @Test
    void optionalFields_decodeToNone_whenNull() {
        var json = new StringBuilder("{");

        for (var field : FIELDS) {
            json.append(json.length() > 1
                        ? ","
                        : "")
                .append('"')
                .append(field)
                .append("\":null");
        }

        var decoded = StrictDecodingTest_AllOptionalJson.CODEC.decode(utf8(json.append('}')
                                                                                                                      .toString()));

        assertEquals(NOTHING, decoded.unwrap());
    }

    @Test
    void optionalFields_decodeToNone_whenAbsent() {
        var decoded = StrictDecodingTest_AllOptionalJson.CODEC.decode(utf8("{}"));

        assertEquals(NOTHING, decoded.unwrap());
    }

    @Test
    void optionalFields_decodeToSome_whenPresent() {
        var decoded = StrictDecodingTest_AllOptionalJson.CODEC.decode(withNull(""))
                                                                                                    .unwrap();

        assertEquals(new AllOptional(Option.some("t"), Option.some(1L), Option.some(1.5), Option.some(2), Option.some(new Inner("y")),
                                     Option.some(List.of("a")), Option.some(Shade.DARK), Option.some(new Email("a@b.c"))),
                     decoded);
    }
}
