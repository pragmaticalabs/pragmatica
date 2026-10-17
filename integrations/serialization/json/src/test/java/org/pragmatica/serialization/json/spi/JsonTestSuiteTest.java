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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// RFC 8259 compliance against the vendored JSONTestSuite corpus (see `jsontestsuite/README.md`): every `y_` file
/// accepted, every `n_` file rejected, every `i_` file as pinned in [#INFORMATIVE], and no exception (stack overflow
/// included) escaping for any of them.
class JsonTestSuiteTest {
    static final boolean ACCEPT = true;
    static final boolean REJECT = false;

    static final tools.jackson.databind.json.JsonMapper JACKSON = ((JsonMapper.JsonMapperImpl) JsonMapper.defaultJsonMapper()).mapper();

    /// Implementation-defined files: the outcome we commit to. Jackson's, except for [#DEVIATIONS_FROM_JACKSON].
    static final Map<String, Boolean> INFORMATIVE = Map.ofEntries(entry("i_number_double_huge_neg_exp.json", ACCEPT),
                                                                  entry("i_number_huge_exp.json", ACCEPT),
                                                                  entry("i_number_neg_int_huge_exp.json", ACCEPT),
                                                                  entry("i_number_pos_double_huge_exp.json", ACCEPT),
                                                                  entry("i_number_real_neg_overflow.json", ACCEPT),
                                                                  entry("i_number_real_pos_overflow.json", ACCEPT),
                                                                  entry("i_number_real_underflow.json", ACCEPT),
                                                                  entry("i_number_too_big_neg_int.json", ACCEPT),
                                                                  entry("i_number_too_big_pos_int.json", ACCEPT),
                                                                  entry("i_number_very_big_negative_int.json", ACCEPT),
                                                                  entry("i_object_key_lone_2nd_surrogate.json", REJECT),
                                                                  entry("i_string_1st_surrogate_but_2nd_missing.json", REJECT),
                                                                  entry("i_string_1st_valid_surrogate_2nd_invalid.json", REJECT),
                                                                  entry("i_string_UTF-16LE_with_BOM.json", REJECT),
                                                                  entry("i_string_UTF-8_invalid_sequence.json", REJECT),
                                                                  entry("i_string_UTF8_surrogate_U+D800.json", REJECT),
                                                                  entry("i_string_incomplete_surrogate_and_escape_valid.json", REJECT),
                                                                  entry("i_string_incomplete_surrogate_pair.json", REJECT),
                                                                  entry("i_string_incomplete_surrogates_escape_valid.json", REJECT),
                                                                  entry("i_string_invalid_lonely_surrogate.json", REJECT),
                                                                  entry("i_string_invalid_surrogate.json", REJECT),
                                                                  entry("i_string_invalid_utf-8.json", REJECT),
                                                                  entry("i_string_inverted_surrogates_U+1D11E.json", REJECT),
                                                                  entry("i_string_iso_latin_1.json", REJECT),
                                                                  entry("i_string_lone_second_surrogate.json", REJECT),
                                                                  entry("i_string_lone_utf8_continuation_byte.json", REJECT),
                                                                  entry("i_string_not_in_unicode_range.json", REJECT),
                                                                  entry("i_string_overlong_sequence_2_bytes.json", REJECT),
                                                                  entry("i_string_overlong_sequence_6_bytes.json", REJECT),
                                                                  entry("i_string_overlong_sequence_6_bytes_null.json", REJECT),
                                                                  entry("i_string_truncated-utf-8.json", REJECT),
                                                                  entry("i_string_utf16BE_no_BOM.json", REJECT),
                                                                  entry("i_string_utf16LE_no_BOM.json", REJECT),
                                                                  entry("i_structure_500_nested_arrays.json", ACCEPT),
                                                                  entry("i_structure_UTF-8_BOM_empty_object.json", REJECT));

    /// Where [#INFORMATIVE] departs from Jackson on purpose, with the reason (mirrored in the README).
    static final Map<String, String> DEVIATIONS_FROM_JACKSON = Map.ofEntries(
                                                                              entry("i_string_not_in_unicode_range.json", "invalid UTF-8 (above U+10FFFF) rejected"),
                                                                              entry("i_string_overlong_sequence_2_bytes.json", "invalid UTF-8 (overlong) rejected"),
                                                                              entry("i_string_UTF-16LE_with_BOM.json", "input must be UTF-8 (RFC 8259 8.1)"),
                                                                              entry("i_string_utf16BE_no_BOM.json", "input must be UTF-8 (RFC 8259 8.1)"),
                                                                              entry("i_string_utf16LE_no_BOM.json", "input must be UTF-8 (RFC 8259 8.1)"),
                                                                              entry("i_structure_UTF-8_BOM_empty_object.json", "byte order mark not skipped"),
                                                                              entry("i_string_1st_surrogate_but_2nd_missing.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_1st_valid_surrogate_2nd_invalid.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_incomplete_surrogate_and_escape_valid.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_incomplete_surrogate_pair.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_incomplete_surrogates_escape_valid.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_invalid_lonely_surrogate.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_invalid_surrogate.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_inverted_surrogates_U+1D11E.json", "lone surrogate escape rejected"),
                                                                              entry("i_string_lone_second_surrogate.json", "lone surrogate escape rejected"));

