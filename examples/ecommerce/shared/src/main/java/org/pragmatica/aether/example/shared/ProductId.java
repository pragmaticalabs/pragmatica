package org.pragmatica.aether.example.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

/**
 * Product identifier - alphanumeric SKU format.
 */
public record ProductId(String value) {
    private static final Fn1<Cause, String> INVALID_PRODUCT_ID = Causes.forOneValue("Invalid product ID: %s");

    public static Result<ProductId> productId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank, INVALID_PRODUCT_ID)
                     .map(String::trim)
                     .map(String::toUpperCase)
                     .filter(INVALID_PRODUCT_ID, ProductId::isValidFormat)
                     .map(ProductId::new);
    }

    private static boolean isValidFormat(String value) {
        return value.length() >= 3 && value.length() <= 20 && value.chars()
                                                                   .allMatch(c -> Character.isLetterOrDigit(c) || c == '-');
    }
}
