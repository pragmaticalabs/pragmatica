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
package org.pragmatica.lang.parse;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// Functional wrappers for JDK number parsing APIs that return Result<T> instead of throwing exceptions
public sealed interface Number {
    /// Parse a string as a Byte value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed Byte or parsing error
    static Result<Byte> parseByte(String s) {
        return Result.lift1(Byte::parseByte, s);
    }

    /// Parse a string as a Byte value with specified radix
    ///
    /// @param s String to parse
    /// @param radix The radix to use for parsing
    ///
    /// @return Result containing parsed Byte or parsing error
    static Result<Byte> parseByte(String s, int radix) {
        return Result.lift2(Byte::parseByte, s, radix);
    }

    /// Parse a string as a Short value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed Short or parsing error
    static Result<Short> parseShort(String s) {
        return Result.lift1(Short::parseShort, s);
    }

    /// Parse a string as a Short value with specified radix
    ///
    /// @param s String to parse
    /// @param radix The radix to use for parsing
    ///
    /// @return Result containing parsed Short or parsing error
    static Result<Short> parseShort(String s, int radix) {
        return Result.lift2(Short::parseShort, s, radix);
    }

    /// Parse a string as an Integer value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed Integer or parsing error
    static Result<Integer> parseInt(String s) {
        return Result.lift1(Integer::parseInt, s);
    }

    /// Parse a string as an Integer value with specified radix
    ///
    /// @param s String to parse
    /// @param radix The radix to use for parsing
    ///
    /// @return Result containing parsed Integer or parsing error
    static Result<Integer> parseInt(String s, int radix) {
        return Result.lift2(Integer::parseInt, s, radix);
    }

    /// Parse a string as a Long value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed Long or parsing error
    static Result<Long> parseLong(String s) {
        return Result.lift1(Long::parseLong, s);
    }

    /// Parse a string as a Long value with specified radix
    ///
    /// @param s String to parse
    /// @param radix The radix to use for parsing
    ///
    /// @return Result containing parsed Long or parsing error
    static Result<Long> parseLong(String s, int radix) {
        return Result.lift2(Long::parseLong, s, radix);
    }

    /// Parse a string as a non-negative `long` in strict decimal form.
    ///
    /// Accepted: one or more ASCII digits `0`-`9` and nothing else. Leading zeros are accepted
    /// (`"007"` is 7). Rejected: the empty string, `null`, a sign (`+5`, `-1`), any whitespace (`" 5"`),
    /// digits of other scripts (Arabic-Indic, fullwidth, ... which [Long#parseLong(String)] accepts), and
    /// values above [Long#MAX_VALUE].
    ///
    /// @param s String to parse
    ///
    /// @return Result containing the parsed value, or a failure naming the rejected input
    static Result<Long> parseUnsignedDecimalLong(String s) {
        return strictLong(s, Number::notUnsignedDecimalLong);
    }

    /// Same as [#parseUnsignedDecimalLong(String)] but every rejection (format or overflow) fails with the given cause.
    ///
    /// @param s     String to parse
    /// @param cause the failure for any rejected input
    ///
    /// @return Result containing the parsed value, or `cause`
    static Result<Long> parseUnsignedDecimalLong(String s, Cause cause) {
        return strictLong(s, _ -> cause);
    }

    /// Parse a string as a non-negative `int` in strict decimal form.
    ///
    /// Accepted: one or more ASCII digits `0`-`9` and nothing else. Leading zeros are accepted
    /// (`"007"` is 7). Rejected: the empty string, `null`, a sign (`+5`, `-1`), any whitespace (`" 5"`),
    /// digits of other scripts (Arabic-Indic, fullwidth, ... which [Integer#parseInt(String)] accepts), and
    /// values above [Integer#MAX_VALUE].
    ///
    /// @param s String to parse
    ///
    /// @return Result containing the parsed value, or a failure naming the rejected input
    static Result<Integer> parseUnsignedDecimalInt(String s) {
        return strictInt(s, Number::notUnsignedDecimalInt);
    }

    /// Same as [#parseUnsignedDecimalInt(String)] but every rejection (format or overflow) fails with the given cause.
    ///
    /// @param s     String to parse
    /// @param cause the failure for any rejected input
    ///
    /// @return Result containing the parsed value, or `cause`
    static Result<Integer> parseUnsignedDecimalInt(String s, Cause cause) {
        return strictInt(s, _ -> cause);
    }

    private static Result<Long> strictLong(String s, Fn1<? extends Cause, ? super String> cause) {
        return Verify.ensure(s, Number::isAsciiDigits, cause)
                     .flatMap(digits -> Result.lift1(_ -> cause.apply(digits), Long::parseLong, digits));
    }

    private static Result<Integer> strictInt(String s, Fn1<? extends Cause, ? super String> cause) {
        return Verify.ensure(s, Number::isAsciiDigits, cause)
                     .flatMap(digits -> Result.lift1(_ -> cause.apply(digits), Integer::parseInt, digits));
    }

    private static boolean isAsciiDigits(String s) {
        return Verify.Is.present(s) && s.chars()
                                        .allMatch(Number::isAsciiDigit);
    }

    private static boolean isAsciiDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static Cause notUnsignedDecimalLong(String s) {
        return Causes.cause("Not an unsigned decimal number in the range of long: " + s);
    }

    private static Cause notUnsignedDecimalInt(String s) {
        return Causes.cause("Not an unsigned decimal number in the range of int: " + s);
    }

    /// Parse a string as a Float value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed Float or parsing error
    static Result<Float> parseFloat(String s) {
        return Result.lift1(Float::parseFloat, s);
    }

    /// Parse a string as a Double value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed Double or parsing error
    static Result<Double> parseDouble(String s) {
        return Result.lift1(Double::parseDouble, s);
    }

    /// Parse a string as a BigInteger value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed BigInteger or parsing error
    static Result<BigInteger> parseBigInteger(String s) {
        return Result.lift1(BigInteger::new, s);
    }

    /// Parse a string as a BigInteger value with specified radix
    ///
    /// @param s String to parse
    /// @param radix The radix to use for parsing
    ///
    /// @return Result containing parsed BigInteger or parsing error
    static Result<BigInteger> parseBigInteger(String s, int radix) {
        return Result.lift2(BigInteger::new, s, radix);
    }

    /// Parse a string as a BigDecimal value
    ///
    /// @param s String to parse
    ///
    /// @return Result containing parsed BigDecimal or parsing error
    static Result<BigDecimal> parseBigDecimal(String s) {
        return Result.lift1(BigDecimal::new, s);
    }

    record unused() implements Number {}
}
