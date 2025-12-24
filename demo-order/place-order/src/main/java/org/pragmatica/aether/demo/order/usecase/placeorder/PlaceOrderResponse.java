package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;

import java.util.List;

public record PlaceOrderResponse(
    OrderId orderId,
    OrderStatus status,
    Money total,
    List<String> reservationIds
) {}
