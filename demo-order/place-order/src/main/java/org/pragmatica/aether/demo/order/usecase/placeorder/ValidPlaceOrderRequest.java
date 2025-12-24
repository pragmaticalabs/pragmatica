package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.aether.demo.order.domain.CustomerId;
import org.pragmatica.aether.demo.order.domain.ProductId;
import org.pragmatica.aether.demo.order.domain.Quantity;
import org.pragmatica.lang.Result;

import java.util.List;

public record ValidPlaceOrderRequest(
    CustomerId customerId,
    List<ValidOrderItem> items,
    String discountCode
) {
    public record ValidOrderItem(String productId, int quantity) {}

    public static Result<ValidPlaceOrderRequest> validPlaceOrderRequest(PlaceOrderRequest raw) {
        return CustomerId.customerId(raw.customerId())
                         .flatMap(customerId -> validateItems(raw.items())
                             .map(items -> new ValidPlaceOrderRequest(customerId, items, raw.discountCode())));
    }

    private static Result<List<ValidOrderItem>> validateItems(List<PlaceOrderRequest.OrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            return new PlaceOrderError.InvalidRequest("At least one item required").result();
        }

        var validated = items.stream()
            .map(item -> Result.all(ProductId.productId(item.productId()),
                                    Quantity.quantity(item.quantity()))
                               .map((pid, qty) -> new ValidOrderItem(pid.value(), qty.value())))
            .toList();

        return Result.allOf(validated);
    }
}
