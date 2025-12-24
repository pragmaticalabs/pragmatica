package org.pragmatica.aether.demo.order.usecase.cancelorder;

import org.pragmatica.lang.Cause;

public sealed interface CancelOrderError extends Cause {

    record InvalidRequest(String details) implements CancelOrderError {
        @Override
        public String message() {
            return "Invalid request: " + details;
        }
    }

    record OrderNotFound(String orderId) implements CancelOrderError {
        @Override
        public String message() {
            return "Order not found: " + orderId;
        }
    }

    record OrderNotCancellable(String orderId, String reason) implements CancelOrderError {
        @Override
        public String message() {
            return "Order " + orderId + " cannot be cancelled: " + reason;
        }
    }

    record StockReleaseFailed(Cause cause) implements CancelOrderError {
        @Override
        public String message() {
            return "Failed to release stock: " + cause.message();
        }
    }
}
