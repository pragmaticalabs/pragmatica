package org.pragmatica.aether.demo.order.usecase.getorderstatus;

import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;

import java.time.Instant;
import java.util.List;

public record GetOrderStatusResponse(
    OrderId orderId,
    OrderStatus status,
    Money total,
    List<OrderItem> items,
    Instant createdAt,
    Instant updatedAt
) {
    public record OrderItem(String productId, int quantity, Money unitPrice) {}
}
