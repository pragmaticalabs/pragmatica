package org.pragmatica.aether.example.banking.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.math.BigDecimal;
import java.math.RoundingMode;

/// Monetary value with currency.
/// Immutable, rounded to 2 decimal places.
public record Money(BigDecimal amount, Currency currency) {
    public sealed interface MoneyError extends Cause {
        record NegativeAmount(BigDecimal amount) implements MoneyError {
            @Override
            public String message() {
                return "Amount cannot be negative: " + amount;
            }
        }

        record CurrencyMismatch(Currency expected, Currency actual) implements MoneyError {
            @Override
            public String message() {
                return "Currency mismatch: expected " + expected + ", got " + actual;
            }
        }
    }

    private static final Fn1<Cause, BigDecimal> NEGATIVE_AMOUNT = MoneyError.NegativeAmount::new;

    public static final Money ZERO_USD = new Money(BigDecimal.ZERO, Currency.USD);

    public static Result<Money> money(BigDecimal amount, Currency currency) {
        return Verify.ensure(amount,
                             a -> a.compareTo(BigDecimal.ZERO) >= 0,
                             NEGATIVE_AMOUNT)
                     .map(a -> a.setScale(2, RoundingMode.HALF_UP))
                     .map(a -> new Money(a, currency));
    }

    public Result<Money> add(Money other) {
        return verifySameCurrency(other)
            .map(_ -> new Money(amount.add(other.amount).setScale(2, RoundingMode.HALF_UP), currency));
    }

    public Result<Money> subtract(Money other) {
        return verifySameCurrency(other)
            .flatMap(_ -> money(amount.subtract(other.amount), currency));
    }

    public Result<Money> multiply(BigDecimal factor) {
        return money(amount.multiply(factor).setScale(2, RoundingMode.HALF_UP), currency);
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    private Result<Money> verifySameCurrency(Money other) {
        return currency.equals(other.currency)
               ? Result.success(this)
               : new MoneyError.CurrencyMismatch(currency, other.currency).result();
    }

    @Override
    public String toString() {
        return currency.code() + " " + amount.toPlainString();
    }
}
