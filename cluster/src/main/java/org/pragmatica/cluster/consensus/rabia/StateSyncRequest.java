package org.pragmatica.cluster.consensus.rabia;

import java.util.UUID;

public record StateSyncRequest(UUID messageId,
                             long timestamp,
                             String senderId,
                             long lastKnownSequence) implements RabiaMessage {
} 