package org.pragmatica.aether.demo.order.inventory;

public record CheckStockRequest(String productId, int quantity) {}
