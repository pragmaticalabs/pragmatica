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

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// ISO 4217 currency over [java.util.Currency].
///
/// Parsing trims the input and normalises it to upper case (`"usd"` and ` EUR ` are accepted), then
/// looks it up among the currencies the JDK knows. A blank input and an unknown code each give a
/// typed [CurrencyError] that is a [Cause.Validation]. The lookup is a table read, not a caught
/// exception. Components: `value()` (the JDK currency), `code()`, `fractionDigits()`.
///
/// **Prefer this over a hand-rolled currency value object** — see the package doc for the full
/// catalog of built-in value objects.
public record Currency(java.util.Currency value) {
    private static final Map<String, java.util.Currency> KNOWN = java.util.Currency.getAvailableCurrencies()
                                                                                   .stream()
                                                                                   .collect(Collectors.toUnmodifiableMap(java.util.Currency::getCurrencyCode,
                                                                                                                         Function.identity()));

    /// Failures of [Currency#currency(String)].
    public sealed interface CurrencyError extends Cause.Validation {
        /// The code is not an ISO 4217 currency known to the JDK.
        record UnknownCode(String code, String message) implements CurrencyError {
            static final Fn1<UnknownCode, String> FACTORY = Causes.forOneValue("Unknown ISO 4217 currency code: %s",
                                                                               UnknownCode::new);

            /// Create the failure for an unknown code.
            ///
            /// @param code the normalised code that was not found
            /// @return the typed failure
            public static UnknownCode unknownCode(String code) {
                return FACTORY.apply(code);
            }
        }

        /// Fixed-text failures.
        enum General implements CurrencyError {
            BLANK("Currency code must not be null or blank");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }
    }

    /// Parse an ISO 4217 code, case-insensitively.
    ///
    /// @param raw the raw code, e.g. `"usd"`
    /// @return Result containing the Currency, or a [CurrencyError]
    public static Result<Currency> currency(String raw) {
        return Verify.ensure(raw, Verify.Is::present, CurrencyError.General.BLANK)
                     .map(Currency::normalise)
                     .flatMap(Currency::lookup);
    }

    /// Wrap an existing JDK currency.
    ///
    /// @param value the currency to wrap
    /// @return Currency wrapping the provided value
    public static Currency currency(java.util.Currency value) {
        return new Currency(value);
    }

    /// The ISO 4217 alphabetic code, upper case.
    public String code() {
        return value.getCurrencyCode();
    }

    /// The default number of fraction digits, or -1 for a pseudo-currency (as in [java.util.Currency]).
    public int fractionDigits() {
        return value.getDefaultFractionDigits();
    }

    @Override
    public String toString() {
        return code();
    }

    private static String normalise(String raw) {
        return raw.trim()
                  .toUpperCase(Locale.ROOT);
    }

    private static Result<Currency> lookup(String code) {
        return Option.option(KNOWN.get(code))
                     .toResult(CurrencyError.UnknownCode.unknownCode(code))
                     .map(Currency::currency);
    }
}
