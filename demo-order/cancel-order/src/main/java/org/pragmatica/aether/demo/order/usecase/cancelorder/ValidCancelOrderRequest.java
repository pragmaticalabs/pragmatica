package org.pragmatica.aether.demo.order.usecase.cancelorder;

import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.lang.Result;

public record ValidCancelOrderRequest(OrderId orderId, String reason) {

    public static Result<ValidCancelOrderRequest> validCancelOrderRequest(CancelOrderRequest raw) {
        return OrderId.orderId(raw.orderId())
                      .map(orderId -> new ValidCancelOrderRequest(orderId, raw.reason()));
    }
}
