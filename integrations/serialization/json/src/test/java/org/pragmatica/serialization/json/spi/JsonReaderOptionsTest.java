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
import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.json.JsonError;
import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.JsonOptions.NullInclusion;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// The reader under options: each limit at its boundary, with the failure and its absolute position, and the
/// unknown and repeated key paths, including key recovery across escapes and whitespace.
class JsonReaderOptionsTest {
    static final int MAX = Integer.MAX_VALUE;

    static JsonOptions options(boolean failOnUnknown,
                               boolean failOnDuplicate,
                               int depth,
                               int string,
                               int number,
                               int document) {
        return JsonOptions.jsonOptions(NullInclusion.INCLUDE,
                                       failOnUnknown,
                                       failOnDuplicate,
                                       depth,
                                       string,
                                       number,
                                       document).unwrap();
    }

    static JsonOptions limits(int depth, int string, int number) {
        return options(false, false, depth, string, number, MAX);
    }

    static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static <T> Result<T> read(String json, JsonOptions options, Fn1<T, JsonReader> body) {
        var bytes = utf8(json);
        var in = JsonReader.jsonReader().reset(bytes, 0, bytes.length, options);
        var value = body.apply(in);

        in.finish();

        return in.result(value);
    }

    static Result<Unit> validate(String json, JsonOptions options) {
        return read(json, options, JsonReaderOptionsTest::skip);
    }

    static Unit skip(JsonReader in) {
        in.skipValue();

        return Unit.unit();
    }

