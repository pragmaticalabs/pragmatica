package org.pragmatica.lang.parse;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

class NumberStrictDecimalTest {
    private static final Cause REJECTED = Causes.cause("rejected");

    private static void assertRejectedLong(String input) {
        Number.parseUnsignedDecimalLong(input)
              .onSuccess(v -> fail("accepted " + input + " as " + v));
        Number.parseUnsignedDecimalLong(input, REJECTED)
              .onSuccess(v -> fail("accepted " + input + " as " + v))
              .onFailure(cause -> assertSame(REJECTED, cause));
    }

    private static void assertRejectedInt(String input) {
        Number.parseUnsignedDecimalInt(input)
              .onSuccess(v -> fail("accepted " + input + " as " + v));
        Number.parseUnsignedDecimalInt(input, REJECTED)
              .onSuccess(v -> fail("accepted " + input + " as " + v))
              .onFailure(cause -> assertSame(REJECTED, cause));
    }

    private static <T> void assertAccepted(Result<T> result, T expected) {
        result.onFailure(cause -> fail(cause.message()))
              .onSuccess(v -> assertEquals(expected, v));
    }

    @Test
    void parseUnsignedDecimalLong_accepts_plainDigits() {
        assertAccepted(Number.parseUnsignedDecimalLong("12345"), 12345L);
        assertAccepted(Number.parseUnsignedDecimalLong("0"), 0L);
        assertAccepted(Number.parseUnsignedDecimalLong("12345", REJECTED), 12345L);
    }

    @Test
    void parseUnsignedDecimalLong_accepts_leadingZeros() {
        assertAccepted(Number.parseUnsignedDecimalLong("007"), 7L);
        assertAccepted(Number.parseUnsignedDecimalLong("0000"), 0L);
    }

    @Test
    void parseUnsignedDecimalLong_accepts_maxValue() {
        assertAccepted(Number.parseUnsignedDecimalLong("9223372036854775807"), Long.MAX_VALUE);
    }

    @Test
    void parseUnsignedDecimalLong_rejects_overflowAtMaxPlusOne() {
        assertRejectedLong("9223372036854775808");
        assertRejectedLong("99999999999999999999");
    }

    @Test
    void parseUnsignedDecimalLong_rejects_signs() {
        assertRejectedLong("+5");
        assertRejectedLong("-1");
        assertRejectedLong("-0");
    }

    @Test
    void parseUnsignedDecimalLong_rejects_whitespace() {
        assertRejectedLong(" 5");
        assertRejectedLong("5 ");
        assertRejectedLong("5\n");
        assertRejectedLong("1 2");
    }

    @Test
    void parseUnsignedDecimalLong_rejects_nonAsciiDigits() {
        assertRejectedLong("٥");                 // Arabic-Indic five
        assertRejectedLong("١٢");           // Arabic-Indic 12
        assertRejectedLong("５");                 // fullwidth five
        assertRejectedLong("1５");
    }

    @Test
    void parseUnsignedDecimalLong_rejects_emptyAndNull() {
        assertRejectedLong("");
        assertRejectedLong(null);
    }

    @Test
    void parseUnsignedDecimalLong_rejects_nonDigits() {
        assertRejectedLong("5.0");
        assertRejectedLong("1e3");
        assertRejectedLong("0x10");
        assertRejectedLong("abc");
    }

    @Test
    void parseUnsignedDecimalLong_failureMessageNamesInput() {
        Number.parseUnsignedDecimalLong("+5")
              .onFailure(cause -> assertEquals("Not an unsigned decimal number in the range of long: +5", cause.message()));
    }

    @Test
    void parseUnsignedDecimalInt_accepts_plainDigitsAndLeadingZeros() {
        assertAccepted(Number.parseUnsignedDecimalInt("12345"), 12345);
        assertAccepted(Number.parseUnsignedDecimalInt("0"), 0);
        assertAccepted(Number.parseUnsignedDecimalInt("007"), 7);
        assertAccepted(Number.parseUnsignedDecimalInt("007", REJECTED), 7);
    }

    @Test
    void parseUnsignedDecimalInt_accepts_maxValue() {
        assertAccepted(Number.parseUnsignedDecimalInt("2147483647"), Integer.MAX_VALUE);
    }

    @Test
    void parseUnsignedDecimalInt_rejects_overflowAtMaxPlusOne() {
        assertRejectedInt("2147483648");
        assertRejectedInt("9223372036854775807");
    }

    @Test
    void parseUnsignedDecimalInt_rejects_signsAndWhitespace() {
        assertRejectedInt("+5");
        assertRejectedInt("-1");
        assertRejectedInt(" 5");
        assertRejectedInt("5 ");
    }

    @Test
    void parseUnsignedDecimalInt_rejects_nonAsciiDigits() {
        assertRejectedInt("٥");
        assertRejectedInt("５");
    }

    @Test
    void parseUnsignedDecimalInt_rejects_emptyAndNull() {
        assertRejectedInt("");
        assertRejectedInt(null);
    }

    @Test
    void parseUnsignedDecimalInt_failureMessageNamesInput() {
        Number.parseUnsignedDecimalInt("-1")
              .onFailure(cause -> assertEquals("Not an unsigned decimal number in the range of int: -1", cause.message()));
    }
}
