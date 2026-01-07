package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.IdGenerator;

public record OrderId(String value) {
    private static final Fn1<Cause, String> INVALID_ORDER_ID = Causes.forOneValue("Invalid order ID: {}");

    public static Result<OrderId> orderId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank, INVALID_ORDER_ID)
                     .map(OrderId::new);
    }

    public static OrderId generate() {
        return new OrderId(IdGenerator.generate("ORD"));
    }
}