    /// Accepted by both, but the integer token `-0` read as a double is -0.0 here (as `Double.parseDouble` reads it)
    /// while Jackson's double path yields +0.0.
    static final Set<String> NEGATIVE_ZERO_VALUES = Set.of("y_number_minus_zero.json", "y_number_negative_zero.json");

    static final Path DIRECTORY = directory();

    static Path directory() {
        try {
            return Path.of(Objects.requireNonNull(JsonTestSuiteTest.class.getResource("/jsontestsuite/LICENSE"))
                                  .toURI())
                       .getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<String> files() {
        try (var listing = Files.list(DIRECTORY)) {
            return listing.map(path -> path.getFileName()
                                           .toString())
                          .filter(name -> name.endsWith(".json"))
                          .sorted()
                          .toList()
                          .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Stream<String> informativeFiles() {
        return files().filter(name -> name.startsWith("i_"));
    }

    static byte[] bytes(String name) {
        try {
            return Files.readAllBytes(DIRECTORY.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean validates(byte[] json) {
        var in = JsonReader.jsonReader()
                           .reset(json);

        in.skipValue();
        in.finish();

        return in.result(Unit.unit())
                 .isSuccess();
    }

    static boolean expected(String name) {
        return switch (name.charAt(0)) {
            case 'y' -> ACCEPT;
            case 'n' -> REJECT;
            default -> {
                assertNotNull(INFORMATIVE.get(name), "no pinned expectation for " + name);
                yield INFORMATIVE.get(name);
            }
        };
    }

    static boolean jacksonAccepts(byte[] json) {
        try {
            JACKSON.readTree(json);

            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @ParameterizedTest
    @MethodSource("files")
    void genericValidation_matchesTheFileClass(String name) {
        var json = bytes(name);
        boolean accepted = assertDoesNotThrow(() -> validates(json), name);

        assertEquals(expected(name), accepted, name);
    }

    @ParameterizedTest
    @MethodSource("informativeFiles")
    void informativeExpectation_isJacksons_unlessListedAsDeviation(String name) {
        var differs = INFORMATIVE.get(name) != jacksonAccepts(bytes(name));

        assertEquals(DEVIATIONS_FROM_JACKSON.containsKey(name), differs, name);
    }

    @Test
    void informativeTable_coversExactlyTheInformativeFiles() {
        assertEquals(informativeFiles().collect(Collectors.toSet()), INFORMATIVE.keySet());
        assertTrue(INFORMATIVE.keySet()
                              .containsAll(DEVIATIONS_FROM_JACKSON.keySet()));
        assertEquals(95, files().filter(name -> name.startsWith("y_")).count());
        assertEquals(188, files().filter(name -> name.startsWith("n_")).count());
    }

    // ---- typed readers -------------------------------------------------------------------------
    enum Kind {
        STRING,
        NUMBER,
        BOOLEAN,
        NULL
    }

    /// A document that is (or, for invalid files, looks like) an array holding exactly one scalar.
    record Single(String name, Kind kind, String text) {
        @Override
        public String toString() {
            return name;
        }
    }

    static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    static boolean isStructural(byte b) {
        return isWhitespace(b) || b == ',' || b == ':' || b == '[' || b == ']' || b == '{' || b == '}' || b == '"';
    }

    static Stream<Single> singleScalarFiles() {
        return files().flatMap(name -> single(name).stream());
    }

    static java.util.Optional<Single> single(String name) {
        var json = bytes(name);
        var from = 0;
        var to = json.length;

        while (from < to && isWhitespace(json[from])) {
            from++;
        }

        while (to > from && isWhitespace(json[to - 1])) {
            to--;
        }

        if (to - from < 3 || json[from] != '[' || json[to - 1] != ']') {
            return java.util.Optional.empty();
        }

        from++;
        to--;
        while (from < to && isWhitespace(json[from])) {
            from++;
        }

        while (to > from && isWhitespace(json[to - 1])) {
            to--;
        }

        if (from >= to) {
            return java.util.Optional.empty();
        }

        var text = new String(json, from, to - from, StandardCharsets.ISO_8859_1);

        return kindOf(json, from, to).map(kind -> new Single(name, kind, text));
    }

    static java.util.Optional<Kind> kindOf(byte[] json, int from, int to) {
        if (json[from] == '"') {
            var i = from + 1;

            while (i < to && json[i] != '"') {
                i += json[i] == '\\'
                     ? 2
                     : 1;
            }

            return i == to - 1
                   ? java.util.Optional.of(Kind.STRING)
                   : java.util.Optional.empty();
        }

        for (int i = from; i < to; i++) {
            if (isStructural(json[i])) {
                return java.util.Optional.empty();
            }
        }

        var first = json[from];

        if (first == '-' || first == '+' || first == '.' || (first >= '0' && first <= '9') || first == 'I' || first == 'N') {
            return java.util.Optional.of(Kind.NUMBER);
        }

        return java.util.Optional.of(first == 'n'
                                     ? Kind.NULL
                                     : Kind.BOOLEAN);
    }

    /// Reads `[value]` with `read`; empty when rejected (a failure, or more than one element).
    static <T> java.util.Optional<T> typed(byte[] json, Function<JsonReader, T> read) {
        var in = JsonReader.jsonReader()
                           .reset(json);

        in.beginArray();
        in.firstElement();
        var value = read.apply(in);
        var more = in.nextElement();

        in.finish();

        Result<T> result = in.result(value);

        return more || result.isFailure()
               ? java.util.Optional.empty()
               : java.util.Optional.of(value);
    }

    static Boolean readNull(JsonReader in) {
        return in.isNull()
               ? Boolean.TRUE
               : null;
    }

    static boolean fitsLong(String text) {
        try {
            Long.parseLong(text);

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean fitsDecimal(String text) {
        try {
            new BigDecimal(text);

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static JsonNode jacksonElement(byte[] json) {
        return JACKSON.readTree(json)
                      .get(0);
    }

    @ParameterizedTest
    @MethodSource("singleScalarFiles")
    void typedReader_agreesWithGenericValidation_andWithJacksonsValue(Single single) {
        var json = bytes(single.name());
        var accepted = validates(json);

        switch (single.kind()) {
            case STRING -> checkString(single, json, accepted);
            case NUMBER -> checkNumber(single, json, accepted);
            case BOOLEAN -> checkBoolean(single, json, accepted);
            case NULL -> assertEquals(accepted, typed(json, JsonTestSuiteTest::readNull).isPresent(), single.name());
        }
    }

    static void checkBoolean(Single single, byte[] json, boolean accepted) {
        var ours = typed(json, JsonReader::readBoolean);

        assertEquals(accepted, ours.isPresent(), single.name());
        ours.ifPresent(value -> assertEquals(jacksonElement(json).booleanValue(), value, single.name()));
    }

    static void checkString(Single single, byte[] json, boolean accepted) {
        var ours = typed(json, JsonReader::readString);

        assertEquals(accepted, ours.isPresent(), single.name());
        ours.ifPresent(value -> assertStringValue(single.name(), jacksonElement(json).asString(), value));
    }

    static void assertStringValue(String name, String jackson, String ours) {
        assertEquals(jackson, ours, name);
    }

    static void assertDoubleValue(String name, double jackson, double ours) {
        if (NEGATIVE_ZERO_VALUES.contains(name)) {
            assertEquals(0.0, jackson, name);
            assertEquals(-0.0, ours, name);
        } else {
            assertEquals(jackson, ours, name + " readDouble");
        }
    }

    static void checkNumber(Single single, byte[] json, boolean accepted) {
        var asDouble = typed(json, JsonReader::readDouble);

        assertEquals(accepted, asDouble.isPresent(), single.name() + " readDouble");
        asDouble.ifPresent(value -> assertDoubleValue(single.name(), JACKSON.readValue(json, double[].class)[0], value));

        if (fitsLong(single.text())) {
            var asLong = typed(json, JsonReader::readLong);

            assertEquals(accepted, asLong.isPresent(), single.name() + " readLong");
            asLong.ifPresent(value -> assertEquals(jacksonElement(json).longValue(), value, single.name() + " readLong"));
        }

        if (fitsDecimal(single.text())) {
            var asDecimal = typed(json, JsonReader::readDecimal);

            assertEquals(accepted, asDecimal.isPresent(), single.name() + " readDecimal");
            asDecimal.ifPresent(value -> assertEquals(JACKSON.readValue(json, BigDecimal[].class)[0], value, single.name() + " readDecimal"));
        }
    }

    @Test
    void typedCoverage_reachesEveryKind() {
        var kinds = singleScalarFiles().collect(Collectors.groupingBy(Single::kind, Collectors.counting()));

        for (var kind : Kind.values()) {
            assertTrue(kinds.getOrDefault(kind, 0L) > 0, kind + " never exercised: " + kinds);
        }
    }
}
