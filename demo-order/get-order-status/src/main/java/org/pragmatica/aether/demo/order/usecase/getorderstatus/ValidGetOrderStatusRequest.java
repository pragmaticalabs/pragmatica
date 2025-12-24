package org.pragmatica.aether.demo.order.usecase.getorderstatus;

import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.lang.Result;

public record ValidGetOrderStatusRequest(OrderId orderId) {

    public static Result<ValidGetOrderStatusRequest> validGetOrderStatusRequest(GetOrderStatusRequest raw) {
        return OrderId.orderId(raw.orderId())
                      .map(ValidGetOrderStatusRequest::new);
    }
}
