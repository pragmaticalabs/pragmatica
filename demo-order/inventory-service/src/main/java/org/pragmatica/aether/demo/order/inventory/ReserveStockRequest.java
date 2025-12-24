package org.pragmatica.aether.demo.order.inventory;

public record ReserveStockRequest(String productId, int quantity, String orderId) {}
