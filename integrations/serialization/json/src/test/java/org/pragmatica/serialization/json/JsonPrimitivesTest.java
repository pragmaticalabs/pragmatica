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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPrimitivesTest {
    static final JsonMapper JACKSON = JsonMapper.defaultJsonMapper();

    static JsonReader reader(String json) {
        return JsonReader.jsonReader()
                         .reset(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readLong_acceptsBoundaries_rejectsOverflowAndBadForms() {
        assertEquals(Long.MAX_VALUE, reader("9223372036854775807").readLong());
        assertEquals(Long.MIN_VALUE, reader("-9223372036854775808").readLong());
        assertEquals(0, reader("-0").readLong());
        for (var bad : new String[]{"9223372036854775808", "-9223372036854775809", "01", "-", "1.0", "1e2", "", "+1"}) {
            var r = reader(bad);
            r.readLong();
            r.finish();
            assertTrue(r.hasError(), bad);
        }
    }

    @Test
    void readInt_rejectsValuesOutsideInt() {
        var r = reader("2147483648");
        r.readInt();
        assertTrue(r.hasError());
        assertEquals(Integer.MIN_VALUE, reader("-2147483648").readInt());
    }

    @Test
    void readDouble_matchesParseDouble_forRandomAndEdgeValues() {
        var random = new Random(7);
        for (int i = 0; i < 200_000; i++) {
            var value = switch (i % 4) {
                case 0 -> Double.longBitsToDouble(random.nextLong());
                case 1 -> random.nextDouble() * 1000;
                case 2 -> Math.round(random.nextDouble() * 1_000_000) / 100.0;
                default -> random.nextGaussian() * Math.pow(10, random.nextInt(40) - 20);
            };
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            var text = Double.toString(value);
            assertEquals(Double.parseDouble(text), reader(text).readDouble(), text);
        }
        for (var text : new String[]{"0", "-0.0", "1e22", "1e23", "123456789012345678901234567890", "4.9e-324", "1.7976931348623157e308",
                                     "0.1", "2.2250738585072014E-308", "9007199254740993", "1E-7", "100000000000000000000000"}) {
            assertEquals(Double.parseDouble(text), reader(text).readDouble(), text);
        }
    }

    // Oracle: Double.parseDouble. Eisel-Lemire must agree whenever it answers, and answer almost always.
    @Test
    void eiselLemire_agreesWithParseDouble_andRarelyDefers() {
        var random = new Random(61);
        var deferred = 0;
        var total = 1_000_000;
        for (int i = 0; i < total; i++) {
            var digits = 1 + random.nextInt(18);
            var significand = 1 + (random.nextLong() >>> 1) % (long) Math.pow(10, digits);
            var power = random.nextInt(4) == 0 ? random.nextInt(651) - 342 : random.nextInt(60) - 30;
            var negative = random.nextBoolean();
            var text = (negative ? "-" : "") + significand + "e" + power;
            var fast = EiselLemire.toDouble(significand, power, negative);
            if (Double.isNaN(fast)) {
                deferred++;
                continue;
            }
            assertEquals(Double.parseDouble(text), fast, text);
        }
        // Range edges (overflow, subnormal) and ties defer by design; anything near all-defer is broken.
        assertTrue(deferred < total * 3 / 100, "deferred " + deferred); // measured 1.71% at seed 61
        for (int i = 0; i < 200_000; i++) {
            var value = Double.longBitsToDouble(random.nextLong());
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            var text = Double.toString(value);
            assertEquals(Double.parseDouble(text), reader(text).readDouble(), text);
        }
    }

    @Test
    void readDecimal_equalsBigDecimalOfText() {
        for (var text : new String[]{"0", "29.99", "149.00", "-0.05", "1e5", "1.5E-3", "123456789012345678", "1234567890123456789",
                                     "0.12345678901234567890123", "99999999999999999999.5", "0.000000000000000000001"}) {
            assertEquals(new BigDecimal(text), reader(text).readDecimal(), text);
        }
    }

    @Test
    void readString_decodesEscapesAndUnicode() {
        assertEquals("a\"b\\c/d\b\f\n\r\t", reader("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\"").readString());
        assertEquals("é€😀", reader("\"\\u00e9\\u20AC\\ud83d\\ude00\"").readString());
        assertEquals("Café Crème 😀", reader("\"Café Crème 😀\"").readString());
        var r = reader("\"bad\u0001\"");
        r.readString();
        assertTrue(r.hasError());
    }

    private static final String[] SPECIALS = {"\"", "\\", "\n", "\u0001", "é", "€", "😀"};

    // Escaped strings longer than the 64-byte scratch buffer exercise its growth path.
    @Test
    void readString_decodesLongEscapedStrings() {
        var random = new Random(13);
        var writer = JsonWriter.jsonWriter(64);
        for (int i = 0; i < 2_000; i++) {
            var sb = new StringBuilder();
            for (int len = 50 + random.nextInt(2_000); len > 0; len--) {
                if (random.nextInt(10) == 0) {
                    sb.append(SPECIALS[random.nextInt(SPECIALS.length)]);
                } else {
                    sb.append((char) ('a' + random.nextInt(26)));
                }
            }
            var text = sb.toString();
            writer.reset()
                  .writeString(text);
            var r = JsonReader.jsonReader()
                              .reset(writer.toByteArray());
            assertEquals(text, r.readString());
            assertFalse(r.hasError());
        }
    }

    // Oracle: Instant.parse. Equal results where it succeeds, a recorded failure where it throws.
    @Test
    void readInstant_agreesWithInstantParse() {
        var random = new Random(21);
        for (int i = 0; i < 300_000; i++) {
            var text = String.format("%04d-%02d-%02d%s%02d:%02d:%02d%s%s", random.nextInt(10_000), 1 + random.nextInt(12),
                                     1 + random.nextInt(31), random.nextBoolean() ? "T" : "t", random.nextInt(24),
                                     random.nextInt(60), random.nextInt(60), fraction(random), random.nextInt(8) == 0 ? "z" : "Z");
            assertInstantAgrees(text);
        }
        for (var text : new String[]{"2024-02-29T00:00:00Z", "2023-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "2000-02-29T23:59:59.999999999Z",
                                     "2026-10-17T23:59:60Z", "2026-10-17T09:41:27.512+02:00", "2026-10-17T09:41:27.Z", "2026-10-17T09:41:27.1234567890Z",
                                     "2026-13-01T00:00:00Z", "2026-00-10T00:00:00Z", "2026-04-31T00:00:00Z", "2026-10-17T24:00:00Z",
                                     "+12026-10-17T00:00:00Z", "2026-10-17 09:41:27Z", "2026-10-17T09:41Z", "0000-01-01T00:00:00Z",
                                     "9999-12-31T23:59:59.999999999Z", "1969-12-31T23:59:59.5Z", "2026-1O-17T09:41:27Z"}) {
            assertInstantAgrees(text);
        }
    }

    private static String fraction(Random random) {
        var digits = random.nextInt(10);
        return digits == 0 ? "" : "." + String.format("%09d", random.nextInt(1_000_000_000)).substring(0, digits);
    }

    private static void assertInstantAgrees(String text) {
        var r = reader("\"" + text + "\"");
        var ours = r.readInstant();
        r.finish();
        try {
            var expected = java.time.Instant.parse(text);
            assertFalse(r.hasError(), text);
            assertEquals(expected, ours, text);
        } catch (java.time.format.DateTimeParseException e) {
            assertTrue(r.hasError(), text);
        }
    }

    // Oracle: the byte-at-a-time scan. Same index and same error state at every alignment and length.
    @Test
    void scanString_agreesWithScalarScan() {
        var random = new Random(31);
        byte[] specials = {'"', '\\', 0x00, 0x1F, 0x20, 0x21, 0x7F, (byte) 0x80, (byte) 0xA2, (byte) 0xDC, (byte) 0xFF};
        for (int round = 0; round < 300_000; round++) {
            var bytes = new byte[random.nextInt(48)];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = random.nextInt(12) == 0
                           ? specials[random.nextInt(specials.length)]
                           : (byte) ('#' + random.nextInt(0x5C - '#'));
            }
            var from = bytes.length == 0 ? 0 : random.nextInt(bytes.length);
            var fast = JsonReader.jsonReader().reset(bytes);
            var slow = JsonReader.jsonReader().reset(bytes);
            assertEquals(slow.scanStringScalar(from), fast.scanStringForTest(from), java.util.Arrays.toString(bytes) + " from " + from);
            assertEquals(slow.hasError(), fast.hasError());
            var stop = slow.scanStringScalar(from);
            if (!slow.hasError()) {
                // stop is the quote index, or -1 at a backslash: find that backslash for the ASCII range.
                var limit = stop >= 0 ? stop : indexOfBackslash(bytes, from);
                var ascii = true;
                for (int k = from; k < limit; k++) {
                    ascii &= bytes[k] >= 0;
                }
                assertEquals(ascii, fast.scannedAsciiForTest(), java.util.Arrays.toString(bytes) + " from " + from);
            }
        }
    }

    private static int indexOfBackslash(byte[] bytes, int from) {
        for (int k = from; k < bytes.length; k++) {
            if (bytes[k] == '\\') {
                return k;
            }
        }
        return bytes.length;
    }

    @Test
    void writeString_matchesJackson_forRandomStrings() {
        var random = new Random(11);
        var writer = JsonWriter.jsonWriter(64);
        for (int i = 0; i < 50_000; i++) {
            var text = randomString(random);
            writer.reset()
                  .writeString(text);
            var ours = new String(writer.toByteArray(), StandardCharsets.UTF_8);
            var expected = new String(JACKSON.writeAsBytes(text)
                                             .unwrap(), StandardCharsets.UTF_8);
            assertEquals(expected, ours);
            assertEquals(text, reader(ours).readString());
        }
    }

    // Oracle: Instant.toString, across the full fast range, every fraction shape, and both fallbacks.
    @Test
    void writeInstant_matchesToString() {
        var random = new Random(81);
        var writer = JsonWriter.jsonWriter(64);
        var min = java.time.Instant.parse("0000-01-01T00:00:00Z").getEpochSecond();
        var max = java.time.Instant.parse("9999-12-31T23:59:59Z").getEpochSecond();
        for (int i = 0; i < 300_000; i++) {
            var nanos = switch (random.nextInt(4)) {
                case 0 -> 0;
                case 1 -> random.nextInt(1_000) * 1_000_000;
                case 2 -> random.nextInt(1_000_000) * 1_000;
                default -> random.nextInt(1_000_000_000);
            };
            var instant = java.time.Instant.ofEpochSecond(min + (long) (random.nextDouble() * (max - min)), nanos);
            writer.reset().writeInstant(instant);
            assertEquals("\"" + instant + "\"", new String(writer.toByteArray(), StandardCharsets.US_ASCII));
        }
        for (var instant : new java.time.Instant[]{java.time.Instant.EPOCH, java.time.Instant.ofEpochSecond(-1), java.time.Instant.MIN,
                                                   java.time.Instant.MAX, java.time.Instant.parse("2024-02-29T23:59:59.999999999Z"),
                                                   java.time.Instant.ofEpochSecond(min - 1), java.time.Instant.ofEpochSecond(max + 1)}) {
            writer.reset().writeInstant(instant);
            assertEquals("\"" + instant + "\"", new String(writer.toByteArray(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void writeDouble_matchesToString() {
        var random = new Random(91);
        var writer = JsonWriter.jsonWriter(64);
        for (int i = 0; i < 300_000; i++) {
            var value = i % 2 == 0 ? Double.longBitsToDouble(random.nextLong()) : random.nextGaussian() * Math.pow(10, random.nextInt(30) - 15);
            writer.reset().writeDouble(value);
            assertEquals(Double.toString(value), new String(writer.toByteArray(), StandardCharsets.US_ASCII));
        }
    }

    // Long strings with nothing to escape: the vectorized path's success branch, compared with Jackson.
    @Test
    void writeString_matchesJackson_forLongCleanStrings() {
        var random = new Random(12);
        var writer = JsonWriter.jsonWriter(64);
        for (int i = 0; i < 50_000; i++) {
            var sb = new StringBuilder();
            for (int len = 16 + random.nextInt(120); len > 0; len--) {
                switch (random.nextInt(8)) {
                    case 0 -> sb.append((char) (0xA0 + random.nextInt(0x60)));
                    case 1 -> sb.append((char) (0x800 + random.nextInt(0xD000)));
                    case 2 -> sb.appendCodePoint(0x10000 + random.nextInt(0xFFFFF));
                    default -> sb.append((char) ('#' + random.nextInt(0x5C - '#')));
                }
            }
            var text = sb.toString();
            writer.reset().writeString(text);
            assertEquals(new String(JACKSON.writeAsBytes(text).unwrap(), StandardCharsets.UTF_8),
                         new String(writer.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void writeLong_matchesToString() {
        var random = new Random(3);
        var writer = JsonWriter.jsonWriter(64);
        for (var value : new long[]{0, 9, 10, 99, 100, -1, Long.MAX_VALUE, Long.MIN_VALUE, 1_000_000_000_000_000_000L}) {
            writer.reset()
                  .writeLong(value);
            assertEquals(Long.toString(value), new String(writer.toByteArray(), StandardCharsets.US_ASCII));
        }
        for (int i = 0; i < 100_000; i++) {
            var value = random.nextLong() >> random.nextInt(64);
            writer.reset()
                  .writeLong(value);
            assertEquals(Long.toString(value), new String(writer.toByteArray(), StandardCharsets.US_ASCII));
        }
    }

    // A key the same length as a declared one, differing in one byte, must not bind to it.
    @Test
    void unknownKey_neverBindsToDeclaredField() {
        var json = "{\"customes\":\"x\",\"event\":\"e\",\"seat\":\"s\",\"tier\":\"t\"}";
        BuyRequestFixture.BuyRequestJson.CODEC.decode(json.getBytes(StandardCharsets.UTF_8))
                                          .onSuccess(value -> Assertions.fail("unexpected success: " + value))
                                          .onFailure(cause -> assertInstanceOf(JsonError.MissingField.class, cause))
                                          .onFailure(cause -> assertEquals("customer", ((JsonError.MissingField) cause).field()));
    }

    // Random same-length keys land in a declared name's probe slot about once per table size, so a
    // lookup that trusted the slot or hash without comparing bytes would bind some of them.
    @Test
    void lookup_neverBindsSameLengthUnknownKeys() {
        var names = FieldNames.fieldNames("customer", "event", "seat", "tier");
        var random = new Random(5);
        for (int i = 0; i < 50_000; i++) {
            var declared = names.name(i % names.size());
            var key = new byte[declared.length()];
            for (int k = 0; k < key.length; k++) {
                key[k] = (byte) ('a' + random.nextInt(26));
            }
            if (new String(key, StandardCharsets.US_ASCII).equals(declared)) {
                continue;
            }
            assertEquals(JsonReader.UNKNOWN_FIELD, names.lookup(key, 0, key.length), new String(key, StandardCharsets.US_ASCII));
        }
        for (int i = 0; i < names.size(); i++) {
            var bytes = names.name(i)
                             .getBytes(StandardCharsets.US_ASCII);
            assertEquals(i, names.lookup(bytes, 0, bytes.length));
        }
    }

    // Skipped (unknown) string values are scanned, not decoded, but their escapes are still validated.
    @Test
    void skipValue_validatesEscapesInSkippedStrings() {
        var tail = ",\"customer\":\"c\",\"event\":\"e\",\"seat\":\"s\",\"tier\":\"t\"}";
        for (var good : new String[]{"\"\\u00e9\\n\\\"x\\/\"", "\"plain\"", "[\"a\\\"]\",{\"k\":\"\\\\\"}]", "\"\""}) {
            var json = "{\"zz\":" + good + tail;
            assertTrue(BuyRequestFixture.BuyRequestJson.CODEC.decode(json.getBytes(StandardCharsets.UTF_8)).isSuccess(), json);
        }
        for (var bad : new String[]{"\"\\q\"", "\"\\u12G4\"", "\"\\u12\"", "\"unterminated", "\"ctl\u0001\"", "[\"\\x\"]"}) {
            var json = "{\"zz\":" + bad + tail;
            assertFalse(BuyRequestFixture.BuyRequestJson.CODEC.decode(json.getBytes(StandardCharsets.UTF_8)).isSuccess(), json);
        }
    }

    // The perfect-hash path is only exercised if every real name set actually gets one.
    @Test
    void fieldNames_ofRealPayloads_getPerfectHash() {
        // Name sets of real payloads (the ticketing demo and the ecommerce and catalog examples).
        var sets = java.util.List.of(BuyRequestFixture.BuyRequestJson.NAMES,
                                     FieldNames.fieldNames("booking", "ticket", "seat", "receipt", "amountMinor", "currency"),
                                     FieldNames.fieldNames("id", "name", "priceCents", "currency", "tags"),
                                     FieldNames.fieldNames("orderId", "customerId", "items", "pricing", "payment", "shipment", "status", "createdAt"),
                                     FieldNames.fieldNames("customerId", "items", "shippingAddress", "paymentMethod", "shippingOption", "discountCode"),
                                     FieldNames.fieldNames("shipmentId", "orderId", "trackingNumber", "shippingOption", "destination", "status",
                                                           "estimatedDelivery", "createdAt"),
                                     FieldNames.fieldNames("PENDING", "LABEL_CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION"),
                                     FieldNames.fieldNames("transactionId", "orderId", "amount", "cardType", "maskedCard", "processedAt", "status"));
        sets.forEach(names -> assertTrue(names.isPerfect(), names.name(0)));
    }

    // Every ISO code decodes to the canonical instance, cold and cached; invalid codes fail every time.
    @Test
    void readCurrency_cachedAndUncached_agreeWithGetInstance() {
        for (var round = 0; round < 2; round++) {
            for (var currency : java.util.Currency.getAvailableCurrencies()) {
                var r = reader("\"" + currency.getCurrencyCode() + "\"");
                assertTrue(currency == r.readCurrency(), currency.getCurrencyCode());
                assertFalse(r.hasError());
            }
            for (var bad : new String[]{"\"ABQ\"", "\"usd\"", "\"US\"", "\"USDX\"", "\"U\u00e9D\"", "\"\""}) {
                var r = reader(bad);
                r.readCurrency();
                assertTrue(r.hasError(), bad);
            }
        }
    }

    // Field failures (a value object's factory refusing a value) are collected, not fatal: every refused field
    // is reported, parsing still validates the rest of the input, and a structural error takes precedence.
    @Test
    void accept_collectsEveryRefusedField_andKeepsParsing() {
        var names = FieldNames.fieldNames("a", "b", "c");
        var refusal = org.pragmatica.lang.utils.Causes.cause("refused");
        var reader = reader("{}");

        assertEquals("ok", reader.accept(org.pragmatica.lang.Result.success("ok"), names, 0));
        reader.accept(refusal.result(), names, 1);
        reader.accept(refusal.result(), names, 2);
        assertTrue(reader.hasError());
        reader.result("unused")
              .onSuccess(value -> Assertions.fail("unexpected success: " + value))
              .onFailure(cause -> assertTrue(cause instanceof org.pragmatica.lang.utils.Causes.CompositeCause, cause.message()))
              .onFailure(cause -> assertEquals(2, cause.stream()
                                                      .filter(JsonError.InvalidField.class::isInstance)
                                                      .map(JsonError.InvalidField.class::cast)
                                                      .peek(invalid -> assertEquals(refusal, invalid.origin()))
                                                      .map(JsonError.InvalidField::field)
                                                      .distinct()
                                                      .count()));
    }

    @Test
    void result_prefersStructuralError_overFieldFailures() {
        var names = FieldNames.fieldNames("a");
        var reader = reader("{");

        reader.accept(org.pragmatica.lang.utils.Causes.cause("refused").result(), names, 0);
        reader.beginObject();
        reader.firstField(names);
        reader.result("unused")
              .onSuccess(value -> Assertions.fail("unexpected success: " + value))
              .onFailure(cause -> assertInstanceOf(JsonError.Malformed.class, cause));
    }

    @Test
    void reset_clearsFieldFailures() {
        var names = FieldNames.fieldNames("a");
        var reader = reader("{}");

        reader.accept(org.pragmatica.lang.utils.Causes.cause("refused").result(), names, 0);
        reader.reset("1".getBytes(StandardCharsets.UTF_8));
        assertFalse(reader.hasError());
        assertTrue(reader.result(1).isSuccess());
    }

    @Test
    void decode_rejectsTrailingGarbage() {
        var json = "{\"customer\":\"c\",\"event\":\"e\",\"seat\":\"s\",\"tier\":\"t\"} x";
        assertFalse(BuyRequestFixture.BuyRequestJson.CODEC.decode(json.getBytes(StandardCharsets.UTF_8))
                                                      .isSuccess());
    }

    @Test
    void skipValue_failsBeyondDepthLimit() {
        var deep = "{\"zz\":" + "[".repeat(600) + "]".repeat(600) + ",\"customer\":\"c\",\"event\":\"e\",\"seat\":\"s\",\"tier\":\"t\"}";
        assertFalse(BuyRequestFixture.BuyRequestJson.CODEC.decode(deep.getBytes(StandardCharsets.UTF_8))
                                                      .isSuccess());
    }

    private static String randomString(Random random) {
        var sb = new StringBuilder();
        // Up to 100 chars: both the inline loop and the long-string (vectorized) path are exercised.
        for (int len = random.nextInt(random.nextBoolean() ? 24 : 100); len > 0; len--) {
            switch (random.nextInt(6)) {
                case 0 -> sb.append((char) random.nextInt(0x20));
                case 1 -> sb.append("\"\\/".charAt(random.nextInt(3)));
                case 2 -> sb.append((char) (0x80 + random.nextInt(0x780)));
                case 3 -> sb.append((char) (0x800 + random.nextInt(0xD000)));
                case 4 -> sb.appendCodePoint(0x10000 + random.nextInt(0xFFFFF));
                default -> sb.append((char) (0x20 + random.nextInt(0x5F)));
            }
        }
        return sb.toString();
    }
}
