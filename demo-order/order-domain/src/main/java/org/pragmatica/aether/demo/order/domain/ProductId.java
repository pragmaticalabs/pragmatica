package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

public record ProductId(String value) {
    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("^PROD-[A-Z0-9]{6}$");
    private static final Fn1<Cause, String> INVALID_PRODUCT_ID = Causes.forValue("Invalid product ID: {}");

    public static Result<ProductId> productId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank)
                     .flatMap(Verify.ensureFn(INVALID_PRODUCT_ID, Verify.Is::matches, PRODUCT_ID_PATTERN))
                     .map(ProductId::new);
    }
}
