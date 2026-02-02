package org.pragmatica.aether.example.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Monetary value with currency.
 * Immutable, rounded to 2 decimal places.
 */
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
                return "Currency mismatch: " + expected + " vs " + actual;
            }
        }

        record InvalidCurrency(String code) implements MoneyError {
            @Override
            public String message() {
                return "Invalid currency: " + code;
            }
        }

        record InvalidAmount(String raw) implements MoneyError {
            @Override
            public String message() {
                return "Invalid amount: " + raw;
            }
        }
    }

    private static final Fn1<Cause, BigDecimal> NEGATIVE_AMOUNT = MoneyError.NegativeAmount::new;

    public static final Currency USD = Currency.getInstance("USD");
    public static final Currency EUR = Currency.getInstance("EUR");

    public static final Money ZERO_USD = new Money(BigDecimal.ZERO, USD);

    public static Result<Money> money(BigDecimal amount, Currency currency) {
        return Verify.ensure(amount,
                             a -> a.compareTo(BigDecimal.ZERO) >= 0,
                             NEGATIVE_AMOUNT)
                     .map(a -> a.setScale(2, RoundingMode.HALF_UP))
                     .map(a -> new Money(a, currency));
    }

    public static Result<Money> money(String amount, String currencyCode) {
        return Result.all(parseAmount(amount),
                          parseCurrency(currencyCode))
                     .flatMap(Money::money);
    }

    public static Result<Money> usd(BigDecimal amount) {
        return money(amount, USD);
    }

    public static Result<Money> usd(String amount) {
        return parseAmount(amount).flatMap(Money::usd);
    }

    public Result<Money> add(Money other) {
        return verifySameCurrency(other).map(_ -> new Money(amount.add(other.amount), currency));
    }

    public Result<Money> subtract(Money other) {
        return verifySameCurrency(other)
        .map(_ -> new Money(amount.subtract(other.amount)
                                  .max(BigDecimal.ZERO),
                            currency));
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor)
                               .setScale(2, RoundingMode.HALF_UP),
                         currency);
    }

    public Money percentage(int percent) {
        return multiply(BigDecimal.valueOf(percent)
                                  .divide(BigDecimal.valueOf(100),
                                          4,
                                          RoundingMode.HALF_UP));
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public Result<Boolean> isGreaterThan(Money other) {
        return verifySameCurrency(other).map(_ -> amount.compareTo(other.amount) > 0);
    }

    private Result<Money> verifySameCurrency(Money other) {
        return currency.equals(other.currency)
               ? Result.success(this)
               : new MoneyError.CurrencyMismatch(currency, other.currency).result();
    }

    private static Result<BigDecimal> parseAmount(String raw) {
        return Result.lift(_ -> new MoneyError.InvalidAmount(raw), () -> new BigDecimal(raw));
    }

    private static Result<Currency> parseCurrency(String code) {
        return Result.lift(_ -> new MoneyError.InvalidCurrency(code), () -> Currency.getInstance(code));
    }

    @Override
    public String toString() {
        return currency.getSymbol() + amount.toPlainString();
    }
}
