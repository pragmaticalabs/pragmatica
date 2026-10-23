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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.JsonError;
import org.pragmatica.serialization.json.JsonOptions;


/// Pull reader over a UTF-8 `byte[]` region, called by generated codecs.
///
/// Error model (the JBCT carve-out for the hot path): no exceptions and no `Result` per value.
/// The first failure is recorded in a single error slot and the cursor jumps to the end of input, so
/// every later read returns a neutral value and every loop terminates. Generated code ignores
/// intermediate values and calls [#result] once to lift the outcome.
///
/// Options ([JsonOptions], set by [#reset(byte\[\], int, int, JsonOptions)]) cost nothing on the fast scan: a limit
/// is at most one comparison per string, number or container (the depth limit is the comparison that was always
/// there), the document size is checked once in `reset`, and the other options are consulted only on their own rare
/// path (an unknown key, a repeated key).
@Contract
public final class JsonReader {
    public static final int END_OF_OBJECT = -1;
    public static final int UNKNOWN_FIELD = -2;
    /// Deepest nesting of objects and arrays accepted by default ([JsonOptions#maxDepth]), counted per container, as
    /// Jackson's `StreamReadConstraints` does. One limit covers generated readers (which recurse once per container)
    /// and skipped values (which nest in an explicit stack, on top of the reader's current depth). 1000 rather than Jackson's 500: a recursive record
    /// costs two containers per level (`{"children":[...]}`), and 400 such levels must decode.
    public static final int MAX_DEPTH = 1000;

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

    private static final byte[] QUOTED_NAN = {'"', 'N', 'a', 'N', '"'};

    private static final byte[] QUOTED_INFINITY = {'"', 'I', 'n', 'f', 'i', 'n', 'i', 't', 'y', '"'};

    private static final byte[] QUOTED_NEGATIVE_INFINITY = {'"', '-', 'I', 'n', 'f', 'i', 'n', 'i', 't', 'y', '"'};

    // Beyond any int: an exponent this large is already out of range for every target type.
    private static final long EXPONENT_CAP = 1L << 40;
    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("XXX");
    private static final UUID NIL_UUID = new UUID(0, 0);

    private byte[] buf;
    private int pos;
    private int end;
    // Start of the input region: failure reporting re-scans from here to name the enclosing field.
    private int begin;
    // Objects and arrays entered by generated readers and not yet closed.
    private int depth;
    // Container kinds of skipped levels 64 and deeper (levels below 64 live in a local bit set); allocated on first use.
    private boolean[] deepObjects;
    private Cause error;
    private Causes.CompositeCause failures;
    private byte[] scratch = new byte[64];
    // Set by scanString when it stops at a backslash.
    private int escapeAt;
    // Set by scanString: whether the bytes before the special byte it stopped at were all ASCII.
    private boolean scannedAscii;
    // The options of the current input (see reset).
    private int maxDepth = MAX_DEPTH;
    private int maxStringLength = Integer.MAX_VALUE;
    private int maxNumberLength = Integer.MAX_VALUE;
    // min(maxNumberLength, maxStringLength): a number whose text is measured (see fitsNumber) and is no longer than
    // this passes both of its limits, so only a longer one pays for counting its digits.
    private int numberTextLimit = Integer.MAX_VALUE;
    private boolean failOnUnknown;
    private boolean failOnDuplicate;
    // Opening quote of the last map key read: where a repeated key is reported.
    private int keyAt;
    // Keys of each level of the value being skipped, kept only while failOnDuplicate is set; allocated on first use.
    private List<Set<String>> skippedKeys;

    private JsonReader() {}

    public static JsonReader jsonReader() {
        return new JsonReader();
    }

    /// Points the reader at `buf[offset, offset + length)` without copying and clears the error slot; reads under
    /// [JsonOptions#DEFAULTS].
    public JsonReader reset(byte[] buf, int offset, int length) {
        return reset(buf, offset, length, JsonOptions.DEFAULTS);
    }

