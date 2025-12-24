package org.pragmatica.aether.demo.order.domain;

import java.math.BigDecimal;

public record Money(BigDecimal amount, Currency currency) {

    public static Money usd(BigDecimal amount) {
        return new Money(amount, Currency.USD);
    }

    public static Money usd(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }

    public Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot add different currencies");
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money multiply(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)), this.currency);
    }

    public Money subtract(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot subtract different currencies");
        }
        return new Money(this.amount.subtract(other.amount), this.currency);
    }
}
