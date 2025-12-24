package org.pragmatica.aether.demo.order.pricing;

import java.util.List;

public record CalculateTotalRequest(List<LineItem> items, String discountCode) {
    public record LineItem(String productId, int quantity) {}
}