    /// As [#reset(byte\[\], int, int)], reading under `options`. A region larger than `options.maxDocumentSize()` is
    /// refused here, before anything is read.
    public JsonReader reset(byte[] buf, int offset, int length, JsonOptions options) {
        this.buf = buf;
        this.pos = offset;
        this.begin = offset;
        this.end = offset + length;
        this.depth = 0;
        this.error = null;
        this.failures = null;
        this.maxDepth = options.maxDepth();
        this.maxStringLength = options.maxStringLength();
        this.maxNumberLength = options.maxNumberLength();
        this.numberTextLimit = Math.min(maxNumberLength, maxStringLength);
        this.failOnUnknown = options.failOnUnknown();
        this.failOnDuplicate = options.failOnDuplicate();
        if (length > options.maxDocumentSize()) {
            error = JsonError.DocumentTooLarge.FACTORY.apply(options.maxDocumentSize(),
                                                             offset + options.maxDocumentSize());
            pos = end;
        }

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
    /// Enters an object; [#firstField] / [#nextField] (records) or [#firstEntry] / [#nextEntry] (maps) leave it.
    public boolean beginObject() {
        return consumeValue('{', "'{'") && enter();
    }

    // Counts a container just opened against the depth limit.
    private boolean enter() {
        if (++depth > maxDepth) {
            pos--;
            nestingTooDeep();

            return false;
        }

        return true;
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
            depth--;

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
            depth--;

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
    /// generated codecs support records of up to 64 components (one bit each; the analyzer refuses wider ones).
    public void failMissing(FieldNames names, long seen, long required) {
        if (error == null) {
            error = JsonError.MissingField.FACTORY.apply(names.name(Long.numberOfTrailingZeros(required & ~seen)));
            pos = end;
        }
    }

    /// Field `field` was already seen in this object (generated code tests its bit in the seen mask): its value is
    /// read again and the last one kept, unless the options refuse duplicates. Called just after the key's colon.
    public void repeatedField(FieldNames names, int field) {
        if (failOnDuplicate) {
            var at = keyStart();

            duplicateKey(names.name(field), at);
        }
    }

    /// Skips the value of a member no component declares (its key just read), unless the options refuse unknown
    /// members. `unknown` holds the unknown keys of this object seen so far, kept (and returned, allocated on first
    /// use) only while the options refuse duplicates; generated code keeps it in a local that starts as `null`, which
    /// is what scopes it to one object.
    public Set<String> skipUnknown(Set<String> unknown) {
        if (failOnUnknown) {
            var at = keyStart();

            unknownField(keyText(at), at);

            return unknown;
        }

        var keys = failOnDuplicate
                   ? withUnknownKey(unknown)
                   : unknown;

        skipValue();

        return keys;
    }

    private Set<String> withUnknownKey(Set<String> unknown) {
        var keys = unknown == null
                   ? new HashSet<String>()
                   : unknown;
        var at = keyStart();
        var key = keyText(at);

        if (!keys.add(key)) {
            duplicateKey(key, at);
        }

        return keys;
    }

    /// The map key just read repeats an earlier key of the same map (its `put` replaced a value): the last value is
    /// kept, unless the options refuse duplicates.
    public void repeatedKey() {
        if (failOnDuplicate) {
            duplicateKey(keyText(keyAt), keyAt);
        }
    }

    // The opening quote of the key whose colon ends just before pos. Scans backwards over whitespace to the closing
    // quote, then to the first quote not escaped by an odd run of backslashes; only a rare path pays for it.
    private int keyStart() {
        var i = pos - 2;

        while (buf[i] != '"') {
            i--;
        }

        i--;
        while (buf[i] != '"' || isEscaped(i)) {
            i--;
        }

        return i;
    }

    private boolean isEscaped(int quote) {
        var backslashes = 0;

        for (int i = quote - 1; i >= begin && buf[i] == '\\'; i--) {
            backslashes++;
        }

        return (backslashes & 1) != 0;
    }

    // The decoded text of the (already validated) string whose opening quote is at `open`; the cursor is kept.
    private String keyText(int open) {
        var saved = pos;
        var text = decodeEscaped(open + 1);

        pos = saved;

        return text;
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

        var index = lookupString(names);

        if (index == -1 || !consume(':', "':'")) {
            return END_OF_OBJECT;
        }

        return index;
    }

    /// Reads the rest of a string whose opening quote was consumed and looks it up in `names`: its index,
    /// [#UNKNOWN_FIELD] when undeclared, or -1 after recording a failure. A name written with escapes is decoded
    /// first (the slow path, taken only when the raw bytes hold a backslash), so `"\u0041"` matches `A`.
    private int lookupString(FieldNames names) {
        var start = pos;
        var close = scanString(start);

        if (error != null) {
            return -1;
        }

        if (close < 0) {
            var text = decodeEscaped(start);

            if (error != null || !fitsString(text.length(), start - 1)) {
                return -1;
            }

            var decoded = text.getBytes(StandardCharsets.UTF_8);

            return names.lookup(decoded, 0, decoded.length);
        }

        if (!scannedAscii && !isValidUtf8(start, close)) {
            return -1;
        }
        // UTF-8 never takes fewer bytes than UTF-16 takes characters: only a string over the limit in bytes is counted.
        if (close - start > maxStringLength && !fitsString(utf16Length(buf, start, close), start - 1)) {
            return -1;
        }

        pos = close + 1;

        return names.lookup(buf, start, close - start);
    }

    // ---- arrays --------------------------------------------------------------------------------
    /// Enters an array; [#firstElement] / [#nextElement] leave it.
    public boolean beginArray() {
        return consumeValue('[', "'['") && enter();
    }

    /// True when the array (just after `[`) has a first element.
    public boolean firstElement() {
        skipWhitespace();
        if (pos < end && buf[pos] == ']') {
            pos++;
            depth--;

            return false;
        }

        if (pos >= end) {
            fail("value or ']'");

            return false;
        }

        return true;
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
                depth--;

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
            depth--;

            return false;
        }

        if (pos >= end) {
            fail("map key or '}'");

            return false;
        }

        return true;
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
                depth--;

                return false;
            }

            pos--;
        }

