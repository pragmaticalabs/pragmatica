package org.pragmatica.aether.example.composition.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.math.BigDecimal;
import java.math.RoundingMode;


/// Monetary amount value object wrapping BigDecimal.
///
/// Factory method `money(String)` parses and validates the amount,
/// ensuring it is non-negative and scaled to 2 decimal places.
/// This follows the "Parse, Don't Validate" pattern — a Money instance
/// is guaranteed to hold a valid, non-negative amount.
public record Money(BigDecimal value) {
    private static final Fn1<Cause, String> INVALID_AMOUNT = Causes.forOneValue("Invalid amount: %s");
    private static final Fn1<Cause, BigDecimal> NEGATIVE_AMOUNT =
        amount -> new OrderError.InvalidAmount("Amount cannot be negative: " + amount);

    public static Result<Money> money(String raw) {
        return parseAmount(raw)
            .flatMap(Money::fromDecimal);
    }

    public static Result<Money> fromDecimal(BigDecimal amount) {
        return Verify.ensure(amount, a -> a.compareTo(BigDecimal.ZERO) >= 0, NEGATIVE_AMOUNT)
                     .map(a -> a.setScale(2, RoundingMode.HALF_UP))
                     .map(Money::new);
    }

    private static Result<BigDecimal> parseAmount(String raw) {
        return Result.lift(_ -> INVALID_AMOUNT.apply(raw), () -> new BigDecimal(raw));
    }

    @Override
    public String toString() {
        return "$" + value.toPlainString();
    }
}
