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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.json.JsonError;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The reader's strictness: full-grammar skipping, nesting limit, UTF-8 well-formedness, canonical UUIDs, exact
/// decimal exponents and non-finite doubles.
class JsonReaderValidationTest {
    // Plain Jackson 3: the oracle writes scalars only, which the Pragmatica module never touched; that module now
    // depends on this one.
    static final tools.jackson.databind.json.JsonMapper JACKSON = tools.jackson.databind.json.JsonMapper.builder()
                                                                                                        .build();

    static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /// Generic validation: one value of any kind, then nothing but whitespace.
    static Result<Unit> validate(byte[] json) {
        var in = JsonReader.jsonReader()
                           .reset(json);

        in.skipValue();
        in.finish();

        return in.result(Unit.unit());
    }

    static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    @Test
    void skipValue_rejectsBrokenGrammar_insideUnknownValues() {
        for (var bad : new String[]{"{]", "[tru , , : xyz]", "[}", "{\"a\" 1}", "{\"a\":1,}", "[1,]", "[,1]", "{,}", "{\"a\":1 \"b\":2}",
                                    "[1 2]", "[nul]", "[truex]", "[True]", "{1:2}", "{\"a\"}", "{\"a\":}", "[01]", "[1.]", "[.5]", "[1e]",
                                    "[1e+]", "[-]", "[+1]", "[0x1]", "[\"\\x\"]", "[\"\\u12\"]", "[\"a\tb\"]", "[\"unterminated]", "[1]]",
                                    "[[]", "{\"a\":{\"b\":[1,{\"c\":2]}}",
                                    // Balanced counts, mismatched kinds: only bracket matching rejects these.
                                    "[1}", "{\"a\":1]", "[{\"a\":1]}", "{\"a\":[1}]", "[[1}]"}) {
            assertTrue(validate(utf8(bad)).isFailure(), bad);
        }
    }

    static Result<String> readString(String json) {
        var in = JsonReader.jsonReader()
                           .reset(utf8(json));
        var value = in.readString();

        in.finish();

        return in.result(value);
    }

    /// A lone surrogate has no UTF-8 encoding, so it is rejected wherever an escape is decoded or skipped,
    /// rather than replaced with U+FFFD.
    @Test
    void loneSurrogateEscapes_areRejected_inValuesKeysAndSkippedValues() {
        for (var lone : new String[]{"\\uD800", "\\uDC00", "\\uD800x", "\\uD800\\u0041", "\\uD800\\uD800", "\\uDD1E\\uD834",
                                     "a\\uDBFF"}) {
            assertTrue(readString("\"" + lone + "\"").isFailure(), "value " + lone);
            assertTrue(validate(utf8("[\"" + lone + "\"]")).isFailure(), "skipped value " + lone);
            assertTrue(validate(utf8("{\"" + lone + "\":1}")).isFailure(), "skipped key " + lone);
        }
    }

    @Test
    void pairedSurrogateEscapes_decodeToTheSupplementaryCodePoint() {
        assertEquals("\uD834\uDD1E", readString("\"\\uD834\\uDD1E\"").unwrap());
        assertEquals("x\uDBFF\uDFFFy", readString("\"x\\uDBFF\\uDFFFy\"").unwrap());
        assertTrue(validate(utf8("{\"\\uD834\\uDD1E\":[\"\\uD834\\uDD1E\"]}")).isSuccess());
    }

    @Test
    void skipValue_acceptsEveryValidShape() {
        for (var good : new String[]{"{}", "[]", " [ ] ", "{\"a\":[1,-2.5e+3,0,-0,1E-2,true,false,null,\"s\\n\\u00e9\",{}]}",
                                     "[[[{\"x\":{\"y\":[]}}]]]", "\"lonely\"", "-0.0e0", "null", "{\"\":\"\"}",
                                     "{ \"a\" : 1 , \"b\" : [ 2 , 3 ] }"}) {
            assertTrue(validate(utf8(good)).isSuccess(), good);
        }
    }

