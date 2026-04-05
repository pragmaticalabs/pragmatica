package org.pragmatica.aether.example.composition.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.IdGenerator;


/// Order identifier value object.
///
/// Two factory paths:
/// - `orderId(String)` — validates an existing ID (e.g., from DB or request)
/// - `generate()` — creates a new unique ID with "ORD" prefix
public record OrderId(String value) {
    private static final Fn1<Cause, String> INVALID_ORDER_ID = Causes.forOneValue("Invalid order ID: %s");

    public static Result<OrderId> orderId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank, INVALID_ORDER_ID).map(OrderId::new);
    }

    public static OrderId generate() {
        return orderId(IdGenerator.generate("ORD")).expect("OrderId.generate");
    }
}
