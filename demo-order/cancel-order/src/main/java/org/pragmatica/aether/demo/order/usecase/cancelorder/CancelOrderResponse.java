package org.pragmatica.aether.demo.order.usecase.cancelorder;

import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;

import java.time.Instant;

public record CancelOrderResponse(
    OrderId orderId,
    OrderStatus status,
    String reason,
    Instant cancelledAt
) {}
