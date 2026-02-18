package org.pragmatica.aether.example.banking.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

/// Validated ISO 4217 currency code.
public record Currency(String code) {
    private static final Pattern ISO_CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Fn1<Cause, String> INVALID_CURRENCY = Causes.forOneValue("Invalid currency code: %s");

    public static final Currency USD = new Currency("USD");
    public static final Currency EUR = new Currency("EUR");
    public static final Currency GBP = new Currency("GBP");
    public static final Currency JPY = new Currency("JPY");

    public static Result<Currency> currency(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank, INVALID_CURRENCY)
                     .map(String::trim)
                     .map(String::toUpperCase)
                     .filter(INVALID_CURRENCY, ISO_CURRENCY.asMatchPredicate())
                     .map(Currency::new);
    }

    @Override
    public String toString() {
        return code;
    }
}
