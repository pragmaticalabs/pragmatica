package org.pragmatica.aether.demo.order.usecase.placeorder;

import java.util.List;

public record PlaceOrderRequest(
    String customerId,
    List<OrderItemRequest> items,
    String discountCode
) {
    public record OrderItemRequest(String productId, int quantity) {}
}
