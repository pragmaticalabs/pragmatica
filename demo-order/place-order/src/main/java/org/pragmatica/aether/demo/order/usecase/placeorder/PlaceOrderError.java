package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.lang.Cause;

public sealed interface PlaceOrderError extends Cause {

    record InvalidRequest(String details) implements PlaceOrderError {
        @Override
        public String message() {
            return "Invalid order request: " + details;
        }
    }

    record InventoryCheckFailed(Cause cause) implements PlaceOrderError {
        @Override
        public String message() {
            return "Inventory check failed: " + cause.message();
        }
    }

    record PricingFailed(Cause cause) implements PlaceOrderError {
        @Override
        public String message() {
            return "Pricing calculation failed: " + cause.message();
        }
    }

    record ReservationFailed(Cause cause) implements PlaceOrderError {
        @Override
        public String message() {
            return "Stock reservation failed: " + cause.message();
        }
    }
}
