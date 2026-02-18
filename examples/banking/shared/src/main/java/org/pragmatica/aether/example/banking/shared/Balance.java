package org.pragmatica.aether.example.banking.shared;

import java.math.BigDecimal;

/// Account balance with available and pending amounts.
public record Balance(Money available, Money pending) {
    public static Balance balance(Money available, Money pending) {
        return new Balance(available, pending);
    }

    public static Balance zero(Currency currency) {
        var zero = new Money(BigDecimal.ZERO, currency);
        return new Balance(zero, zero);
    }
}
