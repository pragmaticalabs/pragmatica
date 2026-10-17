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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Currency;
import java.util.UUID;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Pull reader over a UTF-8 `byte[]` region, called by generated codecs.
///
/// Error model (the JBCT carve-out for the hot path): no exceptions and no `Result` per value.
/// The first failure is recorded in a single error slot and the cursor jumps to the end of input, so
/// every later read returns a neutral value and every loop terminates. Generated code ignores
/// intermediate values and calls [#result] once to lift the outcome.
@Contract
public final class JsonReader {
    public static final int END_OF_OBJECT = -1;
    public static final int UNKNOWN_FIELD = -2;
    private static final int MAX_SKIP_DEPTH = 512;

    private static final double[] POW10 = {1e0,
                                           1e1,
                                           1e2,
                                           1e3,
                                           1e4,
                                           1e5,
                                           1e6,
                                           1e7,
                                           1e8,
                                           1e9,
                                           1e10,
                                           1e11,
                                           1e12,
                                           1e13,
                                           1e14,
                                           1e15,
                                           1e16,
                                           1e17,
                                           1e18,
                                           1e19,
                                           1e20,
                                           1e21,
                                           1e22};

    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};

    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};

    private static final byte[] NULL = {'n', 'u', 'l', 'l'};

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("XXX");
    private static final UUID NIL_UUID = new UUID(0, 0);

    private byte[] buf;
    private int pos;
    private int end;
    private Cause error;
    private Causes.CompositeCause failures;
    private byte[] scratch = new byte[64];
    // Set by scanString when it stops at a backslash.
    private int escapeAt;
    // Set by scanString: whether the bytes before the special byte it stopped at were all ASCII.
    private boolean scannedAscii;

    private JsonReader() {}

    public static JsonReader jsonReader() {
        return new JsonReader();
    }

    /// Points the reader at `buf[offset, offset + length)` without copying and clears the error slot.
    public JsonReader reset(byte[] buf, int offset, int length) {
        this.buf = buf;
        this.pos = offset;
        this.end = offset + length;
        this.error = null;
        this.failures = null;

        return this;
    }

    public JsonReader reset(byte[] buf) {
        return reset(buf, 0, buf.length);
    }

    /// Any failure: structural (malformed input, missing field) or a field a value object's factory refused.
    /// Generated code checks this before constructing a record, so nothing is built from a refused value.
    public boolean hasError() {
        return error != null || failures != null;
    }

    /// Lifts the decoded value: the structural failure if there is one, otherwise every field failure as one
    /// composite, otherwise `value`.
    public <T> Result<T> result(T value) {
        if (error != null) {
            return error.result();
        }

        return failures == null
               ? Result.success(value)
               : failures.result();
    }

    /// A field's parsed value as a value object's factory judged it. A refusal is recorded (wrapping the
    /// factory's cause) and parsing continues, so one decode reports every refused field, as `Result.all`
    /// does; the returned placeholder is never used because generated code checks [#hasError] first.
    public <T> T accept(Result<T> parsed, FieldNames names, int field) {
        return parsed.fold(cause -> refuse(names.name(field), cause), value -> value);
    }

    /// As [#accept(Result, FieldNames, int)], attributing a refusal to `label` (for a record's own factory,
    /// which runs after all its fields were read).
    public <T> T accept(Result<T> parsed, String label) {
        return parsed.fold(cause -> refuse(label, cause), value -> value);
    }

    private <T> T refuse(String field, Cause cause) {
        var failure = JsonError.InvalidField.invalidField(cause, field);

        failures = failures == null
                   ? Causes.composite().append(failure)
                   : failures.append(failure);

        return null;
    }

    /// Requires that nothing but whitespace follows the top-level value.
    public void finish() {
        skipWhitespace();
        if (pos != end) {
            fail("end of input");
        }
    }

    // ---- objects -------------------------------------------------------------------------------
    public boolean beginObject() {
        return consume('{', "'{'");
    }

    /// Reads the first key of an object (just after `{`). Returns the field index,
    /// [#UNKNOWN_FIELD], or [#END_OF_OBJECT] for `{}`.
    public int firstField(FieldNames names) {
        if (matches(names.size() > 0, names, 0, false)) {
            return 0;
        }

        skipWhitespace();
        if (pos < end && buf[pos] == '}') {
            pos++;

            return END_OF_OBJECT;
        }

        return fieldName(names);
    }

    /// Reads the key following the previous value, trying field `expected` first: the whole
    /// `,"name":` run is compared in one go, with no whitespace skip, no scan and no hash.
    public int nextField(FieldNames names, int expected) {
        var size = names.size();

        if (matches(expected < size, names, expected, true)) {
            return expected;
        }
        // Producers that omit null or default fields skip ahead: try the next two before hashing.
        if (matches(expected + 1 < size, names, expected + 1, true)) {
            return expected + 1;
        }

        if (matches(expected + 2 < size, names, expected + 2, true)) {
            return expected + 2;
        }

        skipWhitespace();
        if (pos >= end) {
            fail("',' or '}'");

            return END_OF_OBJECT;
        }

        var b = buf[pos++];

        if (b == '}') {
            return END_OF_OBJECT;
        }

        if (b != ',') {
            pos--;
            fail("',' or '}'");

            return END_OF_OBJECT;
        }

        skipWhitespace();
        if (matches(expected < names.size(), names, expected, false)) {
            return expected;
        }

        return fieldName(names);
    }

    /// Records the first required field (bit set in `required`, clear in `seen`) as missing. Masks are longs:
    /// generated codecs support records of up to 63 components.
    public void failMissing(FieldNames names, long seen, long required) {
        if (error == null) {
            error = JsonError.MissingField.FACTORY.apply(names.name(Long.numberOfTrailingZeros(required & ~seen)));
            pos = end;
        }
    }

    private boolean matches(boolean inRange, FieldNames names, int index, boolean withComma) {
        if (!inRange) {
            return false;
        }

        var pattern = withComma
                      ? names.next(index)
                      : names.first(index);
        var len = pattern.length;

        if (end - pos >= len && Arrays.equals(buf, pos, pos + len, pattern, 0, len)) {
            pos += len;

            return true;
        }

        return false;
    }

    private int fieldName(FieldNames names) {
        if (!consume('"', "field name")) {
            return END_OF_OBJECT;
        }

        var start = pos;
        var close = scanString(start);

        if (close < 0) {
            return slowFieldName(names);
        }

        pos = close + 1;
        var index = names.lookup(buf, start, close - start);

        if (!consume(':', "':'")) {
            return END_OF_OBJECT;
        }

        return index;
    }

    // A key containing escapes: decode it, then match the decoded bytes.
    private int slowFieldName(FieldNames names) {
        var decoded = decodeEscaped(pos).getBytes(StandardCharsets.UTF_8);

        if (error != null) {
            return END_OF_OBJECT;
        }

        var index = names.lookup(decoded, 0, decoded.length);

        if (!consume(':', "':'")) {
            return END_OF_OBJECT;
        }

        return index;
    }

    // ---- arrays --------------------------------------------------------------------------------
    public boolean beginArray() {
        return consume('[', "'['");
    }

    /// True when the array (just after `[`) has a first element.
    public boolean firstElement() {
        skipWhitespace();
        if (pos < end && buf[pos] == ']') {
            pos++;

            return false;
        }

        return pos < end;
    }

    /// True when another element follows the previous one.
    public boolean nextElement() {
        skipWhitespace();
        if (pos < end) {
            var b = buf[pos++];

            if (b == ',') {
                return true;
            }

            if (b == ']') {
                return false;
            }

            pos--;
        }

        fail("',' or ']'");

        return false;
    }

    // ---- maps (objects with free-form keys) ----------------------------------------------------
    /// True when the object (just after `{`) has a first entry.
    public boolean firstEntry() {
        skipWhitespace();
        if (pos < end && buf[pos] == '}') {
            pos++;

            return false;
        }

        return pos < end;
    }

    /// True when another entry follows the previous one.
    public boolean nextEntry() {
        skipWhitespace();
        if (pos < end) {
            var b = buf[pos++];

            if (b == ',') {
                return true;
            }

            if (b == '}') {
                return false;
            }

            pos--;
        }

        fail("',' or '}'");

        return false;
    }

    /// Reads a map key naming an enum constant, and the colon after it; -1 after recording a failure.
    public int readEnumKey(FieldNames constants) {
        var index = readEnum(constants);

        consume(':', "':'");

        return index;
    }

    /// Reads a map key and the colon after it.
    public String readKey() {
        var key = readString();

        consume(':', "':'");

        return key;
    }

    // ---- scalars -------------------------------------------------------------------------------
    /// Reads an enum constant name; returns its index in `constants`, or -1 after recording a failure.
    public int readEnum(FieldNames constants) {
        if (!consume('"', "enum constant")) {
            return -1;
        }

        var start = pos;
        var close = scanString(start);

        if (close < 0 || error != null) {
            fail("enum constant without escapes");

            return -1;
        }

        pos = close + 1;
        var index = constants.lookup(buf, start, close - start);

        if (index < 0) {
            pos = start;
            fail("one of the declared enum constants");

            return -1;
        }

        return index;
    }

    /// Like [#readEnum], but an undeclared name decodes to `fallback` (an enum's `UNKNOWN` constant) instead
    /// of failing, mirroring the binary codec's contract for enums that declare one.
    public int readEnumOr(FieldNames constants, int fallback) {
        if (!consume('"', "enum constant")) {
            return -1;
        }

        var start = pos;
        var close = scanString(start);

        if (close < 0) {
            // An escaped name can never equal a declared constant.
            decodeEscaped(start);

            return error != null
                   ? -1
                   : fallback;
        }

        if (error != null) {
            return -1;
        }

        pos = close + 1;
        var index = constants.lookup(buf, start, close - start);

        return index < 0
               ? fallback
               : index;
    }

    public UUID readUuid() {
        var start = pos;
        var text = readString();

        if (error != null) {
            return NIL_UUID;
        }

        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            pos = start;
            fail("UUID");

            return NIL_UUID;
        }
    }

    public LocalDate readLocalDate() {
        var start = pos;
        var text = readString();

        if (error != null) {
            return LocalDate.EPOCH;
        }

        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            pos = start;
            fail("ISO-8601 local date");

            return LocalDate.EPOCH;
        }
    }

    public Instant readInstant() {
        skipWhitespace();
        if (pos < end && buf[pos] == '"') {
            var fast = fastInstant(pos + 1);

            if (fast != null) {
                return fast;
            }
        }

        var start = pos;
        var text = readString();

        if (error != null) {
            return Instant.EPOCH;
        }

        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            pos = start;
            fail("ISO-8601 instant");

            return Instant.EPOCH;
        }
    }

    private static final int[] NANO_SCALE = {1_000_000_000,
                                             100_000_000,
                                             10_000_000,
                                             1_000_000,
                                             100_000,
                                             10_000,
                                             1_000,
                                             100,
                                             10,
                                             1};

    /// `YYYY-MM-DDTHH:MM:SS[.f{1,9}]Z"` straight from bytes (T and Z in either case, as ISO_INSTANT
    /// parses case-insensitively). Returns `null` for anything else, including out-of-range fields,
    /// leaving the decision to `Instant.parse`: the fast path never accepts what the JDK would reject.
    private Instant fastInstant(int p) {
        if (end - p < 21) {
            return null;
        }

        var year = digits4(p);
        var month = digits2(p + 5);
        var day = digits2(p + 8);
        var hour = digits2(p + 11);
        var minute = digits2(p + 14);
        var second = digits2(p + 17);
        var t = buf[p + 10];

        if ((year | month | day | hour | minute | second) < 0 || buf[p + 4] != '-' || buf[p + 7] != '-' || (t != 'T' && t != 't') || buf[p + 13] != ':' || buf[p + 16] != ':') {
            return null;
        }

        var q = p + 19;
        var nanos = 0;

        if (buf[q] == '.') {
            var fracStart = ++q;

            while (q < end && q - fracStart < 9 && isDigit(buf[q])) {
                nanos = nanos * 10 + (buf[q++] - '0');
            }

            var count = q - fracStart;

            if (count == 0 || (q < end && isDigit(buf[q]))) {
                return null;
            }

            nanos *= NANO_SCALE[count];
        }

        if (end - q < 2 || (buf[q] != 'Z' && buf[q] != 'z') || buf[q + 1] != '"') {
            return null;
        }

        if (month < 1 || month > 12 || day < 1 || day > monthLength(year, month) || hour > 23 || minute > 59 || second > 59) {
            return null;
        }

        pos = q + 2;

        return Instant.ofEpochSecond(daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second,
                                     nanos);
    }

    private int digits2(int at) {
        var hi = buf[at] - '0';
        var lo = buf[at + 1] - '0';

        return (hi | lo) < 0 || hi > 9 || lo > 9
               ? -1
               : hi * 10 + lo;
    }

    private int digits4(int at) {
        var hi = digits2(at);
        var lo = digits2(at + 2);

        return (hi | lo) < 0
               ? -1
               : hi * 100 + lo;
    }

    private static int monthLength(int year, int month) {
        return switch (month) {
            case 2 -> (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0))
                      ? 29
                      : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    // Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's days_from_civil).
    private static long daysFromCivil(int year, int month, int day) {
        var y = month <= 2
                ? year - 1
                : year;
        var era = (y >= 0
                   ? y
                   : y - 399) / 400;
        var yoe = y - era * 400;
        var doy = (153 * (month + (month > 2
                                   ? -3
                                   : 9)) + 2) / 5 + day - 1;
        var doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;

        return era * 146_097L + doe - 719_468L;
    }

    // Indexed by the 3 uppercase letters of an ISO-4217 code. Racy lazy fill is benign: Currency
    // instances are canonical (every racer stores the same one) and safely published via final fields.
    private static final Currency[] CURRENCIES = new Currency[26 * 26 * 26];

    private static void cacheCurrency(String code, Currency currency) {
        if (code.length() == 3 && code.chars().allMatch(ch -> ch >= 'A' && ch <= 'Z')) {
            CURRENCIES[((code.charAt(0) - 'A') * 26 + code.charAt(1) - 'A') * 26 + code.charAt(2) - 'A'] = currency;
        }
    }

    public Currency readCurrency() {
        skipWhitespace();
        if (end - pos >= 5 && buf[pos] == '"' && buf[pos + 4] == '"') {
            var a = buf[pos + 1] - 'A';
            var b = buf[pos + 2] - 'A';
            var c = buf[pos + 3] - 'A';

            if ((a | b | c) >= 0 && a < 26 && b < 26 && c < 26) {
                var cached = CURRENCIES[(a * 26 + b) * 26 + c];

                if (cached != null) {
                    pos += 5;

                    return cached;
                }
            }
        }

        return readCurrencySlow();
    }

    private Currency readCurrencySlow() {
        var start = pos;
        var text = readString();

        if (error != null) {
            return DEFAULT_CURRENCY;
        }

        try {
            var currency = Currency.getInstance(text);

            cacheCurrency(text, currency);

            return currency;
        } catch (IllegalArgumentException e) {
            pos = start;
            fail("ISO-4217 currency code");

            return DEFAULT_CURRENCY;
        }
    }

    /// Consumes a `null` literal if one is next.
    public boolean isNull() {
        skipWhitespace();
        if (end - pos >= 4 && Arrays.equals(buf, pos, pos + 4, NULL, 0, 4)) {
            pos += 4;

            return true;
        }

        return false;
    }

    public boolean readBoolean() {
        skipWhitespace();
        if (end - pos >= 4 && Arrays.equals(buf, pos, pos + 4, TRUE, 0, 4)) {
            pos += 4;

            return true;
        }

        if (end - pos >= 5 && Arrays.equals(buf, pos, pos + 5, FALSE, 0, 5)) {
            pos += 5;

            return false;
        }

        fail("boolean");

        return false;
    }

    public String readString() {
        if (!consume('"', "string")) {
            return "";
        }

        var start = pos;
        var close = scanString(start);

        if (close < 0) {
            return decodeEscaped(start);
        }

        if (error != null) {
            return "";
        }

        pos = close + 1;

        return scannedAscii
               ? asciiString(start, close - start)
               : new String(buf, start, close - start, StandardCharsets.UTF_8);
    }

    // Exact for pure ASCII: the Latin-1 byte constructor copies without the UTF-8 decoder's checks.
    @SuppressWarnings("deprecation")
    private String asciiString(int start, int length) {
        return new String(buf, 0, start, length);
    }

    public int readInt() {
        var start = pos;
        var value = readLong();

        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            outOfRange(start, "int");

            return 0;
        }

        return (int) value;
    }

    public long readLong() {
        skipWhitespace();
        var start = pos;
        var negative = pos < end && buf[pos] == '-';

        if (negative) {
            pos++;
        }

        var digitsStart = pos;
        // Accumulate as a negative number so Long.MIN_VALUE needs no special case.
        var acc = 0L;

        while (pos < end) {
            var d = buf[pos] - '0';

            if (d < 0 || d > 9) {
                break;
            }

            if (acc < Long.MIN_VALUE / 10 || (acc == Long.MIN_VALUE / 10 && d > 8)) {
                outOfRange(start, "long");

                return 0;
            }

            acc = acc * 10 - d;
            pos++;
        }

        if (!isValidIntegerEnd(digitsStart)) {
            return 0;
        }

        if (!negative && acc == Long.MIN_VALUE) {
            outOfRange(start, "long");

            return 0;
        }

        return negative
               ? acc
               : -acc;
    }

    public double readDouble() {
        skipWhitespace();
        var start = pos;
        var number = scanNumber();

        if (error != null) {
            return 0;
        }
        // Clinger's fast path: an exact mantissa and a power of ten that are both representable.
        if (number.digits <= 15 && number.exponent >= -22 && number.exponent <= 22) {
            var mantissa = (double) number.mantissa;
            var value = number.exponent < 0
                        ? mantissa / POW10[- number.exponent]
                        : mantissa * POW10[number.exponent];

            return number.negative
                   ? -value
                   : value;
        }
        // Eisel-Lemire for exact significands (up to 18 digits kept); NaN means it could not decide.
        if (number.digits <= 18 && number.mantissa != 0) {
            var value = EiselLemire.toDouble(number.mantissa, number.exponent, number.negative);

            if (!Double.isNaN(value)) {
                return value;
            }
        }

        return Double.parseDouble(new String(buf, start, pos - start, StandardCharsets.ISO_8859_1));
    }

    public BigDecimal readDecimal() {
        skipWhitespace();
        var start = pos;
        var number = scanNumber();

        if (error != null) {
            return BigDecimal.ZERO;
        }

        if (number.digits <= 18) {
            var unscaled = number.negative
                           ? -number.mantissa
                           : number.mantissa;

            return BigDecimal.valueOf(unscaled, -number.exponent);
        }

        return new BigDecimal(new String(buf, start, pos - start, StandardCharsets.ISO_8859_1));
    }

    /// Skips one complete value of any kind.
    public void skipValue() {
        skipWhitespace();
        if (pos >= end) {
            fail("value");

            return;
        }

        switch (buf[pos]) {
            case '"' -> skipString();
            case '{', '[' -> skipContainer();
            case 't', 'f' -> readBoolean();
            case 'n' -> skipNull();
            default -> scanNumber();
        }
    }

    // Scans past a string without building it; escapes are validated, not decoded.
    private void skipString() {
        if (!consume('"', "string")) {
            return;
        }

        var i = pos;

        while (true) {
            var close = scanString(i);

            if (error != null) {
                return;
            }

            if (close >= 0) {
                pos = close + 1;

                return;
            }

            i = skipEscape(escapeAt + 1);
            if (i < 0) {
                return;
            }
        }
    }

    // Validates the escape whose letter is at `at`; returns the index after it, or -1 after failing.
    private int skipEscape(int at) {
        if (at >= end) {
            fail("escape");

            return -1;
        }

        return switch (buf[at]) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> at + 1;
            case 'u' -> hex4(at + 1) < 0
                        ? -1
                        : at + 5;
            default -> {
                pos = at;
                fail("valid escape");
                yield -1;
            }
        };
    }

    // ---- internals -----------------------------------------------------------------------------
    /// Mantissa digits (up to 18 kept exactly), decimal exponent and sign of a scanned number.
    private record Scanned(long mantissa, int digits, int exponent, boolean negative) {}

    private static final Scanned ZERO_NUMBER = new Scanned(0, 0, 0, false);

    // Validates JSON number grammar and collects up to 18 significant digits.
    private Scanned scanNumber() {
        var negative = pos < end && buf[pos] == '-';

        if (negative) {
            pos++;
        }

        var intStart = pos;
        var mantissa = 0L;
        var digits = 0;
        var dropped = 0;

        while (pos < end && isDigit(buf[pos])) {
            if (digits < 18) {
                mantissa = mantissa * 10 + (buf[pos] - '0');
                if (mantissa != 0) {
                    digits++;
                }
            } else {
                dropped++;
            }

            pos++;
        }

        if (pos == intStart || (buf[intStart] == '0' && pos - intStart > 1)) {
            fail("number");

            return ZERO_NUMBER;
        }
        // Integer digits beyond the kept 18 scale the mantissa; dropped fraction digits do not.
        var exponent = dropped;

        if (pos < end && buf[pos] == '.') {
            pos++;
            var fracStart = pos;

            while (pos < end && isDigit(buf[pos])) {
                if (digits < 18) {
                    mantissa = mantissa * 10 + (buf[pos] - '0');
                    if (mantissa != 0) {
                        digits++;
                    }

                    exponent--;
                } else {
                    dropped++;
                }

                pos++;
            }

            if (pos == fracStart) {
                fail("digit after '.'");

                return ZERO_NUMBER;
            }
        }

        if (pos < end && (buf[pos] == 'e' || buf[pos] == 'E')) {
            pos++;
            var expNegative = pos < end && buf[pos] == '-';

            if (pos < end && (buf[pos] == '-' || buf[pos] == '+')) {
                pos++;
            }

            var expStart = pos;
            var exp = 0;

            while (pos < end && isDigit(buf[pos])) {
                if (exp < 100_000) {
                    exp = exp * 10 + (buf[pos] - '0');
                }

                pos++;
            }

            if (pos == expStart) {
                fail("exponent digits");

                return ZERO_NUMBER;
            }

            exponent += expNegative
                        ? -exp
                        : exp;
        }
        // digits > 18 means precision was dropped: force the exact slow path.
        return new Scanned(mantissa,
                           dropped > 0
                           ? 19
                           : digits,
                           exponent,
                           negative);
    }

    private boolean isValidIntegerEnd(int digitsStart) {
        if (pos == digitsStart || (buf[digitsStart] == '0' && pos - digitsStart > 1)) {
            fail("integer");

            return false;
        }

        if (pos < end && (buf[pos] == '.' || buf[pos] == 'e' || buf[pos] == 'E')) {
            fail("integer without fraction or exponent");

            return false;
        }

        return true;
    }

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;

    /// High bit set in each byte of `w` that is a quote, a backslash or a control byte. Only the LOWEST
    /// set bit is exact: borrows may flag bytes above a true match, never below it.
    private static long specialBytes(long w) {
        var quote = w ^ 0x2222222222222222L;
        var slash = w ^ 0x5C5C5C5C5C5C5C5CL;

        return ((w - 0x2020202020202020L) & ~w | (quote - ONES) & ~quote | (slash - ONES) & ~slash) & HIGHS;
    }

    /// Index of the closing quote, or -1 if an escape occurs first. Fails on control bytes and EOF.
    /// Scans 8 bytes per step; the last 1-7 bytes of input go byte by byte.
    private int scanString(int from) {
        var i = from;
        var high = 0L;

        for (; i + 8 <= end; i += 8) {
            var w = (long) LONGS.get(buf, i);
            var hits = specialBytes(w);

            if (hits != 0) {
                var index = Long.numberOfTrailingZeros(hits) >>> 3;
                // Only the bytes before the hit belong to the string.
                scannedAscii = ((high | (w & ((1L << (index << 3)) - 1))) & HIGHS) == 0;

                return scanSpecial(i + index);
            }

            high |= w;
        }

        for (; i < end; i++) {
            var b = buf[i];

            if (b == '"' || b == '\\' || (b >= 0 && b < 0x20)) {
                scannedAscii = (high & HIGHS) == 0;

                return scanSpecial(i);
            }

            if (b < 0) {
                high = HIGHS;
            }
        }

        fail("closing '\"'");

        return end;
    }

    private int scanSpecial(int i) {
        var b = buf[i];

        if (b == '"') {
            return i;
        }

        if (b == '\\') {
            escapeAt = i;

            return -1;
        }

        pos = i;
        fail("string character");

        return end;
    }

    int scanStringForTest(int from) {
        return scanString(from);
    }

    boolean scannedAsciiForTest() {
        return scannedAscii;
    }

    /// Byte-at-a-time reference scan, kept as the oracle for [#scanString] tests.
    int scanStringScalar(int from) {
        for (int i = from; i < end; i++) {
            var b = buf[i];

            if (b == '"') {
                return i;
            }

            if (b == '\\') {
                return -1;
            }

            if (b >= 0 && b < 0x20) {
                pos = i;
                fail("string character");

                return end;
            }
        }

        fail("closing '\"'");

        return end;
    }

    // Slow path for strings with escapes; decodes into the reader's reusable UTF-8 scratch array.
    // Sized by growth, never by the remaining input: that made allocation quadratic in document size.
    private String decodeEscaped(int start) {
        var out = scratch;
        var len = 0;
        var i = start;

        while (i < end) {
            // One step writes at most 4 bytes (a \\u escape decoded to UTF-8).
            if (len + 4 > out.length) {
                out = scratch = Arrays.copyOf(out, out.length << 1);
            }

            var b = buf[i++];

            if (b == '"') {
                pos = i;

                return new String(out, 0, len, StandardCharsets.UTF_8);
            }

            if (b != '\\') {
                if (b >= 0 && b < 0x20) {
                    pos = i - 1;
                    fail("string character");

                    return "";
                }

                out[len++] = b;
                continue;
            }

            if (i >= end) {
                break;
            }

            var e = buf[i++];

            switch (e) {
                case '"', '\\', '/' -> out[len++] = e;
                case 'b' -> out[len++] = '\b';
                case 'f' -> out[len++] = '\f';
                case 'n' -> out[len++] = '\n';
                case 'r' -> out[len++] = '\r';
                case 't' -> out[len++] = '\t';
                case 'u' -> {
                    var cp = hex4(i);

                    if (cp < 0) {
                        return "";
                    }

                    i += 4;
                    if (Character.isHighSurrogate((char) cp) && i + 6 <= end && buf[i] == '\\' && buf[i + 1] == 'u') {
                        var low = hex4(i + 2);

                        if (low >= 0 && Character.isLowSurrogate((char) low)) {
                            cp = Character.toCodePoint((char) cp, (char) low);
                            i += 6;
                        }
                    }

                    len = putUtf8(out, len, cp);
                }
                default -> {
                    pos = i - 1;
                    fail("valid escape");

                    return "";
                }
            }
        }

        fail("closing '\"'");

        return "";
    }

    private int hex4(int at) {
        if (end - at < 4) {
            pos = at;
            fail("4 hex digits");

            return -1;
        }

        var value = 0;

        for (int k = at; k < at + 4; k++) {
            var digit = Character.digit(buf[k], 16);

            if (digit < 0) {
                pos = k;
                fail("hex digit");

                return -1;
            }

            value = (value << 4) | digit;
        }

        return value;
    }

    // Escapes never expand: the 6-byte "\\uXXXX" form is longer than its UTF-8 encoding (at most 4).
    private static int putUtf8(byte[] out, int len, int cp) {
        if (cp < 0x80) {
            out[len++] = (byte) cp;
        } else if (cp < 0x800) {
            out[len++] = (byte)(0xC0 | (cp >> 6));
            out[len++] = (byte)(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            var c = Character.isSurrogate((char) cp)
                    ? 0xFFFD
                    : cp;

            out[len++] = (byte)(0xE0 | (c >> 12));
            out[len++] = (byte)(0x80 | ((c >> 6) & 0x3F));
            out[len++] = (byte)(0x80 | (c & 0x3F));
        } else {
            out[len++] = (byte)(0xF0 | (cp >> 18));
            out[len++] = (byte)(0x80 | ((cp >> 12) & 0x3F));
            out[len++] = (byte)(0x80 | ((cp >> 6) & 0x3F));
            out[len++] = (byte)(0x80 | (cp & 0x3F));
        }

        return len;
    }

    private void skipNull() {
        if (!isNull()) {
            fail("null");
        }
    }

    // Structural skip of an object or array; strings are scanned so brackets inside them are ignored.
    private void skipContainer() {
        var depth = 0;

        while (pos < end) {
            var b = buf[pos];

            switch (b) {
                case '{', '[' -> {
                    if (++depth > MAX_SKIP_DEPTH) {
                        fail("nesting depth <= " + MAX_SKIP_DEPTH);

                        return;
                    }

                    pos++;
                }
                case '}', ']' -> {
                    pos++;
                    if (--depth == 0) {
                        return;
                    }
                }
                case '"' -> skipString();
                default -> pos++;
            }
        }

        fail("end of container");
    }

    private boolean consume(char expected, String description) {
        skipWhitespace();
        if (pos < end && buf[pos] == expected) {
            pos++;

            return true;
        }

        fail(description);

        return false;
    }

    private void skipWhitespace() {
        while (pos < end) {
            var b = buf[pos];

            if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                return;
            }

            pos++;
        }
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private void fail(String expected) {
        if (error == null) {
            error = JsonError.Malformed.FACTORY.apply(pos, expected);
        }

        pos = end;
    }

    private void outOfRange(int position, String type) {
        if (error == null) {
            error = JsonError.OutOfRange.FACTORY.apply(position, type);
        }

        pos = end;
    }
}
