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
package org.pragmatica.utility;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;

/// Universally Unique Lexicographically Sortable Identifier (ULID) implementation.
///
/// A ULID is a 16-byte (128-bit) identifier consisting of:
///
///   - 6 bytes: 48-bit unsigned timestamp (milliseconds since the Unix epoch, big-endian)
///   - 10 bytes: 80-bit cryptographically random payload
///
/// The text representation is 26 characters using Crockford's base32 encoding. This
/// implementation emits the lowercase alphabet (`0123456789abcdefghjkmnpqrstvwxyz`),
/// which is still strictly ASCII-ascending, so lexicographic ordering of the text form
/// matches chronological order. Parsing is Crockford-lenient: it is case-insensitive and
/// accepts the ambiguous aliases `O`/`o` -> `0` and `I`/`i`/`L`/`l` -> `1`.
///
/// The 48-bit/26-char layout means the 80-bit random section lands on a clean character
/// boundary, so a single uniform "two pad bits, most-significant-bit first" 5-bit packing
/// over all 16 bytes is byte-compatible with the canonical ULID binary specification.
///
/// @see <a href="https://github.com/ulid/spec">ULID Specification</a>
public final class ULID implements Comparable<ULID> {
    /// Binary length in bytes
    public static final int BYTE_LENGTH = 16;

    /// Timestamp length in bytes
    public static final int TIMESTAMP_LENGTH = 6;

    /// Payload length in bytes
    public static final int PAYLOAD_LENGTH = 10;

    /// String-encoded length
    public static final int STRING_LENGTH = 26;

    /// Crockford base32 encoding alphabet (lowercase, lexicographically ordered)
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    /// Reverse lookup table for base32 decoding (ASCII -> value, -1 for invalid)
    private static final byte[] DECODE = new byte[128];