    @Test
    void skipValue_nestedBeyondLimit_failsAsNestingTooDeep_withoutStackOverflow() {
        var deep = "[".repeat(100_000) + "]".repeat(100_000);

        assertInstanceOf(JsonError.NestingTooDeep.class, failureOf(validate(utf8(deep))));

        var atLimit = "[".repeat(JsonReader.MAX_DEPTH) + "]".repeat(JsonReader.MAX_DEPTH);
        var mixed = "{\"a\":[".repeat(JsonReader.MAX_DEPTH / 2) + "]}".repeat(JsonReader.MAX_DEPTH / 2);

        assertTrue(validate(utf8(atLimit)).isSuccess());
        assertTrue(validate(utf8(mixed)).isSuccess());
        assertInstanceOf(JsonError.NestingTooDeep.class,
                         failureOf(validate(utf8("[" + atLimit + "]"))));
    }

    @Test
    void beginArray_countsGeneratedNestingAgainstTheSameLimit() {
        var in = JsonReader.jsonReader()
                           .reset(utf8("[".repeat(JsonReader.MAX_DEPTH + 1)));

        for (int i = 0; i < JsonReader.MAX_DEPTH; i++) {
            assertTrue(in.beginArray());
        }

        assertFalse(in.beginArray());
        assertInstanceOf(JsonError.NestingTooDeep.class, failureOf(in.result(Unit.unit())));
    }

    @Test
    void skipValue_insideGeneratedNesting_sharesTheDepthBudget() {
        var half = JsonReader.MAX_DEPTH / 2;
        var in = JsonReader.jsonReader()
                           .reset(utf8("[".repeat(half) + "[".repeat(half + 1) + "]".repeat(half + 1)));

        for (int i = 0; i < half; i++) {
            in.beginArray();
            in.firstElement();
        }

        in.skipValue();
        assertInstanceOf(JsonError.NestingTooDeep.class, failureOf(in.result(Unit.unit())));
    }

    @Test
    void closingContainers_releaseDepth() {
        // 2 * MAX_DEPTH sibling arrays: each one closes before the next opens.
        var json = "[" + "[],".repeat(2 * JsonReader.MAX_DEPTH) + "[]]";
        var in = JsonReader.jsonReader()
                           .reset(utf8(json));

        in.beginArray();
        for (var more = in.firstElement(); more; more = in.nextElement()) {
            in.beginArray();
            in.firstElement();
        }

        in.finish();
        assertTrue(in.result(Unit.unit())
                     .isSuccess());
    }

    @Test
    void firstElement_atEndOfInput_isAFailure() {
        var in = JsonReader.jsonReader()
                           .reset(utf8("["));

        in.beginArray();
        assertFalse(in.firstElement());
        in.finish();
        assertTrue(in.result(Unit.unit())
                     .isFailure());

        var map = JsonReader.jsonReader()
                            .reset(utf8("{"));

        map.beginObject();
        assertFalse(map.firstEntry());
        map.finish();
        assertTrue(map.result(Unit.unit())
                      .isFailure());
    }

    // ---- UTF-8 ---------------------------------------------------------------------------------
    static final byte[][] INVALID_UTF8 = {{(byte) 0xC3, 0x28},                                   // 2-byte lead, ASCII follows
                                          {(byte) 0x80},                                          // lone continuation
                                          {(byte) 0xC0, (byte) 0xAF},                             // overlong '/'
                                          {(byte) 0xC1, (byte) 0xBF},                             // overlong
                                          {(byte) 0xE0, (byte) 0x80, (byte) 0xAF},                // overlong 3-byte
                                          {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0xAF},   // overlong 4-byte
                                          {(byte) 0xED, (byte) 0xA0, (byte) 0x80},                // surrogate U+D800
                                          {(byte) 0xED, (byte) 0xBF, (byte) 0xBF},                // surrogate U+DFFF
                                          {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80},   // U+110000
                                          {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80},   // lead past F4
                                          {(byte) 0xFF},
                                          {(byte) 0xE2, (byte) 0x82},                             // truncated
                                          {(byte) 0xF0, (byte) 0x9F, (byte) 0x98},                // truncated 4-byte
                                          {(byte) 0xE2, 0x41, (byte) 0xAC}};                     // broken continuation

