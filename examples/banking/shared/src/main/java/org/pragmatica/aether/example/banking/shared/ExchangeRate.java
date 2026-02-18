package org.pragmatica.aether.example.banking.shared;

import java.math.BigDecimal;
import java.time.Instant;

/// Exchange rate between two currencies at a point in time.
public record ExchangeRate(Currency from,
                           Currency to,
                           BigDecimal rate,
                           Instant timestamp) {

    public static ExchangeRate exchangeRate(Currency from, Currency to, BigDecimal rate) {
        return new ExchangeRate(from, to, rate, Instant.now());
    }
}
