package org.pragmatica.aether.example.shared;

import java.util.List;

import org.pragmatica.lang.Result;


public record LineItem(ProductId productId, Quantity quantity) {
    public static Result<LineItem> lineItem(String productId, int quantity) {
        return Result.all(ProductId.productId(productId),
                          Quantity.quantity(quantity))
                     .map(LineItem::new);
    }

    public static Result<List<LineItem>> lineItems(List<RawLineItem> raw) {
        var results = raw.stream().map(r -> lineItem(r.productId(), r.quantity())).toList();

        return Result.allOf(results);
    }

    public record RawLineItem(String productId, int quantity) {}
}
