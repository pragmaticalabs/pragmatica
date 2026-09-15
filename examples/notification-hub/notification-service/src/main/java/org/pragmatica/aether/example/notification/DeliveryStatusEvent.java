package org.pragmatica.aether.example.notification;

import org.pragmatica.aether.slice.annotation.PartitionKey;
import org.pragmatica.serialization.Codec;


/// Outcome of one delivery attempt, published by the emailer and consumed by analytics.
@Codec
public record DeliveryStatusEvent(@PartitionKey String channel, String senderId, boolean delivered, long timestamp) {}
