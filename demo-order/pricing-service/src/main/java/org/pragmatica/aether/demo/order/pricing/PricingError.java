package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.lang.Cause;

public sealed interface PricingError extends Cause {

    record PriceNotFound(String productId) implements PricingError {
        @Override
        public String message() {
            return "Price not found for product: " + productId;
        }
    }

    record DiscountCodeInvalid(String code) implements PricingError {
        @Override
        public String message() {
            return "Invalid discount code: " + code;
        }
    }
}
