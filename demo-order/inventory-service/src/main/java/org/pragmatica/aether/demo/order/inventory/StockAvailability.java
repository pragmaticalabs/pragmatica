package org.pragmatica.aether.demo.order.inventory;

public record StockAvailability(String productId, int available, boolean sufficient) {}