    static {
        Arrays.fill(DECODE, (byte) - 1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE[Character.toUpperCase(ALPHABET[i])] = (byte) i;
            DECODE[ALPHABET[i]] = (byte) i;
        }
        // Crockford-lenient aliases for visually ambiguous characters
        DECODE['O'] = DECODE['o'] = 0;
        DECODE['I'] = DECODE['i'] = 1;
        DECODE['L'] = DECODE['l'] = 1;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /// Nil ULID (all zeros) - represents invalid/empty state
    public static final ULID NIL = new ULID(new byte[BYTE_LENGTH]);

    /// Maximum possible ULID (all 0xFF bytes)
    public static final ULID MAX = new ULID(maxBytes());

    /// Monotonic generator state, guarded by MONOTONIC_LOCK.
    private static final Object MONOTONIC_LOCK = new Object();
    private static long lastTimestamp = -1L;
    private static final byte[] lastRandom = new byte[PAYLOAD_LENGTH];

    private final byte[] data;

    private ULID(byte[] data) {
        this.data = data;
    }

    /// Generate a new random ULID using the current timestamp.
    public static ULID ulid() {
        var payload = new byte[PAYLOAD_LENGTH];
        RANDOM.nextBytes(payload);
        return new ULID(compose(System.currentTimeMillis(), payload));
    }

    /// Generate a monotonic ULID.
    ///
    /// Within the same millisecond (or if the system clock moves backwards), the previously
    /// generated 80-bit random payload is incremented by one, guaranteeing a strictly larger
    /// value while preserving the earlier timestamp. On the (practically impossible) overflow
    /// of the random payload, the timestamp is advanced by one millisecond and a fresh payload
    /// is drawn. Thread-safe.
    public static ULID monotonicUlid() {
        synchronized (MONOTONIC_LOCK) {
            var now = System.currentTimeMillis();
            if (now > lastTimestamp) {
                lastTimestamp = now;
                RANDOM.nextBytes(lastRandom);
            } else if (!incrementPayload(lastRandom)) {
                lastTimestamp++;
                RANDOM.nextBytes(lastRandom);
            }
            return new ULID(compose(lastTimestamp, lastRandom));
        }
    }

    /// Parse a ULID from its 26-character Crockford base32 string representation.
    public static Result<ULID> parse(String input) {
        return option(input).toResult(ULIDError.NULL_INPUT)
                     .filter(ULIDError::invalidLength,
                             s -> s.length() == STRING_LENGTH)
                     .flatMap(s -> decodeBase32(s).toResult(ULIDError.INVALID_CHARACTER))
                     .map(ULID::new);
    }

    /// Create a ULID from its 16-byte binary representation.
    public static Result<ULID> fromBytes(byte[] bytes) {
        return option(bytes).toResult(ULIDError.NULL_INPUT)
                     .filter(ULIDError::invalidByteLength, b -> b.length == BYTE_LENGTH)
                     .map(b -> {
                              var copy = new byte[BYTE_LENGTH];
                              System.arraycopy(b, 0, copy, 0, BYTE_LENGTH);
                              return new ULID(copy);
                          });
    }

    /// Get the 26-character Crockford base32 encoded string representation.
    public String encoded() {
        return encodeBase32(data);
    }

    /// Get the raw 16-byte binary representation (defensive copy).
    public byte[] toBytes() {
        var copy = new byte[BYTE_LENGTH];
        System.arraycopy(data, 0, copy, 0, BYTE_LENGTH);
        return copy;
    }

    /// Get the Unix timestamp in milliseconds (48-bit big-endian prefix).
    public long timestamp() {
        return ((data[0] & 0xFFL)<< 40)
               | ((data[1] & 0xFFL)<< 32)
               | ((data[2] & 0xFFL)<< 24)
               | ((data[3] & 0xFFL)<< 16)
               | ((data[4] & 0xFFL)<< 8)
               | (data[5] & 0xFFL);
    }

    /// Check if this is the nil (all-zero) ULID.
    public boolean isNil() {
        for (byte b : data) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(ULID other) {
        return Arrays.compareUnsigned(data, other.data);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ULID other && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return encoded();
    }

    // ==================== Internal helpers ====================

    /// Assemble 16 bytes from a 48-bit timestamp and a 10-byte payload.
    private static byte[] compose(long timestamp, byte[] payload) {
        var data = new byte[BYTE_LENGTH];
        data[0] = (byte)(timestamp>>> 40);
        data[1] = (byte)(timestamp>>> 32);
        data[2] = (byte)(timestamp>>> 24);
        data[3] = (byte)(timestamp>>> 16);
        data[4] = (byte)(timestamp>>> 8);
        data[5] = (byte) timestamp;
        System.arraycopy(payload, 0, data, TIMESTAMP_LENGTH, PAYLOAD_LENGTH);
        return data;
    }

    /// Increment the payload as an 80-bit big-endian integer.
    /// Returns false on overflow (payload was all 0xFF, now wrapped to all zeros).
    private static boolean incrementPayload(byte[] payload) {
        for (int i = payload.length - 1; i >= 0; i--) {
            if ((payload[i] & 0xFF) == 0xFF) {
                payload[i] = 0;
            } else {
                payload[i]++;
                return true;
            }
        }
        return false;
    }

    // ==================== Crockford Base32 Encoding ====================

    /// Encode 16 bytes to a 26-character base32 string.
    /// The 128 bits are treated as 130 bits with two leading zero pad bits, then split
    /// into 26 groups of 5 bits, most-significant first.
    private static String encodeBase32(byte[] src) {
        var dst = new char[STRING_LENGTH];
        for (int i = 0; i < STRING_LENGTH; i++) {
            int index = 0;
            for (int b = 0; b < 5; b++) {
                int bitPosition = i * 5 + b - 2;       // subtract the two leading pad bits
                int bit = 0;
                if (bitPosition >= 0) {
                    bit = (src[bitPosition>>> 3]>>> (7 - (bitPosition & 7))) & 1;
                }
                index = (index<< 1) | bit;
            }
            dst[i] = ALPHABET[index];
        }
        return new String(dst);
    }

    /// Decode a 26-character base32 string to 16 bytes.
    /// Returns none if any character is invalid. Crockford-lenient: case-insensitive with
    /// `O`->`0` and `I`/`L`->`1` aliases. The two leading pad bits are ignored.
    private static Option<byte[]> decodeBase32(String src) {
        var dst = new byte[BYTE_LENGTH];
        for (int i = 0; i < STRING_LENGTH; i++) {
            char c = src.charAt(i);
            if (c >= 128) {
                return none();
            }
            int value = DECODE[c];
            if (value < 0) {
                return none();
            }
            for (int b = 0; b < 5; b++) {
                int bitPosition = i * 5 + b - 2;
                if (bitPosition < 0) {
                    continue;
                }
                int bit = (value>>> (4 - b)) & 1;
                if (bit != 0) {
                    dst[bitPosition>>> 3] |= (byte)(1<< (7 - (bitPosition & 7)));
                }
            }
        }
        return some(dst);
    }

    private static byte[] maxBytes() {
        var bytes = new byte[BYTE_LENGTH];
        Arrays.fill(bytes, (byte) 0xFF);
        return bytes;
    }

    /// Error causes for ULID operations.
    public sealed interface ULIDError extends Cause {
        ULIDError NULL_INPUT = new NullInput();
        ULIDError INVALID_CHARACTER = new InvalidCharacter();

        static ULIDError invalidLength(String input) {
            return new InvalidLength(input.length());
        }

        static ULIDError invalidByteLength(byte[] bytes) {
            return new InvalidByteLength(bytes.length);
        }

        record NullInput() implements ULIDError {
            @Override
            public String message() {
                return "Input is null";
            }
        }

        record InvalidLength(int actual) implements ULIDError {
            @Override
            public String message() {
                return "Invalid string length: expected " + STRING_LENGTH + ", got " + actual;
            }
        }

        record InvalidByteLength(int actual) implements ULIDError {
            @Override
            public String message() {
                return "Invalid byte array length: expected " + BYTE_LENGTH + ", got " + actual;
            }
        }

        record InvalidCharacter() implements ULIDError {
            @Override
            public String message() {
                return "Invalid base32 character in input";
            }
        }
    }
}
