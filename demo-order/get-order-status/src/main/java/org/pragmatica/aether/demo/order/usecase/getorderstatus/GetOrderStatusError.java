package org.pragmatica.aether.demo.order.usecase.getorderstatus;

import org.pragmatica.lang.Cause;

public sealed interface GetOrderStatusError extends Cause {

    record InvalidRequest(String details) implements GetOrderStatusError {
        @Override
        public String message() {
            return "Invalid request: " + details;
        }
    }

    record OrderNotFound(String orderId) implements GetOrderStatusError {
        @Override
        public String message() {
            return "Order not found: " + orderId;
        }
    }
}
