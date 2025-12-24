package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.aether.demo.order.domain.Money;

public record ProductPrice(String productId, Money unitPrice) {}
