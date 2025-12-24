package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.UUID;
import java.util.regex.Pattern;

public record OrderId(String value) {
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^ORD-[a-f0-9]{8}$");
    private static final Fn1<Cause, String> INVALID_ORDER_ID = Causes.forValue("Invalid order ID: {}");

    public static Result<OrderId> orderId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank)
                     .flatMap(Verify.ensureFn(INVALID_ORDER_ID, Verify.Is::matches, ORDER_ID_PATTERN))
                     .map(OrderId::new);
    }

    public static OrderId generate() {
        return new OrderId("ORD-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
