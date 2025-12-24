package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.aether.demo.order.domain.Money;

public record OrderTotal(Money subtotal, Money discount, Money total) {}
