package org.pragmatica.aether.demo.order.inventory;

import org.pragmatica.lang.Cause;

public sealed interface InventoryError extends Cause {

    record ProductNotFound(String productId) implements InventoryError {
        @Override
        public String message() {
            return "Product not found: " + productId;
        }
    }

    record InsufficientStock(String productId, int requested, int available) implements InventoryError {
        @Override
        public String message() {
            return "Insufficient stock for " + productId + ": requested " + requested + ", available " + available;
        }
    }

    record ReservationNotFound(String reservationId) implements InventoryError {
        @Override
        public String message() {
            return "Reservation not found: " + reservationId;
        }
    }
}
