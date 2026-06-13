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

import org.junit.jupiter.api.Test;
import org.pragmatica.utility.ULID.ULIDError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class ULIDTest {

    // ==================== Reference Test Vectors ====================
    // Canonical ULID "01ARZ3NDEKTSV4RRFFQ69G5FAV" (lowercased here). The timestamp and byte
    // values below were cross-checked against the binary layout used by reference
    // implementations (oklog/ulid, the Rust `ulid` crate).
    private static final String CANONICAL = "01arz3ndektsv4rrffq69g5fav";
    private static final long CANONICAL_TIMESTAMP = 1469922850259L;
    private static final byte[] CANONICAL_BYTES =
        HexFormat.of().parseHex("01563e3ab5d3d6764c61efb99302bd5b");

    @Test
    void nilUlidEncodesToAllZeros() {
        assertEquals("00000000000000000000000000", ULID.NIL.encoded());
    }

    @Test
    void maxUlidEncodesToExpectedValue() {
        // First char carries only 3 significant bits, so the maximum is '7' followed by 25 'z'.
        assertEquals("7zzzzzzzzzzzzzzzzzzzzzzzzz", ULID.MAX.encoded());
    }

    @Test
    void nilUlidRoundTrip() {
        var parsed = ULID.parse("00000000000000000000000000");
        assertTrue(parsed.isSuccess());
        parsed.onSuccess(ulid -> {
            assertTrue(ulid.isNil());
            assertArrayEquals(new byte[16], ulid.toBytes());
        });
    }

    @Test
    void maxUlidRoundTrip() {
        var parsed = ULID.parse("7zzzzzzzzzzzzzzzzzzzzzzzzz");
        assertTrue(parsed.isSuccess());
        parsed.onSuccess(ulid -> {
            assertArrayEquals(ULID.MAX.toBytes(), ulid.toBytes());
            assertEquals(ULID.MAX, ulid);
        });
    }

    @Test
    void canonicalVectorDecodesToExpectedTimestampAndBytes() {
        ULID.parse(CANONICAL)
            .onFailure(cause -> fail("Should parse: " + cause))
            .onSuccess(ulid -> {
                assertEquals(CANONICAL_TIMESTAMP, ulid.timestamp());
                assertArrayEquals(CANONICAL_BYTES, ulid.toBytes());
                assertEquals(CANONICAL, ulid.encoded());
            });
    }

    @Test
    void parseIsCaseInsensitive() {
        var lower = ULID.parse(CANONICAL).fold(_ -> null, u -> u);
        var upper = ULID.parse(CANONICAL.toUpperCase()).fold(_ -> null, u -> u);
        assertEquals(lower, upper);
        // Canonical text representation is always lowercase.
        assertEquals(CANONICAL, upper.encoded());
    }

    @Test
    void parseAcceptsCrockfordAliases() {
        // 'O' -> 0, 'I' -> 1, 'L' -> 1
        var base = ULID.parse("01000000000000000000000000").fold(_ -> null, u -> u);

        var oAlias = ULID.parse("0i000000000000000000000000").fold(_ -> null, u -> u);
        assertEquals(base, oAlias);

        var lAlias = ULID.parse("0l000000000000000000000000").fold(_ -> null, u -> u);
        assertEquals(base, lAlias);

        var zeroAlias = ULID.parse("o1000000000000000000000000").fold(_ -> null, u -> u);
        assertEquals(base, zeroAlias);
    }

    // ==================== Generation Tests ====================

    @Test
    void generatedUlidHasCorrectLength() {
        var ulid = ULID.ulid();
        assertEquals(26, ulid.encoded().length());
        assertEquals(16, ulid.toBytes().length);
    }

    @Test
    void generatedUlidIsNotNil() {
        assertFalse(ULID.ulid().isNil());
    }

    @Test
    void generatedUlidsAreUnique() {
        var set = new HashSet<String>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(set.add(ULID.ulid().encoded()));
        }
    }

    @Test
    void generatedUlidHasReasonableTimestamp() {
        var ulid = ULID.ulid();
        var now = System.currentTimeMillis();
        assertTrue(Math.abs(now - ulid.timestamp()) < 2000,
                   "Timestamp " + ulid.timestamp() + " should be close to " + now);
    }

    // ==================== Monotonic Generation Tests ====================

    @Test
    void monotonicUlidsAreStrictlyIncreasing() {
        var previous = ULID.monotonicUlid();
        for (int i = 0; i < 10_000; i++) {
            var next = ULID.monotonicUlid();
            assertTrue(next.compareTo(previous) > 0,
                       "Monotonic ULID should be strictly greater than the previous one");
            assertTrue(next.encoded().compareTo(previous.encoded()) > 0,
                       "Monotonic ULID text should sort strictly after the previous one");
            previous = next;
        }
    }

    @Test
    void monotonicUlidsAreUnique() {
        var set = new HashSet<String>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(set.add(ULID.monotonicUlid().encoded()));
        }
    }

    // ==================== Parsing Tests ====================

    @Test
    void parseValidUlid() {
        var original = ULID.ulid();
        ULID.parse(original.encoded())
            .onFailure(cause -> fail("Should parse: " + cause))
            .onSuccess(parsed -> assertEquals(original, parsed));
    }

    @Test
    void parseNullReturnsError() {
        var result = ULID.parse(null);
        assertTrue(result.isFailure());
        result.onFailure(cause -> assertInstanceOf(ULIDError.NullInput.class, cause));
    }

    @Test
    void parseTooShortReturnsError() {
        var result = ULID.parse("abc");
        assertTrue(result.isFailure());
        result.onFailure(cause -> {
            assertInstanceOf(ULIDError.InvalidLength.class, cause);
            assertEquals(3, ((ULIDError.InvalidLength) cause).actual());
        });
    }

    @Test
    void parseTooLongReturnsError() {
        var result = ULID.parse("000000000000000000000000000");
        assertTrue(result.isFailure());
        result.onFailure(cause -> assertInstanceOf(ULIDError.InvalidLength.class, cause));
    }

    @Test
    void parseInvalidCharacterReturnsError() {
        // 'u' is excluded from the Crockford alphabet and is not an alias.
        var result = ULID.parse("0000000000000000000000000u");
        assertTrue(result.isFailure());
        result.onFailure(cause -> assertInstanceOf(ULIDError.InvalidCharacter.class, cause));
    }

    @Test
    void parseInvalidCharacterSpaceReturnsError() {
        var result = ULID.parse("0000000000000000000000000 ");
        assertTrue(result.isFailure());
        result.onFailure(cause -> assertInstanceOf(ULIDError.InvalidCharacter.class, cause));
    }

    // ==================== Byte Array Tests ====================

    @Test
    void fromBytesValidInput() {
        var original = ULID.ulid();
        ULID.fromBytes(original.toBytes())
            .onFailure(cause -> fail("Should parse bytes: " + cause))
            .onSuccess(parsed -> assertEquals(original, parsed));
    }

    @Test
    void fromBytesNullReturnsError() {
        var result = ULID.fromBytes(null);
        assertTrue(result.isFailure());
        result.onFailure(cause -> assertInstanceOf(ULIDError.NullInput.class, cause));
    }

    @Test
    void fromBytesWrongLengthReturnsError() {
        var result = ULID.fromBytes(new byte[10]);
        assertTrue(result.isFailure());
        result.onFailure(cause -> {
            assertInstanceOf(ULIDError.InvalidByteLength.class, cause);
            assertEquals(10, ((ULIDError.InvalidByteLength) cause).actual());
        });
    }

    @Test
    void toBytesReturnsDefensiveCopy() {
        var ulid = ULID.ulid();
        var bytes1 = ulid.toBytes();
        var bytes2 = ulid.toBytes();

        assertNotSame(bytes1, bytes2);
        assertArrayEquals(bytes1, bytes2);

        bytes1[0] = (byte) 0xFF;
        assertFalse(java.util.Arrays.equals(bytes1, ulid.toBytes()));
    }

    // ==================== Comparison and Sorting Tests ====================

    @Test
    void nilIsLessThanMax() {
        assertTrue(ULID.NIL.compareTo(ULID.MAX) < 0);
        assertTrue(ULID.MAX.compareTo(ULID.NIL) > 0);
    }

    @Test
    void equalUlidsCompareToZero() {
        var ulid = ULID.ulid();
        assertEquals(0, ulid.compareTo(ulid));
        ULID.parse(ulid.encoded())
            .onSuccess(parsed -> assertEquals(0, ulid.compareTo(parsed)));
    }

    @Test
    void ulidsAreSortableByTimestamp() throws InterruptedException {
        var ulids = new ArrayList<ULID>();
        for (int i = 0; i < 5; i++) {
            ulids.add(ULID.ulid());
            Thread.sleep(10);
        }

        var sorted = new ArrayList<>(ulids);
        Collections.sort(sorted);

        for (int i = 0; i < sorted.size() - 1; i++) {
            assertTrue(sorted.get(i).compareTo(sorted.get(i + 1)) <= 0);
        }
    }

    @Test
    void stringsSortLexicographicallyByTimestamp() throws InterruptedException {
        var strings = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            strings.add(ULID.ulid().encoded());
            Thread.sleep(10);
        }

        var sorted = new ArrayList<>(strings);
        Collections.sort(sorted);

        for (int i = 0; i < sorted.size() - 1; i++) {
            assertTrue(sorted.get(i).compareTo(sorted.get(i + 1)) <= 0);
        }
    }

    // ==================== Equality and Hashing Tests ====================

    @Test
    void equalityBasedOnContent() {
        var ulid1 = ULID.ulid();
        var ulid2 = ULID.parse(ulid1.encoded()).fold(_ -> null, u -> u);

        assertEquals(ulid1, ulid2);
        assertEquals(ulid1.hashCode(), ulid2.hashCode());
    }

    @Test
    void differentUlidsAreNotEqual() {
        assertNotEquals(ULID.ulid(), ULID.ulid());
    }

    @Test
    void equalsWithNull() {
        assertNotEquals(ULID.ulid(), null);
    }

    @Test
    void equalsWithDifferentType() {
        assertNotEquals(ULID.ulid(), "not a ulid");
    }

    // ==================== toString Tests ====================

    @Test
    void toStringReturnsEncoded() {
        var ulid = ULID.ulid();
        assertEquals(ulid.encoded(), ulid.toString());
    }

    // ==================== Base32 Encoding Edge Cases ====================

    @Test
    void encodingIsConsistent() {
        var ulid = ULID.ulid();
        assertEquals(ulid.encoded(), ulid.encoded());
    }

    @Test
    void timestampExtractionForNil() {
        assertEquals(0L, ULID.NIL.timestamp());
    }

    @Test
    void timestampExtractionForMax() {
        // 48-bit maximum: 2^48 - 1
        assertEquals(281474976710655L, ULID.MAX.timestamp());
    }

    // ==================== Constants Tests ====================

    @Test
    void constantsHaveCorrectValues() {
        assertEquals(16, ULID.BYTE_LENGTH);
        assertEquals(6, ULID.TIMESTAMP_LENGTH);
        assertEquals(10, ULID.PAYLOAD_LENGTH);
        assertEquals(26, ULID.STRING_LENGTH);
    }
}
