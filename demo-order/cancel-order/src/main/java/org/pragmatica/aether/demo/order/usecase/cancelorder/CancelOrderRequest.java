package org.pragmatica.aether.demo.order.usecase.cancelorder;

public record CancelOrderRequest(String orderId, String reason) {}