        fail("',' or '}'");

        return false;
    }

    /// Reads a map key naming an enum constant, and the colon after it; -1 after recording a failure.
    public int readEnumKey(FieldNames constants) {
        skipWhitespace();
        var start = pos;

        keyAt = start;
        var index = consume('"', "map key")
                    ? declared(lookupString(constants), start)
                    : -1;

        consume(':', "':'");

        return index;
    }

    /// Reads a map key and the colon after it.
    public String readKey() {
        skipWhitespace();
        keyAt = pos;
        var key = consume('"', "map key")
                  ? stringBody()
                  : "";

        consume(':', "':'");

        return key;
    }

    // ---- scalars -------------------------------------------------------------------------------
    /// Reads an enum constant name; returns its index in `constants`, or -1 after recording a failure.
    public int readEnum(FieldNames constants) {
        skipWhitespace();
        var start = pos;

        return consumeValue('"', "enum constant")
               ? declared(lookupString(constants), start)
               : -1;
    }

    // A looked-up enum constant index, failing at `start` when the name is not declared.
    private int declared(int index, int start) {
        if (index == UNKNOWN_FIELD) {
            pos = start;
            fail("one of the declared enum constants");

            return -1;
        }

        return index;
    }

    /// Like [#readEnum], but an undeclared name decodes to `fallback` (an enum's `UNKNOWN` constant) instead
    /// of failing, mirroring the binary codec's contract for enums that declare one.
    public int readEnumOr(FieldNames constants, int fallback) {
        if (!consumeValue('"', "enum constant")) {
            return -1;
        }

        var index = lookupString(constants);

        return index == UNKNOWN_FIELD
               ? fallback
               : index;
    }

    /// Only the canonical 36-character form `8-4-4-4-12` (hex digits in either case), as RFC 9562 writes it.
    /// `UUID.fromString` alone would also take `"1-1-1-1-1"`.
    public UUID readUuid() {
        skipWhitespace();
        if (end - pos >= 38 && buf[pos] == '"' && buf[pos + 37] == '"') {
            var fast = fastUuid(pos + 1);

            if (fast != null) {
                return fast;
            }
        }

        var start = pos;
        var text = readString();

        if (error != null) {
            return NIL_UUID;
        }

        if (!isCanonicalUuid(text)) {
            pos = start;
            fail("UUID in canonical 8-4-4-4-12 form");

            return NIL_UUID;
        }

        return UUID.fromString(text);
    }

    // Straight from bytes; null for anything else (escapes included), leaving the decision to the slow path.
    private UUID fastUuid(int p) {
        if (buf[p + 8] != '-' || buf[p + 13] != '-' || buf[p + 18] != '-' || buf[p + 23] != '-') {
            return null;
        }

        var a = hexDigits(p, 8);
        var b = hexDigits(p + 9, 4);
        var c = hexDigits(p + 14, 4);
        var d = hexDigits(p + 19, 4);
        var e = hexDigits(p + 24, 12);

        if ((a | b | c | d | e) < 0) {
            return null;
        }

        pos = p + 37;

        return new UUID(a << 32 | b << 16 | c, d << 48 | e);
    }

    // `count` hex digits at `at` as a number, or -1 if any byte is not a hex digit.
    private long hexDigits(int at, int count) {
        var value = 0L;

        for (int k = at; k < at + count; k++) {
            var digit = Character.digit(buf[k], 16);

            if (digit < 0) {
                return -1;
            }

            value = (value << 4) | digit;
        }

        return value;
    }

    private static boolean isCanonicalUuid(String text) {
        if (text.length() != 36) {
            return false;
        }

        for (int i = 0; i < 36; i++) {
            var c = text.charAt(i);
            var hyphen = i == 8 || i == 13 || i == 18 || i == 23;

            if (hyphen
                ? c != '-'
                : c >= 0x80 || Character.digit(c, 16) < 0) {
                return false;
            }
        }

        return true;
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

        failValue("boolean");

        return false;
    }

    public String readString() {
        return consumeValue('"', "string")
               ? stringBody()
               : "";
    }

    // The rest of a string whose opening quote was consumed, within the string length limit.
    private String stringBody() {
        var start = pos;
        var text = stringContent(start);

        return fitsString(text.length(), start - 1)
               ? text
               : "";
    }

    // Non-ASCII bytes are validated as UTF-8 first: the JDK decoder would silently turn malformed sequences into
    // U+FFFD.
    private String stringContent(int start) {
        var close = scanString(start);

        if (close < 0) {
            return decodeEscaped(start);
        }

        if (error != null) {
            return "";
        }

        if (scannedAscii) {
            pos = close + 1;

            return asciiString(start, close - start);
        }

        if (!isValidUtf8(start, close)) {
            return "";
        }

        pos = close + 1;

        return new String(buf, start, close - start, StandardCharsets.UTF_8);
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

        if (!isValidIntegerEnd(digitsStart, negative)) {
            return 0;
        }
        // The text is never shorter than its digits: only a number over the limit in characters counts them.
        if (pos - start > numberTextLimit && !fitsNumber(pos - digitsStart, true, pos - start, start)) {
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

    /// A JSON number, or one of the three strings Jackson writes for non-finite values: `"NaN"`, `"Infinity"`,
    /// `"-Infinity"` (exactly these; no other quoted form).
    public double readDouble() {
        skipWhitespace();
        if (pos < end && buf[pos] == '"') {
            return readNonFinite();
        }

        var start = pos;
        var number = scanNumber();

        if (error != null) {
            return 0;
        }
        // Clinger's fast path: an exact mantissa and a power of ten that are both representable.
        if (number.digits <= 15 && number.exponent >= -22 && number.exponent <= 22) {
            var mantissa = (double) number.mantissa;
            var value = number.exponent < 0
                        ? mantissa / POW10[(int) - number.exponent]
                        : mantissa * POW10[(int) number.exponent];

            return number.negative
                   ? -value
                   : value;
        }
        // Eisel-Lemire for exact significands (up to 18 digits kept); NaN means it could not decide. Its table spans
        // far less than +-400, so a larger exponent goes straight to the JDK, which yields 0 or infinity.
        if (number.digits <= 18 && number.mantissa != 0 && Math.abs(number.exponent) <= 400) {
            var value = EiselLemire.toDouble(number.mantissa, (int) number.exponent, number.negative);

            if (!Double.isNaN(value)) {
                return value;
            }
        }

        return Double.parseDouble(new String(buf, start, pos - start, StandardCharsets.ISO_8859_1));
    }

    private double readNonFinite() {
        if (literal(QUOTED_NAN)) {
            return Double.NaN;
        }

        if (literal(QUOTED_INFINITY)) {
            return Double.POSITIVE_INFINITY;
        }

        if (literal(QUOTED_NEGATIVE_INFINITY)) {
            return Double.NEGATIVE_INFINITY;
        }

        fail("number, \"NaN\", \"Infinity\" or \"-Infinity\"");

        return 0;
    }

    /// Exact: the exponent is accumulated in full, so `1e1234567` is `1E+1234567`. A number whose scale does not fit
    /// an `int` (beyond what `BigDecimal` represents) fails as [JsonError.OutOfRange].
    public BigDecimal readDecimal() {
        skipWhitespace();
        var start = pos;
        var number = scanNumber();

        if (error != null) {
            return BigDecimal.ZERO;
        }

        if (number.digits > 18) {
            return parseDecimal(start);
        }
        // The scale is -exponent and must fit an int, exactly the bound BigDecimal's text constructor enforces.
        if (number.exponent < - Integer.MAX_VALUE || number.exponent > - (long) Integer.MIN_VALUE) {
            outOfRange(start, "BigDecimal");

            return BigDecimal.ZERO;
        }

        var unscaled = number.negative
                       ? -number.mantissa
                       : number.mantissa;

        return BigDecimal.valueOf(unscaled, (int) - number.exponent);
    }

    // JDK-boundary leaf: BigDecimal's text constructor documents NumberFormatException, here only for scale overflow.
    @SuppressWarnings("JBCT-EX-03")
    private BigDecimal parseDecimal(int start) {
        try {
            return new BigDecimal(new String(buf, start, pos - start, StandardCharsets.ISO_8859_1));
        } catch (NumberFormatException exponentOverflow) {
            outOfRange(start, "BigDecimal");

            return BigDecimal.ZERO;
        }
    }

    /// Skips one complete value of any kind, validating it against the full RFC 8259 grammar: brackets match,
    /// members are `"key":value` pairs separated by commas, literals are exactly `true` / `false` / `null`, numbers
    /// follow the number grammar, strings hold valid escapes, no control bytes and well-formed UTF-8. Iterative:
    /// open containers live in a bit stack, never on the call stack, and nesting is bounded by the depth limit together
    /// with the reader's current depth. Keys and numbers are held to the options' limits, and keys to being unique in
    /// their object when duplicates are refused, as Jackson holds every token it parses; a skipped string value is not
    /// measured, as Jackson does not measure one. With [#finish] it validates a whole document.
    public void skipValue() {
        skipWhitespace();
        if (pos < end && (buf[pos] == '{' || buf[pos] == '[')) {
            skipContainer();

            return;
        }

        skipScalar();
    }

    private void skipScalar() {
        if (pos >= end) {
            fail("value");

            return;
        }

        switch (buf[pos]) {
            case '"' -> skipString();
            case 't' -> skipLiteral(TRUE);
            case 'f' -> skipLiteral(FALSE);
            case 'n' -> skipLiteral(NULL);
            default -> skipNumber();
        }
    }

    private void skipLiteral(byte[] literal) {
        if (!literal(literal)) {
            fail(new String(literal, StandardCharsets.ISO_8859_1));
        }
    }

    // Consumes `bytes` if they come next.
    private boolean literal(byte[] bytes) {
        var len = bytes.length;

        if (end - pos >= len && Arrays.equals(buf, pos, pos + len, bytes, 0, len)) {
            pos += len;

            return true;
        }

        return false;
    }

    // Number grammar only: nothing is accumulated. A skipped number is held to the number limit, as Jackson holds every
    // number token it parses, and its whole text to the string limit (see fitsNumber).
    private void skipNumber() {
        var start = pos;

        if (pos < end && buf[pos] == '-') {
            pos++;
        }

        var intStart = pos;

        if (!skipDigits() || (buf[intStart] == '0' && pos - intStart > 1)) {
            pos = intStart;
            fail("number");

            return;
        }

        var digits = pos - intStart;
        var fraction = pos < end && buf[pos] == '.';

        if (fraction) {
            pos++;
            var fracStart = pos;

            if (!skipDigits()) {
                fail("digit after '.'");

                return;
            }

            digits += pos - fracStart;
        }

        var exponent = pos < end && (buf[pos] == 'e' || buf[pos] == 'E');

        if (exponent) {
            pos++;
            if (pos < end && (buf[pos] == '-' || buf[pos] == '+')) {
                pos++;
            }

            var expStart = pos;

            if (!skipDigits()) {
                fail("exponent digits");

                return;
            }

            digits += pos - expStart;
        }

        if (pos - start > numberTextLimit) {
            fitsNumber(digits, true, pos - start, start);
        }
    }

    private boolean skipDigits() {
        var from = pos;

        while (pos < end && isDigit(buf[pos])) {
            pos++;
        }

        return pos > from;
    }

    // Scans past a string without building it; escapes and UTF-8 are validated, not decoded.
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
            // Escapes are ASCII, so validating the runs between them validates the whole string.
            if (!scannedAscii && !isValidUtf8(i,
                                              close < 0
                                              ? escapeAt
                                              : close)) {
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

    // An object or array, as a state machine: `expectValue` is false just after a value. Level n's kind is bit n of
    // `objects` (set: object) below 64, and deepObjects[n] from 64 on.
    private void skipContainer() {
        var objects = 0L;
        var level = 0;
        var expectValue = true;

        while (error == null) {
            skipWhitespace();
            if (pos >= end) {
                fail(expectValue
                     ? "value"
                     : "',' or end of container");

                return;
            }

            var b = buf[pos];

            if (!expectValue) {
                level = afterValue(b, level, isObjectLevel(objects, level));
                expectValue = b == ',';
            } else if (b == '{' || b == '[') {
                if (depth + ++level > maxDepth) {
                    nestingTooDeep();

                    return;
                }

                objects = markLevel(objects, level, b == '{');
                expectValue = openContainer(b == '{', level);
                level -= expectValue
                         ? 0
                         : 1;
            } else {
                skipScalar();
                expectValue = false;
            }

            if (level == 0 && !expectValue) {
                return;
            }
        }
    }

    // Just after `{` or `[` (at pos): false when the container closed at once, true when a value follows (an
    // object's first key and colon already consumed). An object at `level` starts with no keys seen.
    private boolean openContainer(boolean object, int level) {
        pos++;
        if (object && failOnDuplicate) {
            levelKeys(level).clear();
        }

        skipWhitespace();
        if (pos < end && buf[pos] == (object
                                      ? '}'
                                      : ']')) {
            pos++;

            return false;
        }

        if (object) {
            skipKey(level);
        }

        return true;
    }

    // `b` (at pos) follows a value at `level`: a comma (and, in an object, the next key) or the closing bracket.
    // Returns the level after it.
    private int afterValue(byte b, int level, boolean object) {
        if (b == ',') {
            pos++;
            if (object) {
                skipKey(level);
            }

            return level;
        }

        if (b == (object
                  ? '}'
                  : ']')) {
            pos++;

            return level - 1;
        }

        fail(object
             ? "',' or '}'"
             : "',' or ']'");

        return level;
    }

    // A key of the skipped object at `level`, held to the string length limit (Jackson checks every key it parses) and,
    // while duplicates are refused, to being new in its object.
    private void skipKey(int level) {
        skipWhitespace();
        if (pos >= end || buf[pos] != '"') {
            fail("field name");

            return;
        }

        var open = pos;

        skipString();
        if (error == null && pos - open - 2 > maxStringLength) {
            fitsString(keyText(open).length(), open);
        }

        if (error == null && failOnDuplicate) {
            noteSkippedKey(level, open);
        }

        consume(':', "':'");
    }

    private void noteSkippedKey(int level, int open) {
        var key = keyText(open);

        if (!levelKeys(level).add(key)) {
            duplicateKey(key, open);
        }
    }

    private Set<String> levelKeys(int level) {
        if (skippedKeys == null) {
            skippedKeys = new ArrayList<>();
        }

        while (skippedKeys.size() <= level) {
            skippedKeys.add(new HashSet<>());
        }

        return skippedKeys.get(level);
    }

    private long markLevel(long objects, int level, boolean object) {
        if (level < 64) {
            return object
                   ? objects | 1L << level
                   : objects & ~(1L << level);
        }

        if (deepObjects == null || deepObjects.length <= level) {
            deepObjects = deepObjects == null
                          ? new boolean[maxDepth + 1]
                          : Arrays.copyOf(deepObjects, maxDepth + 1);
        }

        deepObjects[level] = object;

        return objects;
    }

    private boolean isObjectLevel(long objects, int level) {
        return level < 64
               ? (objects >>> level & 1) != 0
               : deepObjects[level];
    }

    // Validates the escape whose letter is at `at`; returns the index after it, or -1 after failing.
    private int skipEscape(int at) {
        if (at >= end) {
            fail("escape");

            return -1;
        }

        return switch (buf[at]) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> at + 1;
            case 'u' -> skipUnicodeEscape(at + 1);
            default -> {
                pos = at;
                fail("valid escape");
                yield -1;
            }
        };
    }

    // The 4 hex digits start at `at`; a surrogate must be a high one followed by an escaped low one.
    private int skipUnicodeEscape(int at) {
        var cp = hex4(at);

        if (cp < 0) {
            return -1;
        }

        if (!Character.isSurrogate((char) cp)) {
            return at + 4;
        }

        return pairedLow(cp, at + 4) < 0
               ? -1
               : at + 10;
    }

    // The low surrogate escaped at `at` that completes `high`; -1 after failing when `high` is a lone surrogate.
    // Lone surrogates are rejected: they have no UTF-8 encoding, so no decoded string can carry one.
    private int pairedLow(int high, int at) {
        if (Character.isHighSurrogate((char) high) && at + 6 <= end && buf[at] == '\\' && buf[at + 1] == 'u') {
            var low = hex4(at + 2);

            if (low >= 0 && Character.isLowSurrogate((char) low)) {
                return low;
            }
        }

        pos = at - 6;
        fail("surrogate pair");

        return -1;
    }

    // ---- internals -----------------------------------------------------------------------------
    /// Mantissa digits (up to 18 kept exactly), decimal exponent and sign of a scanned number.
    private record Scanned(long mantissa, int digits, long exponent, boolean negative) {}

    private static final Scanned ZERO_NUMBER = new Scanned(0, 0, 0, false);

    // Validates JSON number grammar and collects up to 18 significant digits.
    private Scanned scanNumber() {
        var start = pos;
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

        if (pos == intStart) {
            if (negative) {
                fail("number");
            } else {
                failValue("number");
            }

            return ZERO_NUMBER;
        }

        if (buf[intStart] == '0' && pos - intStart > 1) {
            fail("number");

            return ZERO_NUMBER;
        }
        // Integer digits beyond the kept 18 scale the mantissa; dropped fraction digits do not.
        var exponent = (long) dropped;
        // Every digit of the text (integer, fraction and exponent), for the number length limit.
        var allDigits = pos - intStart;
        var fraction = pos < end && buf[pos] == '.';

        if (fraction) {
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

            allDigits += pos - fracStart;
        }

        var scientific = pos < end && (buf[pos] == 'e' || buf[pos] == 'E');

        if (scientific) {
            pos++;
            var expNegative = pos < end && buf[pos] == '-';

            if (pos < end && (buf[pos] == '-' || buf[pos] == '+')) {
                pos++;
            }

            var expStart = pos;
            var exp = 0L;
            // Exact up to the cap, which already exceeds every target's range.
            while (pos < end && isDigit(buf[pos])) {
                if (exp < EXPONENT_CAP) {
                    exp = exp * 10 + (buf[pos] - '0');
                }

                pos++;
            }

            if (pos == expStart) {
                fail("exponent digits");

                return ZERO_NUMBER;
            }

            allDigits += pos - expStart;
            exponent += expNegative
                        ? -exp
                        : exp;
        }

        var integer = !fraction && !scientific;
        var textLimit = integer
                        ? numberTextLimit
                        : maxNumberLength;

        if (pos - start > textLimit && !fitsNumber(allDigits, integer, pos - start, start)) {
            return ZERO_NUMBER;
        }
        // digits > 18 means precision was dropped: force the exact slow path.
        return new Scanned(mantissa,
                           dropped > 0
                           ? 19
                           : digits,
                           exponent,
                           negative);
    }

    private boolean isValidIntegerEnd(int digitsStart, boolean negative) {
        if (pos == digitsStart && !negative) {
            failValue("integer");

            return false;
        }

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
        var high = 0;

        while (i < end) {
            // One step writes at most 4 bytes (a \\u escape decoded to UTF-8).
            if (len + 4 > out.length) {
                out = scratch = Arrays.copyOf(out, out.length << 1);
            }

            var b = buf[i++];

            if (b == '"') {
                return decoded(out, len, high < 0, i);
            }

            if (b != '\\') {
                if (b >= 0 && b < 0x20) {
                    pos = i - 1;
                    fail("string character");

                    return "";
                }

                high |= b;
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
                    if (Character.isSurrogate((char) cp)) {
                        var low = pairedLow(cp, i);

                        if (low < 0) {
                            return "";
                        }

                        cp = Character.toCodePoint((char) cp, (char) low);
                        i += 6;
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

    // The decoded bytes as a string; raw non-ASCII bytes were copied as they came, so they are validated first.
    // Escapes always decode to well-formed UTF-8: lone surrogates were rejected while decoding.
    private String decoded(byte[] out, int len, boolean sawNonAscii, int after) {
        var invalid = sawNonAscii
                      ? invalidUtf8(out, 0, len)
                      : -1;

        if (invalid >= 0) {
            fail("valid UTF-8 in string");

            return "";
        }

        pos = after;

        return new String(out, 0, len, StandardCharsets.UTF_8);
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
            out[len++] = (byte)(0xE0 | (cp >> 12));
            out[len++] = (byte)(0x80 | ((cp >> 6) & 0x3F));
            out[len++] = (byte)(0x80 | (cp & 0x3F));
        } else {
            out[len++] = (byte)(0xF0 | (cp >> 18));
            out[len++] = (byte)(0x80 | ((cp >> 12) & 0x3F));
            out[len++] = (byte)(0x80 | ((cp >> 6) & 0x3F));
            out[len++] = (byte)(0x80 | (cp & 0x3F));
        }

        return len;
    }

    // ---- UTF-8 ---------------------------------------------------------------------------------
    // Well-formed per RFC 3629 (Unicode Table 3-7); fails at the first offending byte otherwise.
    private boolean isValidUtf8(int from, int to) {
        var invalid = invalidUtf8(buf, from, to);

        if (invalid < 0) {
            return true;
        }

        pos = invalid;
        fail("valid UTF-8");

        return false;
    }

    /// Index of the first byte of `bytes[from, to)` that breaks UTF-8 well-formedness (RFC 3629): overlong forms,
    /// UTF-16 surrogates (ED A0-BF), code points past U+10FFFF and truncated sequences; -1 when well-formed.
    static int invalidUtf8(byte[] bytes, int from, int to) {
        var i = from;

        while (i < to) {
            var lead = bytes[i] & 0xFF;

            if (lead < 0x80) {
                i++;
                continue;
            }

            var length = sequenceLength(lead);

            if (length == 0 || to - i < length || !secondByteFits(lead, bytes[i + 1] & 0xFF)) {
                return i;
            }

            for (int k = i + 2; k < i + length; k++) {
                if ((bytes[k] & 0xC0) != 0x80) {
                    return i;
                }
            }

            i += length;
        }

        return -1;
    }

    // Bytes in the sequence a lead byte starts; 0 for a continuation byte or a lead that is never valid (C0, C1, F5+).
    private static int sequenceLength(int lead) {
        if (lead >= 0xC2 && lead <= 0xDF) {
            return 2;
        }

        if (lead >= 0xE0 && lead <= 0xEF) {
            return 3;
        }

        return lead >= 0xF0 && lead <= 0xF4
               ? 4
               : 0;
    }

    // The second byte's range depends on the lead: this is where overlongs, surrogates and > U+10FFFF are excluded.
    private static boolean secondByteFits(int lead, int second) {
        return switch (lead) {
            case 0xE0 -> second >= 0xA0 && second <= 0xBF;
            case 0xED -> second >= 0x80 && second <= 0x9F;
            case 0xF0 -> second >= 0x90 && second <= 0xBF;
            case 0xF4 -> second >= 0x80 && second <= 0x8F;
            default -> second >= 0x80 && second <= 0xBF;
        };
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

    // As consume, for the byte that opens a value: a `null` there fails as UnexpectedNull naming the field.
    private boolean consumeValue(char expected, String description) {
        skipWhitespace();
        if (pos < end && buf[pos] == expected) {
            pos++;

            return true;
        }

        failValue(description);

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

    // A value of the wrong kind at pos. Option fields consume `null` through isNull before reading, so a `null`
    // reaching here belongs to a non-optional field: it is reported as UnexpectedNull with the field's name.
    private void failValue(String expected) {
        if (error == null && end - pos >= 4 && Arrays.equals(buf, pos, pos + 4, NULL, 0, 4)) {
            var field = enclosingField(pos);

            if (!field.isEmpty()) {
                error = JsonError.UnexpectedNull.FACTORY.apply(field, pos);
                pos = end;

                return;
            }
        }

        fail(expected);
    }

    /// The key of the innermost object member whose value contains `at` (an array inherits its member's key), or ""
    /// at the top level. Re-scans the prefix the reader already accepted, so only a failure pays for it.
    private String enclosingField(int at) {
        var keys = new ArrayDeque<String>();
        var i = begin;

        while (i < at) {
            var b = buf[i];

            if (b == '"') {
                var close = closingQuote(i + 1);
                var next = close + 1;

                while (next < at && (buf[next] == ' ' || buf[next] == '\n' || buf[next] == '\r' || buf[next] == '\t')) {
                    next++;
                }

                if (next < at && buf[next] == ':' && !keys.isEmpty()) {
                    keys.pop();
                    keys.push(new String(buf, i + 1, close - i - 1, StandardCharsets.UTF_8));
                }

                i = close + 1;
                continue;
            }

            if (b == '{') {
                keys.push("");
            } else if (b == '[') {
                keys.push(keys.isEmpty()
                          ? ""
                          : keys.peek());
            } else if ((b == '}' || b == ']') && !keys.isEmpty()) {
                keys.pop();
            }

            i++;
        }

        return keys.isEmpty()
               ? ""
               : keys.peek();
    }

    // Index of the quote closing a string whose content starts at `from` (escapes skipped); `end` if unterminated.
    private int closingQuote(int from) {
        var i = from;

        while (i < end && buf[i] != '"') {
            i += buf[i] == '\\'
                 ? 2
                 : 1;
        }

        return Math.min(i, end);
    }

    private void nestingTooDeep() {
        if (error == null) {
            error = JsonError.NestingTooDeep.FACTORY.apply(maxDepth, pos);
        }

        pos = end;
    }

    // Whether a string of `length` characters starting at `at` is within the limit; records the failure if not.
    private boolean fitsString(int length, int at) {
        if (length <= maxStringLength) {
            return true;
        }

        if (error == null) {
            error = JsonError.StringTooLong.FACTORY.apply(maxStringLength, at);
        }

        pos = end;

        return false;
    }

    // Whether a number starting at `at`, of `digits` digits and `textLength` characters, is within the limits: the
    // digits against the number limit, and, when `textIsString`, the whole text (sign, point and exponent included)
    // against the string limit. Jackson measures the text of every skipped number and of an integer it reads into a
    // BigDecimal or a double, but not of a floating-point number it reads; the callers do the same, measuring an int or
    // long too, which is harmless: its at most 20 characters never reach JsonOptions.MIN_STRING_LENGTH.
    private boolean fitsNumber(int digits, boolean textIsString, int textLength, int at) {
        if (digits > maxNumberLength) {
            if (error == null) {
                error = JsonError.NumberTooLong.FACTORY.apply(maxNumberLength, at);
            }

            pos = end;

            return false;
        }

        return ! textIsString || fitsString(textLength, at);
    }

    // UTF-16 length of the well-formed UTF-8 in bytes[from, to): one character per lead byte, two for a 4-byte one.
    static int utf16Length(byte[] bytes, int from, int to) {
        var length = 0;

        for (int i = from; i < to; i++) {
            var b = bytes[i] & 0xFF;

            if ((b & 0xC0) != 0x80) {
                length += b >= 0xF0
                          ? 2
                          : 1;
            }
        }

        return length;
    }

    private void unknownField(String field, int at) {
        if (error == null) {
            error = JsonError.UnknownField.FACTORY.apply(field, at);
        }

        pos = end;
    }

    private void duplicateKey(String key, int at) {
        if (error == null) {
            error = JsonError.DuplicateKey.FACTORY.apply(key, at);
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