    static final byte[][] VALID_UTF8 = {{(byte) 0xC2, (byte) 0x80},                               // U+0080
                                        {(byte) 0xDF, (byte) 0xBF},                               // U+07FF
                                        {(byte) 0xE0, (byte) 0xA0, (byte) 0x80},                  // U+0800
                                        {(byte) 0xED, (byte) 0x9F, (byte) 0xBF},                  // U+D7FF
                                        {(byte) 0xEE, (byte) 0x80, (byte) 0x80},                  // U+E000
                                        {(byte) 0xEF, (byte) 0xBF, (byte) 0xBF},                  // U+FFFF
                                        {(byte) 0xF0, (byte) 0x90, (byte) 0x80, (byte) 0x80},     // U+10000
                                        {(byte) 0xF4, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF}};    // U+10FFFF

    // `"` + prefix + bytes + suffix + `"`, padded so both the 8-byte and the byte-at-a-time scan paths see them.
    static byte[] quoted(String prefix, byte[] bytes, String suffix) {
        var head = utf8("\"" + prefix);
        var tail = utf8(suffix + "\"");
        var out = new byte[head.length + bytes.length + tail.length];

        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(bytes, 0, out, head.length, bytes.length);
        System.arraycopy(tail, 0, out, head.length + bytes.length, tail.length);

        return out;
    }

    static Result<String> readString(byte[] json) {
        var in = JsonReader.jsonReader()
                           .reset(json);
        var value = in.readString();

        in.finish();

        return in.result(value);
    }

    @Test
    void readString_rejectsMalformedUtf8_onEveryPath() {
        for (var bytes : INVALID_UTF8) {
            for (var prefix : new String[]{"", "abcdefghij"}) {
                for (var suffix : new String[]{"", "x", "\\n", "klmnopqrstuvwxyz"}) {
                    var json = quoted(prefix, bytes, suffix);
                    var label = prefix + "|" + suffix + "|" + bytes.length;

                    assertInstanceOf(JsonError.Malformed.class, failureOf(readString(json)), label);
                    assertTrue(validate(json).isFailure(), "skip " + label);
                    assertTrue(readString(quoted("\\t" + prefix, bytes, suffix)).isFailure(), "escaped " + label);
                }
            }
        }
    }

    @Test
    void readString_acceptsWellFormedUtf8_atTheBoundaries() {
        for (var bytes : VALID_UTF8) {
            var expected = new String(bytes, StandardCharsets.UTF_8);

            for (var prefix : new String[]{"", "abcdefghij"}) {
                var json = quoted(prefix, bytes, "z");

                assertEquals(prefix + expected + "z", readString(json).unwrap());
                assertTrue(validate(json).isSuccess());
                assertEquals("\t" + prefix + expected + "z", readString(quoted("\\t" + prefix, bytes, "z")).unwrap());
            }
        }
    }

    @Test
    void invalidUtf8_agreesWithStrictJdkDecoder_forRandomBytes() {
        var random = new Random(3629);
        var decoder = StandardCharsets.UTF_8.newDecoder()
                                            .onMalformedInput(CodingErrorAction.REPORT)
                                            .onUnmappableCharacter(CodingErrorAction.REPORT);

        for (int i = 0; i < 300_000; i++) {
            var bytes = new byte[1 + random.nextInt(6)];

            for (int k = 0; k < bytes.length; k++) {
                // Bias toward the interesting range: lead and continuation bytes.
                bytes[k] = (byte)(random.nextInt(4) == 0
                                  ? random.nextInt(256)
                                  : 0x80 + random.nextInt(0x80));
            }

            var jdkValid = isValidPerJdk(decoder, bytes);

            assertEquals(jdkValid, JsonReader.invalidUtf8(bytes, 0, bytes.length) < 0, () -> hex(bytes));
        }
    }

