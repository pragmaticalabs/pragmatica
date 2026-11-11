package org.pragmatica.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyUnicodeLengthTest {
    private static final String EMOJI = "😀";           // U+1F600, surrogate pair, 4 UTF-8 bytes
    private static final String E_ACUTE = "é";               // U+00E9, 2 bytes
    private static final String EURO = "€";                  // U+20AC, 3 bytes
    private static final String COMBINING = "é";            // e + combining acute: 2 code points, 3 bytes

    @Test
    void codePointsBetween_countsSurrogatePairAsOne() {
        assertTrue(Verify.Is.codePointsBetween(EMOJI, 1, 1));
        assertFalse(Verify.Is.codePointsBetween(EMOJI, 2, 2));
        assertFalse(Verify.Is.lenBetween(EMOJI, 1, 1));
    }

    @Test
    void codePointsBetween_countsAsciiAndBmp() {
        assertTrue(Verify.Is.codePointsBetween("abc", 3, 3));
        assertTrue(Verify.Is.codePointsBetween(E_ACUTE + EURO, 2, 2));
    }

    @Test
    void codePointsBetween_countsCombiningMarkAsOwnCodePoint() {
        assertTrue(Verify.Is.codePointsBetween(COMBINING, 2, 2));
        assertFalse(Verify.Is.codePointsBetween(COMBINING, 1, 1));
    }

    @Test
    void codePointsBetween_boundariesAreInclusive() {
        var value = EMOJI + EMOJI + EMOJI;

        assertTrue(Verify.Is.codePointsBetween(value, 3, 5));
        assertTrue(Verify.Is.codePointsBetween(value, 1, 3));
        assertFalse(Verify.Is.codePointsBetween(value, 4, 5));
        assertFalse(Verify.Is.codePointsBetween(value, 1, 2));
    }

    @Test
    void codePointsBetween_emptyString() {
        assertTrue(Verify.Is.codePointsBetween("", 0, 0));
        assertFalse(Verify.Is.codePointsBetween("", 1, 5));
    }

    @Test
    void utf8LengthAtMost_oneByteBoundary() {
        assertTrue(Verify.Is.utf8LengthAtMost("a", 1));
        assertFalse(Verify.Is.utf8LengthAtMost("a", 0));
        assertTrue(Verify.Is.utf8LengthAtMost("\u007F", 1));
    }

    @Test
    void utf8LengthAtMost_twoByteBoundary() {
        assertTrue(Verify.Is.utf8LengthAtMost("\u0080", 2));
        assertFalse(Verify.Is.utf8LengthAtMost("\u0080", 1));
        assertTrue(Verify.Is.utf8LengthAtMost("߿", 2));
        assertFalse(Verify.Is.utf8LengthAtMost("߿", 1));
    }

    @Test
    void utf8LengthAtMost_threeByteBoundary() {
        assertTrue(Verify.Is.utf8LengthAtMost("ࠀ", 3));
        assertFalse(Verify.Is.utf8LengthAtMost("ࠀ", 2));
        assertTrue(Verify.Is.utf8LengthAtMost("￿", 3));
        assertFalse(Verify.Is.utf8LengthAtMost("￿", 2));
        assertTrue(Verify.Is.utf8LengthAtMost(EURO, 3));
        assertFalse(Verify.Is.utf8LengthAtMost(EURO, 2));
    }

    @Test
    void utf8LengthAtMost_fourByteBoundary() {
        assertTrue(Verify.Is.utf8LengthAtMost(EMOJI, 4));
        assertFalse(Verify.Is.utf8LengthAtMost(EMOJI, 3));
        assertTrue(Verify.Is.utf8LengthAtMost("𐀀", 4));
        assertFalse(Verify.Is.utf8LengthAtMost("􏿿", 3));
    }

    @Test
    void utf8LengthAtMost_sumsMixedContent() {
        var value = "a" + E_ACUTE + EURO + EMOJI;      // 1 + 2 + 3 + 4

        assertTrue(Verify.Is.utf8LengthAtMost(value, 10));
        assertFalse(Verify.Is.utf8LengthAtMost(value, 9));
    }

    @Test
    void utf8LengthAtMost_bcryptStyleCap() {
        var seventyTwo = "a".repeat(72);

        assertTrue(Verify.Is.utf8LengthAtMost(seventyTwo, 72));
        assertFalse(Verify.Is.utf8LengthAtMost(seventyTwo + "a", 72));
        assertTrue(Verify.Is.utf8LengthAtMost(EMOJI.repeat(18), 72));
        assertFalse(Verify.Is.utf8LengthAtMost(EMOJI.repeat(19), 72));
    }

    @Test
    void utf8LengthAtMost_loneSurrogateCountsOneByte() {
        assertTrue(Verify.Is.utf8LengthAtMost("\uD83D", 1));
        assertTrue(Verify.Is.utf8LengthAtMost("\uDE00", 1));
    }

    @Test
    void utf8LengthAtMost_matchesJdkEncoderForWellFormedText() {
        var value = "a" + E_ACUTE + EURO + EMOJI + COMBINING;
        var bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        assertTrue(Verify.Is.utf8LengthAtMost(value, bytes));
        assertFalse(Verify.Is.utf8LengthAtMost(value, bytes - 1));
    }
}
