package org.pragmatica.aether.example.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

/**
 * Non-negative quantity value (0 to MAX_QUANTITY).
 */
public record Quantity(int value) {
    public sealed interface QuantityError extends Cause {
        record Negative(int value) implements QuantityError {
            @Override
            public String message() {
                return "Quantity cannot be negative: " + value;
            }
        }

        record ExceedsMax(int value, int max) implements QuantityError {
            @Override
            public String message() {
                return "Quantity " + value + " exceeds maximum " + max;
            }
        }
    }

    public static final int MAX_QUANTITY = 10_000;
    public static final Quantity ZERO = new Quantity(0);

    public static Result<Quantity> quantity(int value) {
        return Verify.ensure(value,
                             v -> v >= 0,
                             _ -> new QuantityError.Negative(value))
                     .filter(_ -> new QuantityError.ExceedsMax(value, MAX_QUANTITY),
                             v -> v <= MAX_QUANTITY)
                     .map(Quantity::new);
    }

    public Quantity add(Quantity other) {
        return new Quantity(Math.min(value + other.value, MAX_QUANTITY));
    }

    public Quantity subtract(Quantity other) {
        return new Quantity(Math.max(0, value - other.value));
    }

    public boolean isPositive() {
        return value > 0;
    }

    public boolean isZero() {
        return value == 0;
    }

    public boolean isGreaterThan(Quantity other) {
        return value > other.value;
    }

    public boolean isLessThanOrEqual(Quantity other) {
        return value <= other.value;
    }
}