    static boolean isValidPerJdk(java.nio.charset.CharsetDecoder decoder, byte[] bytes) {
        try {
            decoder.reset()
                   .decode(ByteBuffer.wrap(bytes));

            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static String hex(byte[] bytes) {
        var sb = new StringBuilder();

        for (var b : bytes) {
            sb.append(String.format("%02X ", b));
        }

        return sb.toString();
    }

    @Test
    void fieldNamesAndEnumConstants_withMalformedUtf8_areRejected() {
        var names = FieldNames.fieldNames("a", "b");
        var key = quoted("", new byte[]{(byte) 0xC3, 0x28}, "");
        var json = new byte[key.length + 4];

        json[0] = '{';
        System.arraycopy(key, 0, json, 1, key.length);
        json[key.length + 1] = ':';
        json[key.length + 2] = '1';
        json[key.length + 3] = '}';

        var in = JsonReader.jsonReader()
                           .reset(json);

        in.beginObject();
        in.firstField(names);
        assertTrue(in.hasError());

        var constant = JsonReader.jsonReader()
                                 .reset(quoted("", new byte[]{(byte) 0xC3, 0x28}, ""));

        assertEquals(-1, constant.readEnumOr(names, 1));
        assertTrue(constant.hasError());
    }

    // ---- escaped names -------------------------------------------------------------------------
    @Test
    void escapedEnumConstant_resolvesToItsConstant() {
        var constants = FieldNames.fieldNames("RED", "A", "UNKNOWN");

        assertEquals(1, JsonReader.jsonReader()
                                  .reset(utf8("\"\\u0041\""))
                                  .readEnum(constants));
        assertEquals(1, JsonReader.jsonReader()
                                  .reset(utf8("\"\\u0041\""))
                                  .readEnumOr(constants, 2));
        assertEquals(0, JsonReader.jsonReader()
                                  .reset(utf8("\"R\\u0045D\""))
                                  .readEnumOr(constants, 2));
        assertEquals(2, JsonReader.jsonReader()
                                  .reset(utf8("\"\\u0042\""))
                                  .readEnumOr(constants, 2));

        var undeclared = JsonReader.jsonReader()
                                   .reset(utf8("\"\\u0042\""));

        assertEquals(-1, undeclared.readEnum(constants));
        assertTrue(undeclared.hasError());
    }

    @Test
    void escapedFieldName_bindsToItsField() {
        var names = FieldNames.fieldNames("name", "age");
        var in = JsonReader.jsonReader()
                           .reset(utf8("{\"\\u006eame\":\"x\",\"a\\u0067e\":1}"));

        in.beginObject();
        assertEquals(0, in.firstField(names));
        assertEquals("x", in.readString());
        assertEquals(1, in.nextField(names, 1));
        assertEquals(1, in.readLong());
        assertEquals(JsonReader.END_OF_OBJECT, in.nextField(names, 2));
        assertFalse(in.hasError());
    }

    // ---- UUID ----------------------------------------------------------------------------------
    static Result<UUID> readUuid(String json) {
        var in = JsonReader.jsonReader()
                           .reset(utf8(json));
        var value = in.readUuid();

        in.finish();

        return in.result(value);
    }

    @Test
    void readUuid_acceptsOnlyTheCanonicalForm() {
        var uuid = UUID.randomUUID();

        assertEquals(uuid, readUuid("\"" + uuid + "\"").unwrap());
        assertEquals(uuid, readUuid("\"" + uuid.toString().toUpperCase() + "\"").unwrap());
        assertEquals(uuid, readUuid("\"\\u00" + Integer.toHexString(uuid.toString().charAt(0)) + uuid.toString().substring(1) + "\"").unwrap());
        assertEquals(new UUID(-1, -1), readUuid("\"ffffffff-ffff-ffff-ffff-ffffffffffff\"").unwrap());
        assertEquals(new UUID(0, 0), readUuid("\"00000000-0000-0000-0000-000000000000\"").unwrap());

        for (var bad : new String[]{"\"1-1-1-1-1\"", "\"" + uuid.toString().replace('-', '_') + "\"", "\"" + uuid + "0\"",
                                    "\"" + uuid.toString().substring(1) + "\"", "\"g" + uuid.toString().substring(1) + "\"",
                                    "\"0000000-00000-0000-0000-000000000000\"", "\"+0000000-0000-0000-0000-000000000000\"", "\"\""}) {
            assertTrue(readUuid(bad).isFailure(), bad);
        }
    }

    // A 36-character string between quotes takes the byte fast path, and a value the fast path accepts never reaches
    // the canonical-form check: each separator test in the fast path is the only thing refusing these, one character
    // off at exactly one separator, with hex digits on both sides.
    @Test
    void readUuid_fastPath_refusesANonHyphenAtEachSeparator() {
        var canonical = "123e4567-e89b-12d3-a456-426614174000";

        for (var at : new int[]{8, 13, 18, 23}) {
            for (var replacement : new char[]{'x', '0', '_'}) {
                var text = canonical.substring(0, at) + replacement + canonical.substring(at + 1);

                assertTrue(readUuid("\"" + text + "\"").isFailure(), text);
            }
        }
    }

    // ---- numbers -------------------------------------------------------------------------------
    static Result<BigDecimal> readDecimal(String json) {
        var in = JsonReader.jsonReader()
                           .reset(utf8(json));
        var value = in.readDecimal();

        in.finish();

        return in.result(value);
    }

    @Test
    void readDecimal_keepsTheExponentExact() {
        for (var text : new String[]{"1e1234567", "1e-1234567", "-12.5e2147483646", "1e2147483647", "1e-2147483647", "1e2147483648",
                                     "0.1e2147483648", "12345678901234567890123e1234567", "12345678901234567890123e2147483648",
                                     "0e999999", "1.5E+100001"}) {
            assertEquals(new BigDecimal(text), readDecimal(text).unwrap(), text);
        }
    }

    @Test
    void readDecimal_beyondBigDecimalScale_failsAsOutOfRange() {
        for (var text : new String[]{"1e2147483649", "1e-2147483648", "0.1e-2147483647", "1e99999999999999999999",
                                     "12345678901234567890123e2147483649", "0e9999999999"}) {
            assertThrows(NumberFormatException.class, () -> new BigDecimal(text), text);
            assertInstanceOf(JsonError.OutOfRange.class, failureOf(readDecimal(text)), text);
        }
    }

    @Test
    void readDouble_hugeExponents_matchParseDouble() {
        for (var text : new String[]{"1e1234567", "-1e1234567", "1e-1234567", "0.4e0066999999999999999999999999", "1e400", "1e-400"}) {
            var in = JsonReader.jsonReader()
                               .reset(utf8(text));

            assertEquals(Double.parseDouble(text), in.readDouble(), text);
            assertFalse(in.hasError(), text);
        }
    }

    static Result<Double> readDouble(String json) {
        var in = JsonReader.jsonReader()
                           .reset(utf8(json));
        var value = in.readDouble();

        in.finish();

        return in.result(value);
    }

    @Test
    void readDouble_acceptsExactlyTheThreeQuotedNonFiniteForms() {
        assertTrue(Double.isNaN(readDouble("\"NaN\"").unwrap()));
        assertEquals(Double.POSITIVE_INFINITY, readDouble("\"Infinity\"").unwrap());
        assertEquals(Double.NEGATIVE_INFINITY, readDouble(" \"-Infinity\"").unwrap());

        for (var bad : new String[]{"NaN", "Infinity", "-Infinity", "\"nan\"", "\"+Infinity\"", "\"Inf\"", "\"1.5\"", "\"\"",
                                    "\"NaN", "\"\\u004EaN\""}) {
            assertTrue(readDouble(bad).isFailure(), bad);
        }
    }

    @Test
    void writeDouble_nonFinite_matchesJacksonBytes() {
        for (var value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var out = JsonWriter.jsonWriter(64);

            out.writeDouble(value);
            assertEquals(new String(JACKSON.writeValueAsBytes(value), StandardCharsets.UTF_8),
                         new String(out.toByteArray(), StandardCharsets.UTF_8));
            assertEquals(Double.doubleToLongBits(value), Double.doubleToLongBits(readDouble(new String(out.toByteArray(),
                                                                                                        StandardCharsets.UTF_8)).unwrap()));
        }
    }

    @Test
    void writeNull_writesTheNullLiteral() {
        var out = JsonWriter.jsonWriter(64);

        out.writeByte('[');
        out.writeNull();
        out.writeByte(']');
        assertEquals("[null]", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }
}
