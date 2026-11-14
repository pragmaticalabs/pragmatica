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
package org.pragmatica.lang.vo;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CurrencyTest {
    private static Cause failure(String raw) {
        return Currency.currency(raw)
                       .fold(c -> c, _ -> fail("expected failure for: " + raw));
    }

    private static Currency success(String raw) {
        return Currency.currency(raw)
                       .fold(c -> fail("expected success: " + c.message()), v -> v);
    }

    @Test
    void currency_success_upperCaseCode() {
        assertEquals("USD", success("USD").code());
    }

    @Test
    void currency_success_lowerCaseNormalised() {
        assertEquals("EUR", success("eur").code());
    }

    @Test
    void currency_success_mixedCaseAndWhitespace() {
        assertEquals("JPY", success("  jPy ").code());
    }

    @Test
    void currency_success_fractionDigits() {
        assertEquals(2, success("USD").fractionDigits());
        assertEquals(0, success("JPY").fractionDigits());
    }

    @Test
    void currency_success_toStringIsCode() {
        assertEquals("GBP", success("gbp").toString());
    }

    @Test
    void currency_success_wrapsJdkCurrency() {
        var jdk = java.util.Currency.getInstance("CHF");

        assertEquals(jdk, Currency.currency(jdk).value());
        assertEquals(Currency.currency(jdk), success("chf"));
    }

    @Test
    void currency_failure_unknownCode() {
        var cause = failure("ZZZ");

        assertInstanceOf(Currency.CurrencyError.UnknownCode.class, cause);
        assertEquals("ZZZ", ((Currency.CurrencyError.UnknownCode) cause).code());
        assertTrue(cause.message().contains("ZZZ"));
    }

    @Test
    void currency_failure_unknownCodeIsNormalisedInCause() {
        assertEquals("QQQ", ((Currency.CurrencyError.UnknownCode) failure(" qqq ")).code());
    }

    @Test
    void currency_failure_wrongLength() {
        assertInstanceOf(Currency.CurrencyError.UnknownCode.class, failure("US"));
        assertInstanceOf(Currency.CurrencyError.UnknownCode.class, failure("USDX"));
    }

    @Test
    void currency_failure_blankNullEmpty() {
        assertEquals(Currency.CurrencyError.General.BLANK, failure(null));
        assertEquals(Currency.CurrencyError.General.BLANK, failure(""));
        assertEquals(Currency.CurrencyError.General.BLANK, failure("   "));
    }

    @Test
    void currency_failure_isValidationCause() {
        assertInstanceOf(Cause.Validation.class, failure("ZZZ"));
        assertInstanceOf(Cause.Validation.class, failure(null));
    }
}
