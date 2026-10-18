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
import java.util.Arrays;
import java.util.UUID;

import org.pragmatica.lang.Contract;
import org.pragmatica.serialization.json.JsonOptions;


/// Growable UTF-8 output buffer, called by generated codecs. Reusable through [#reset].
///
/// Of the [JsonOptions], only `nullInclusion` concerns writing, and it costs only an empty `Option` component
/// ([#writeEmpty]): leaving out an object's leading members would leave the next member's comma after the `{`, so
/// the omission records that comma's position and the comma is removed when the bytes are taken ([#toByteArray],
/// [#buffer], [#size]), which costs one comparison per document otherwise.
@Contract
public final class JsonWriter {
    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};

    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};

    private static final byte[] NULL = {'n', 'u', 'l', 'l'};

    private static final byte[] MIN_LONG = Long.toString(Long.MIN_VALUE).getBytes(StandardCharsets.ISO_8859_1);

    // Two ASCII digits per entry for 00..99.
    private static final byte[] DIGIT_PAIRS = new byte[200];
    // 0: copy as is; 'u': \\u00XX; anything else: two-byte escape \\<char>.
    private static final byte[] ESCAPE = new byte[128];

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_PAIRS[2 * i] = (byte)('0' + i / 10);
            DIGIT_PAIRS[2 * i + 1] = (byte)('0' + i % 10);
        }

        for (int i = 0; i < 0x20; i++) {
            ESCAPE[i] = 'u';
        }

        ESCAPE['"'] = '"';
        ESCAPE['\\'] = '\\';
        ESCAPE['\b'] = 'b';
        ESCAPE['\f'] = 'f';
        ESCAPE['\n'] = 'n';
        ESCAPE['\r'] = 'r';
        ESCAPE['\t'] = 't';
    }

    private static final int[] NO_DROPS = new int[0];

    private byte[] buf;
    private int pos;
    private final StringBuilder number = new StringBuilder(32);
    // Whether an empty Option component is left out rather than written as null.
    private boolean omitEmpty;
    // Positions, in increasing order, just after an object's `{` where every member so far was left out: the comma
    // the next member writes there is removed by compact.
    private int[] drops = NO_DROPS;
    private int dropCount;

    private JsonWriter(int capacity) {
        this.buf = new byte[capacity];
    }

    public static JsonWriter jsonWriter(int initialCapacity) {
        return new JsonWriter(Math.max(64, initialCapacity));
    }

    /// Empties the writer for a new document written under [JsonOptions#DEFAULTS].
    public JsonWriter reset() {
        return reset(JsonOptions.DEFAULTS);
    }

    /// Empties the writer for a new document written under `options`.
    public JsonWriter reset(JsonOptions options) {
        pos = 0;
        dropCount = 0;
        omitEmpty = options.nullInclusion() == JsonOptions.NullInclusion.EXCLUDE;

        return this;
    }

    /// A copy of the complete document. This and [#buffer] and [#size] finish the left-out members' bookkeeping, so
    /// take them once the value is written, never in the middle of an object.
    public byte[] toByteArray() {
        compact();

        return Arrays.copyOf(buf, pos);
    }

    public byte[] buffer() {
        compact();

        return buf;
    }

    public int size() {
        compact();

        return pos;
    }

    /// An empty `Option` record component, whose pre-encoded name is `name`: the name and `null`, or nothing when the
    /// options exclude empty values. Called only for an empty value, so including costs nothing more than before.
    public void writeEmpty(byte[] name) {
        if (omitEmpty) {
            omitMember();

            return;
        }

        writeRaw(name);
        writeNull();
    }

    // Nothing is written. If nothing was written in this object yet either (the last byte is its `{`), the next member
    // will write its leading comma here: record the position once.
    private void omitMember() {
        if (buf[pos - 1] == '{' && (dropCount == 0 || drops[dropCount - 1] != pos)) {
            if (dropCount == drops.length) {
                drops = Arrays.copyOf(drops, Math.max(8, dropCount << 1));
            }

            drops[dropCount++] = pos;
        }
    }

    private void compact() {
        if (dropCount > 0) {
            removeDroppedCommas();
        }
    }

    // A recorded position holds the comma of the member that followed the left-out ones, or the object's `}` when
    // every member was left out; only a comma is removed.
    private void removeDroppedCommas() {
        var out = drops[0];
        var in = drops[0];

        for (int k = 0; k < dropCount; k++) {
            var at = drops[k];

            System.arraycopy(buf, in, buf, out, at - in);
            out += at - in;
            in = at < pos && buf[at] == ','
                 ? at + 1
                 : at;
        }

        System.arraycopy(buf, in, buf, out, pos - in);
        pos = out + pos - in;
        dropCount = 0;
    }

    public void writeByte(char b) {
        ensure(1);
        buf[pos++] = (byte) b;
    }

    /// Copies pre-encoded bytes, typically a field name from [FieldNames].
    public void writeRaw(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
    }

    public void writeNull() {
        writeRaw(NULL);
    }

    public void writeBoolean(boolean value) {
        writeRaw(value
                 ? TRUE
                 : FALSE);
    }

    public void writeInt(int value) {
        writeLong(value);
    }

    public void writeLong(long value) {
        if (value == Long.MIN_VALUE) {
            writeRaw(MIN_LONG);

            return;
        }

        ensure(20);
        if (value < 0) {
            buf[pos++] = '-';
            value = -value;
        }

        var len = digitCount(value);
        var at = pos + len;

        while (value >= 100) {
            var pair = (int)(value % 100) << 1;

            value /= 100;
            buf[--at] = DIGIT_PAIRS[pair + 1];
            buf[--at] = DIGIT_PAIRS[pair];
        }

        if (value >= 10) {
            var pair = (int) value << 1;

            buf[--at] = DIGIT_PAIRS[pair + 1];
            buf[--at] = DIGIT_PAIRS[pair];
        } else {
            buf[--at] = (byte)('0' + value);
        }

        pos += len;
    }

    /// Identical to Jackson's default, `Double.toString`, but formatted into a reused builder: no String.
    /// NaN and the infinities are not JSON numbers; like Jackson, they are written as the strings `"NaN"`,
    /// `"Infinity"` and `"-Infinity"`, which [JsonReader#readDouble] accepts back.
    public void writeDouble(double value) {
        if (!Double.isFinite(value)) {
            writeAsciiString(Double.toString(value));

            return;
        }

        number.setLength(0);
        number.append(value);
        var len = number.length();

        ensure(len);
        for (int i = 0; i < len; i++) {
            buf[pos++] = (byte) number.charAt(i);
        }
    }

    /// Identical to Jackson's default for `BigDecimal`: `toString()`, not plain notation.
    public void writeDecimal(BigDecimal value) {
        writeAscii(value.toString());
    }

    /// Exactly `"` + `Instant.toString()` + `"` (ISO_INSTANT: fraction in groups of 3/6/9 digits, none
    /// when zero), written in place. Years outside 0000-9999 use `toString()`.
    public void writeInstant(Instant value) {
        var seconds = value.getEpochSecond();
        var days = Math.floorDiv(seconds, 86_400L);
        var secondOfDay = (int) Math.floorMod(seconds, 86_400L);
        // H. Hinnant's civil_from_days.
        var z = days + 719_468L;
        var era = (z >= 0
                   ? z
                   : z - 146_096L) / 146_097L;
        var doe = z - era * 146_097L;
        var yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
        var doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        var mp = (5 * doy + 2) / 153;
        var day = (int)(doy - (153 * mp + 2) / 5 + 1);
        var month = (int)(mp < 10
                          ? mp + 3
                          : mp - 9);
        var year = yoe + era * 400 + (month <= 2
                                      ? 1
                                      : 0);

        if (year < 0 || year > 9_999) {
            writeAsciiString(value.toString());

            return;
        }

        ensure(32);
        buf[pos++] = '"';
        putPair((int)(year / 100));
        putPair((int)(year % 100));
        buf[pos++] = '-';
        putPair(month);
        buf[pos++] = '-';
        putPair(day);
        buf[pos++] = 'T';
        putPair(secondOfDay / 3_600);
        buf[pos++] = ':';
        putPair(secondOfDay / 60 % 60);
        buf[pos++] = ':';
        putPair(secondOfDay % 60);
        var nanos = value.getNano();

        if (nanos != 0) {
            buf[pos++] = '.';
            if (nanos % 1_000_000 == 0) {
                putDigits(nanos / 1_000_000, 3);
            } else if (nanos % 1_000 == 0) {
                putDigits(nanos / 1_000, 6);
            } else {
                putDigits(nanos, 9);
            }
        }

        buf[pos++] = 'Z';
        buf[pos++] = '"';
    }

    private void putPair(int value) {
        buf[pos++] = DIGIT_PAIRS[value << 1];
        buf[pos++] = DIGIT_PAIRS[(value << 1) + 1];
    }

    // Exactly `width` digits, zero-padded.
    private void putDigits(int value, int width) {
        for (int at = pos + width - 1; at >= pos; at--) {
            buf[at] = (byte)('0' + value % 10);
            value /= 10;
        }

        pos += width;
    }

    public void writeUuid(UUID value) {
        writeAsciiString(value.toString());
    }

    public void writeLocalDate(LocalDate value) {
        writeAsciiString(value.toString());
    }

    /// Writes a string known to contain only JSON-safe ASCII (numbers, ISO timestamps, enum names).
    public void writeAsciiString(String value) {
        var len = value.length();

        ensure(len + 2);
        buf[pos++] = '"';
        value.getBytes(0, len, buf, pos);
        pos += len;
        buf[pos++] = '"';
    }

    /// Short strings: the char loop, kept inline here (moving it behind a call measurably slowed
    /// short-string payloads). Long strings: the JDK's vectorized encoder plus a masked 8-byte escape check,
    /// out of line; when something needs escaping it commits nothing and the loop takes over.
    public void writeString(String value) {
        var len = value.length();

        if (len >= VECTOR_THRESHOLD && writeVectorized(value)) {
            return;
        }
        // Worst case per char: 6 bytes (\\u00XX) or 3 bytes of UTF-8.
        ensure(len * 6 + 2);
        buf[pos++] = '"';
        for (int i = 0; i < len; i++) {
            var c = value.charAt(i);

            if (c < 0x80 && ESCAPE[c] == 0) {
                buf[pos++] = (byte) c;
            } else if (c < 0x80) {
                escapeAscii(c);
            } else {
                i = writeNonAscii(value, i, c);
            }
        }

        buf[pos++] = '"';
    }

    private static final int VECTOR_THRESHOLD = 16;

    private boolean writeVectorized(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var len = bytes.length;
        // +8: the last word of the check may read past the copied bytes.
        ensure(len + 10);
        var start = pos + 1;

        System.arraycopy(bytes, 0, buf, start, len);
        if (hasEscapable(buf, start, len)) {
            return false;
        }

        buf[pos] = '"';
        pos = start + len;
        buf[pos++] = '"';

        return true;
    }

    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;

    // Any quote, backslash or control byte in buf[start, start + len). The last word is masked: bytes
    // past the end are replaced by 'A', which never needs escaping. Requires 8 bytes of slack.
    static boolean hasEscapable(byte[] buf, int start, int len) {
        var hits = 0L;
        var i = 0;

        for (; i + 8 <= len; i += 8) {
            hits |= escapableBits((long) LONGS.get(buf, start + i));
        }

        var rest = len - i;

        if (rest > 0) {
            var mask = -1L >>>(64 - 8 * rest);

            hits |= escapableBits(((long) LONGS.get(buf, start + i) & mask) | (0x4141414141414141L & ~mask));
        }

        return hits != 0;
    }

    private static long escapableBits(long w) {
        var quote = w ^ 0x2222222222222222L;
        var slash = w ^ 0x5C5C5C5C5C5C5C5CL;

        return ((w - 0x2020202020202020L) & ~w | (quote - ONES) & ~quote | (slash - ONES) & ~slash) & HIGHS;
    }

    private void writeAscii(String value) {
        var len = value.length();

        ensure(len);
        value.getBytes(0, len, buf, pos);
        pos += len;
    }

    private void escapeAscii(char c) {
        var kind = ESCAPE[c];

        buf[pos++] = '\\';
        if (kind != 'u') {
            buf[pos++] = kind;

            return;
        }

        buf[pos++] = 'u';
        buf[pos++] = '0';
        buf[pos++] = '0';
        buf[pos++] = HEX[c >> 4];
        buf[pos++] = HEX[c & 0xF];
    }

    // Encodes one char (or surrogate pair) as UTF-8; returns the index of the last char consumed.
    private int writeNonAscii(String value, int i, char c) {
        if (c < 0x800) {
            buf[pos++] = (byte)(0xC0 | (c >> 6));
            buf[pos++] = (byte)(0x80 | (c & 0x3F));

            return i;
        }

        if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
            var cp = Character.toCodePoint(c, value.charAt(i + 1));

            buf[pos++] = (byte)(0xF0 | (cp >> 18));
            buf[pos++] = (byte)(0x80 | ((cp >> 12) & 0x3F));
            buf[pos++] = (byte)(0x80 | ((cp >> 6) & 0x3F));
            buf[pos++] = (byte)(0x80 | (cp & 0x3F));

            return i + 1;
        }
        // An unpaired surrogate becomes U+FFFD, as String.getBytes(UTF_8) does.
        var ch = Character.isSurrogate(c)
                 ? '�'
                 : c;

        buf[pos++] = (byte)(0xE0 | (ch >> 12));
        buf[pos++] = (byte)(0x80 | ((ch >> 6) & 0x3F));
        buf[pos++] = (byte)(0x80 | (ch & 0x3F));

        return i;
    }

    private static int digitCount(long value) {
        var count = 1;

        for (long bound = 10; count < 19 && value >= bound; bound *= 10) {
            count++;
        }

        return count;
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length << 1, pos + extra));
        }
    }
}
