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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.JsonOptions.NullInclusion;
import org.pragmatica.serialization.json.spi.JsonReader;
import org.pragmatica.serialization.json.spi.JsonWriter;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// The options record, the naming strategy's spellings, and how a codec carries its options.
class JsonOptionsTest {
    static final JsonTypeCodec<String> STRING = JsonTypeCodec.jsonTypeCodec(JsonWriter::writeString,
                                                                            JsonReader::readString);

    static JsonOptions options(boolean failOnDuplicate, int maxStringLength, int maxDocumentSize) {
        return JsonOptions.jsonOptions(NullInclusion.INCLUDE,
                                       false,
                                       failOnDuplicate,
                                       JsonReader.MAX_DEPTH,
                                       maxStringLength,
                                       Integer.MAX_VALUE,
                                       maxDocumentSize).unwrap();
    }

    static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause,
                           value -> {
                               throw new AssertionError("expected a failure, got " + value);
                           });
    }

    @Nested
    class Defaults {
        // The defaults are the contract that every existing test passes unchanged: today's depth, no other limit.
        @Test
        void defaults_areTodaysBehaviour() {
            var defaults = JsonOptions.DEFAULTS;

            assertEquals(NullInclusion.INCLUDE, defaults.nullInclusion());
            assertEquals(false, defaults.failOnUnknown());
            assertEquals(false, defaults.failOnDuplicate());
            assertEquals(JsonReader.MAX_DEPTH, defaults.maxDepth());
            assertEquals(1000, defaults.maxDepth());
            assertEquals(Integer.MAX_VALUE, defaults.maxStringLength());
            assertEquals(Integer.MAX_VALUE, defaults.maxNumberLength());
            assertEquals(Integer.MAX_VALUE, defaults.maxDocumentSize());
        }

        @Test
        void jsonTypeCodec_withoutOptions_carriesTheDefaults() {
            assertSame(JsonOptions.DEFAULTS, STRING.options());
            assertSame(JsonOptions.DEFAULTS,
                       JsonTypeCodec.listOf(STRING).options());
        }

        @Test
        void withOptions_keepsTheFunctions_andBindsTheOptions() {
            var options = options(true, 64, 100);
            var bound = STRING.withOptions(options);

            assertSame(options, bound.options());
            assertSame(STRING.writer(), bound.writer());
            assertSame(STRING.reader(), bound.reader());
        }
    }

    @Nested
    class Validation {
        @Test
        void jsonOptions_acceptsTheSmallestLegalLimits() {
            var options = JsonOptions.jsonOptions(NullInclusion.EXCLUDE,
                                                  true,
                                                  true,
                                                  1,
                                                  JsonOptions.MIN_STRING_LENGTH,
                                                  1,
                                                  1);

            assertTrue(options.isSuccess());
        }

        @Test
        void jsonOptions_refusesEachIllegalLimit_namingIt() {
            assertTrue(failureOf(JsonOptions.jsonOptions(NullInclusion.INCLUDE, false, false, 0, 64, 1, 1)).message()
                                .contains("max_depth"));
            assertTrue(failureOf(JsonOptions.jsonOptions(NullInclusion.INCLUDE, false, false, 1, 63, 1, 1)).message()
                                .contains("max_string_length"));
            assertTrue(failureOf(JsonOptions.jsonOptions(NullInclusion.INCLUDE, false, false, 1, 64, 0, 1)).message()
                                .contains("max_number_length"));
            assertTrue(failureOf(JsonOptions.jsonOptions(NullInclusion.INCLUDE, false, false, 1, 64, 1, -5)).message()
                                .contains("max_document_size"));
        }
    }

    @Nested
    class Naming {
        @Test
        void namingStrategy_acceptsEverySpelling() {
            for (var raw : new String[]{"snake_case", "SNAKE_CASE", "snake-case", "SnakeCase"}) {
                assertEquals(NamingStrategy.SNAKE_CASE,
                             NamingStrategy.namingStrategy(raw).unwrap(),
                             raw);
            }

            assertEquals(NamingStrategy.KEBAB_CASE,
                         NamingStrategy.namingStrategy("kebab-case").unwrap());
            assertEquals(NamingStrategy.PASCAL_CASE,
                         NamingStrategy.namingStrategy("PascalCase").unwrap());
            assertEquals(NamingStrategy.AS_DECLARED,
                         NamingStrategy.namingStrategy("as_declared").unwrap());
        }

        @Test
        void namingStrategy_refusesAnUnknownName_listingTheKnownOnes() {
            var cause = failureOf(NamingStrategy.namingStrategy("camelCase"));

            assertTrue(cause.message().contains("camelCase"),
                       cause.message());
            assertTrue(cause.message().contains("snake_case"),
                       cause.message());
        }

        @Test
        void apply_translatesAComponentName() {
            assertEquals("userId", NamingStrategy.AS_DECLARED.apply("userId"));
            assertEquals("user_id", NamingStrategy.SNAKE_CASE.apply("userId"));
            assertEquals("user-id", NamingStrategy.KEBAB_CASE.apply("userId"));
            assertEquals("UserId", NamingStrategy.PASCAL_CASE.apply("userId"));
        }
    }

    // Combinator codecs apply their own options to everything inside them.
    @Nested
    class CombinatorOptions {
        @Test
        void mapOf_repeatedKey_keepsTheLastValue_byDefault() {
            var decoded = JsonTypeCodec.mapOf(STRING).decode("{\"a\":\"1\",\"b\":\"2\",\"a\":\"3\"}").unwrap();

            assertEquals(Map.of("a", "3", "b", "2"), decoded);
        }

        @Test
        void mapOf_repeatedKey_failsAsDuplicateKey_atTheRepeatedKey_whenDuplicatesAreRefused() {
            var json = "{\"a\":\"1\",\"b\":\"2\", \"a\":\"3\"}";
            var cause = failureOf(JsonTypeCodec.mapOf(STRING)
                                               .withOptions(options(true, Integer.MAX_VALUE, Integer.MAX_VALUE))
                                               .decode(json));
            var duplicate = assertInstanceOf(JsonError.DuplicateKey.class, cause);

            assertEquals("a", duplicate.key());
            assertEquals(json.lastIndexOf("\"a\""), duplicate.position());
        }

        @Test
        void listOf_appliesItsOwnStringLimit_toItsElements() {
            var codec = JsonTypeCodec.listOf(STRING).withOptions(options(false, 64, Integer.MAX_VALUE));
            var atLimit = "[\"" + "x".repeat(64) + "\"]";
            var overLimit = "[\"" + "x".repeat(64) + "\",\"" + "y".repeat(65) + "\"]";
            var cause = failureOf(codec.decode(overLimit));

            assertTrue(codec.decode(atLimit).isSuccess());
            assertEquals(new JsonError.StringTooLong(64, overLimit.indexOf("\"y"), cause.message()),
                         cause);
        }

        @Test
        void decode_region_reportsTheDocumentLimit_atAnAbsolutePosition() {
            var buffer = "xxx\"abcde\"yy".getBytes(StandardCharsets.UTF_8);
            var codec = STRING.withOptions(options(false, 64, 6));
            var cause = assertInstanceOf(JsonError.DocumentTooLarge.class, failureOf(codec.decode(buffer, 3, 7)));

            assertEquals(6, cause.limit());
            assertEquals(3 + 6, cause.position());
            assertEquals("abcde",
                         STRING.withOptions(options(false, 64, 7)).decode(buffer, 3, 7).unwrap());
        }
    }
}
