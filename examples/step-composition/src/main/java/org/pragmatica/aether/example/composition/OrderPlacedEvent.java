package org.pragmatica.aether.example.composition;

import org.pragmatica.aether.example.composition.shared.Money;
import org.pragmatica.serialization.Codec;

import java.time.Instant;


/// Event published when an order is successfully placed.
///
/// The @Codec annotation triggers compile-time codec generation,
/// enabling automatic serialization/deserialization for pub-sub transport.
///
/// Factory method follows the JBCT naming convention: `orderPlacedEvent(...)`.
@Codec
public record OrderPlacedEvent(String orderId, String customerId, Money amount, Instant timestamp) {

    public static OrderPlacedEvent orderPlacedEvent(String orderId, String customerId, Money amount) {
        return new OrderPlacedEvent(orderId, customerId, amount, Instant.now());
    }
}