    static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause,
                           value -> {
                               throw new AssertionError("expected a failure, got " + value);
                           });
    }

    @Nested
    class DocumentSize {
        @Test
        void reset_documentAtTheLimit_isRead_andOneByteMore_isRefusedBeforeReading() {
            var json = "[1]  ";

            assertTrue(validate(json, options(false, false, 1000, MAX, MAX, 5)).isSuccess());
            assertEquals(new JsonError.DocumentTooLarge(4,
                                                        4,
                                                        "Document larger than 4 bytes (offset 4 is past the limit)"),
                         failureOf(validate(json, options(false, false, 1000, MAX, MAX, 4))));
        }
    }

    @Nested
    class Depth {
        @Test
        void beginArray_configuredDepth_acceptsTheLimit_andRefusesOneMore() {
            var options = limits(3, MAX, MAX);

            assertTrue(read("[[[]]]", options, JsonReaderOptionsTest::nestedArrays).isSuccess());
            var cause = assertInstanceOf(JsonError.NestingTooDeep.class,
                                         failureOf(read("[[[[]]]]", options, JsonReaderOptionsTest::nestedArrays)));

            assertEquals(3, cause.limit());
            assertEquals(3, cause.position());
        }

        @Test
        void skipValue_configuredDepth_acceptsTheLimit_andRefusesOneMore() {
            var options = limits(3, MAX, MAX);

            assertTrue(validate("{\"a\":[[1]]}", options).isSuccess());
            assertEquals(3,
                         assertInstanceOf(JsonError.NestingTooDeep.class,
                                          failureOf(validate("{\"a\":[[[1]]]}", options))).limit());
        }

        // A reused reader whose earlier input allowed only shallow skipping must still skip deeper levels later.
        @Test
        void skipValue_reusedReader_growsItsDeepLevelTable_withTheLimit() {
            var in = JsonReader.jsonReader();
            var shallow = utf8("[".repeat(70) + "]".repeat(70));
            var deep = utf8("[".repeat(200) + "{\"a\":1}" + "]".repeat(200));

            in.reset(shallow, 0, shallow.length, limits(80, MAX, MAX));
            in.skipValue();
            assertTrue(in.result(Unit.unit()).isSuccess());
            in.reset(deep, 0, deep.length, limits(300, MAX, MAX));
            in.skipValue();
            in.finish();
            assertTrue(in.result(Unit.unit()).isSuccess());
        }
    }

    static int nestedArrays(JsonReader in) {
        var levels = 0;

        while (in.beginArray()) {
            levels++;
            if (!in.firstElement()) {
                break;
            }
        }

        for (int i = 1; i < levels; i++) {
            in.nextElement();
        }

        return levels;
    }

    @Nested
    class StringLength {
        static final JsonOptions LIMIT_64 = limits(1000, 64, MAX);

        @Test
        void readString_countsUtf16Characters_atTheLimit() {
            for (var unit : new String[]{"x", "\u00e9", "\\u0041"}) {
                assertTrue(read("\"" + unit.repeat(64) + "\"", LIMIT_64, JsonReader::readString).isSuccess(),
                           unit);
                assertEquals(new JsonError.StringTooLong(64, 0, "String longer than 64 characters at offset 0"),
                             failureOf(read("\"" + unit.repeat(65) + "\"", LIMIT_64, JsonReader::readString)),
                             unit);
            }
            // A supplementary character is two UTF-16 characters, as Jackson counts it.
            assertTrue(read("\"" + "\uD834\uDD1E".repeat(32) + "\"", LIMIT_64, JsonReader::readString).isSuccess());
            assertTrue(read("\"" + "\uD834\uDD1E".repeat(32) + "a\"", LIMIT_64, JsonReader::readString).isFailure());
        }

        @Test
        void readKey_isHeldToTheLimit() {
            var json = " \"" + "k".repeat(65) + "\":1";

            assertEquals(1,
                         assertInstanceOf(JsonError.StringTooLong.class,
                                          failureOf(read(json, LIMIT_64, JsonReader::readKey))).position());
        }

        @Test
        void skipValue_holdsKeysToTheLimit_butNotStringValues() {
            assertTrue(validate("{\"" + "k".repeat(64) + "\":\"" + "v".repeat(500) + "\"}",
                                LIMIT_64).isSuccess());
            assertEquals(1,
                         assertInstanceOf(JsonError.StringTooLong.class,
                                          failureOf(validate("{\"" + "\u00e9".repeat(65) + "\":1}", LIMIT_64))).position());
        }

        @Test
        void firstField_unknownKey_isHeldToTheLimit() {
            var names = FieldNames.fieldNames("id");

            assertEquals(1,
                         assertInstanceOf(JsonError.StringTooLong.class,
                                          failureOf(read("{\"" + "u".repeat(65) + "\":1}",
                                                         LIMIT_64,
                                                         in -> firstFieldOf(in, names)))).position());
            assertEquals(JsonReader.UNKNOWN_FIELD,
                         (int) read("{\"" + "u".repeat(64) + "\":1}", LIMIT_64, in -> unknownThenSkip(in, names)).unwrap());
        }
    }

    static int unknownThenSkip(JsonReader in, FieldNames names) {
        in.beginObject();
        var field = in.firstField(names);

        in.skipUnknown(null);
        in.nextField(names, 0);

        return field;
    }

    @Nested
    class NumberLength {
        static final JsonOptions FIVE_DIGITS = limits(1000, 64, 5);

        @Test
        void readLong_countsDigits_notTheSign() {
            assertEquals(12345L, read("12345", FIVE_DIGITS, JsonReader::readLong).unwrap());
            assertEquals(-12345L, read("-12345", FIVE_DIGITS, JsonReader::readLong).unwrap());
            assertEquals(new JsonError.NumberTooLong(5, 1, "Number longer than 5 digits at offset 1"),
                         failureOf(read(" 123456", FIVE_DIGITS, JsonReader::readLong)));
        }

        @Test
        void readDecimal_countsIntegerFractionAndExponentDigits() {
            assertTrue(read("1.2345", FIVE_DIGITS, JsonReader::readDecimal).isSuccess());
            assertTrue(read("1e-1234", FIVE_DIGITS, JsonReader::readDecimal).isSuccess());
            assertInstanceOf(JsonError.NumberTooLong.class,
                             failureOf(read("1.23456", FIVE_DIGITS, JsonReader::readDecimal)));
            assertInstanceOf(JsonError.NumberTooLong.class,
                             failureOf(read("12e1234", FIVE_DIGITS, JsonReader::readDecimal)));
            assertInstanceOf(JsonError.NumberTooLong.class,
                             failureOf(read("123456.5", FIVE_DIGITS, JsonReader::readDouble)));
        }

        @Test
        void skipValue_holdsSkippedNumbersToTheLimit() {
            assertTrue(validate("[12345,-1.234e0]", FIVE_DIGITS).isSuccess());
            assertEquals(7,
                         assertInstanceOf(JsonError.NumberTooLong.class,
                                          failureOf(validate("[12345,123456]", FIVE_DIGITS))).position());
        }

        // Jackson measures an integer's whole text against the string limit as well, and a skipped number's; never the
        // text of a floating-point number it reads.
        @Test
        void numberText_isAlsoHeldToTheStringLimit_exceptForAReadFloatingPointNumber() {
            var options = limits(1000, 64, 100);
            var fits = "-1." + "1".repeat(59) + "e1";
            var tooLong = "-1." + "1".repeat(60) + "e1";

            assertEquals(64, fits.length());
            assertTrue(validate("[" + fits + "]", options).isSuccess());
            assertEquals(1,
                         assertInstanceOf(JsonError.StringTooLong.class,
                                          failureOf(validate("[" + tooLong + "]", options))).position());
            assertTrue(read(tooLong, options, JsonReader::readDecimal).isSuccess());
            assertInstanceOf(JsonError.StringTooLong.class,
                             failureOf(read("-" + "7".repeat(64), options, JsonReader::readDecimal)));
            assertTrue(read("-" + "7".repeat(63), options, JsonReader::readDecimal).isSuccess());
            assertInstanceOf(JsonError.StringTooLong.class, failureOf(validate("[-" + "7".repeat(64) + "]", options)));
            assertTrue(validate("[-" + "7".repeat(63) + "]", options).isSuccess());
        }
    }

    @Nested
    class UnknownAndRepeatedKeys {
        static final FieldNames NAMES = FieldNames.fieldNames("a", "b");

        @Test
        void skipUnknown_failOnUnknown_namesTheKey_atItsOpeningQuote_acrossEscapesAndWhitespace() {
            var json = "{\"a\":1, \"q\\\"\\\\\" :2}";
            var cause = assertInstanceOf(JsonError.UnknownField.class,
                                         failureOf(read(json,
                                                        options(true, false, 1000, MAX, MAX, MAX),
                                                        JsonReaderOptionsTest::readMembers)));

            assertEquals("q\"\\", cause.field());
            assertEquals(json.indexOf("\"q"), cause.position());
        }

        @Test
        void skipUnknown_byDefault_skipsTheValue() {
            assertEquals(2,
                         (int) read("{\"z\":{\"x\":[1]},\"a\":1,\"b\":2}",
                                    JsonOptions.DEFAULTS,
                                    JsonReaderOptionsTest::readMembers).unwrap());
        }

        @Test
        void skipUnknown_repeatedUnknownKey_isADuplicate_whenDuplicatesAreRefused() {
            var json = "{\"z\":1,\"a\":1,\"z\":2}";
            var cause = assertInstanceOf(JsonError.DuplicateKey.class,
                                         failureOf(read(json,
                                                        options(false, true, 1000, MAX, MAX, MAX),
                                                        JsonReaderOptionsTest::readMembers)));

            assertEquals("z", cause.key());
            assertEquals(json.lastIndexOf("\"z\""), cause.position());
            assertTrue(read(json, JsonOptions.DEFAULTS, JsonReaderOptionsTest::readMembers).isSuccess());
        }

        @Test
        void repeatedField_failsOnlyWhenDuplicatesAreRefused() {
            var json = "{\"a\":1,\"b\":2,\"a\" : 3}";
            var cause = assertInstanceOf(JsonError.DuplicateKey.class,
                                         failureOf(read(json,
                                                        options(false, true, 1000, MAX, MAX, MAX),
                                                        JsonReaderOptionsTest::readMembers)));

            assertEquals("a", cause.key());
            assertEquals(json.lastIndexOf("\"a\""), cause.position());
            assertEquals(2, (int) read(json, JsonOptions.DEFAULTS, JsonReaderOptionsTest::readMembers).unwrap());
        }

        @Test
        void skipValue_repeatedKeyInOneObject_isADuplicate_butSiblingObjectsMayShareKeys() {
            var refuse = options(false, true, 1000, MAX, MAX, MAX);
            var nested = "{\"x\":{\"b\":1,\"\\u0062\":2}}";

            assertTrue(validate("[{\"a\":1},{\"a\":2}]", refuse).isSuccess());
            assertTrue(validate("{\"x\":{\"a\":1},\"y\":{\"a\":1,\"x\":[{\"x\":1}]}}", refuse).isSuccess());
            assertEquals(nested.indexOf("\"\\u0062"),
                         assertInstanceOf(JsonError.DuplicateKey.class, failureOf(validate(nested, refuse))).position());
            assertTrue(validate(nested, JsonOptions.DEFAULTS).isSuccess());
        }
    }

    // The generated record loop in miniature: known fields read, a repeat reported, unknown members passed to
    // skipUnknown. Returns how many distinct known fields were read.
    static int readMembers(JsonReader in) {
        var seen = 0L;
        var expected = 0;
        Set<String> unknown = null;

        in.beginObject();
        for (var field = in.firstField(UnknownAndRepeatedKeys.NAMES); field != JsonReader.END_OF_OBJECT; field = in.nextField(UnknownAndRepeatedKeys.NAMES,
                                                                                                                              expected)) {
            if (field >= 0) {
                if ((seen & 1L << field) != 0) {
                    in.repeatedField(UnknownAndRepeatedKeys.NAMES, field);
                }

                seen |= 1L << field;
                expected = field + 1;
                in.readInt();
            } else {
                unknown = in.skipUnknown(unknown);
            }
        }

        return Long.bitCount(seen);
    }

    static int firstFieldOf(JsonReader in, FieldNames names) {
        in.beginObject();

        return in.firstField(names);
    }
}
