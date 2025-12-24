package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

public record Quantity(int value) {
    private static final Cause INVALID_QUANTITY = Causes.cause("Quantity must be between 1 and 1000");

    public static Result<Quantity> quantity(int raw) {
        return Verify.ensure(raw, Verify.Is::between, 1, 1000)
                     .mapError(_ -> INVALID_QUANTITY)
                     .map(Quantity::new);
    }
}
