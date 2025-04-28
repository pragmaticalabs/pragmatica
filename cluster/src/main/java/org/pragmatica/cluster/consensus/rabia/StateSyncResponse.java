package org.pragmatica.cluster.consensus.rabia;

import java.util.UUID;

public record StateSyncResponse(UUID messageId,
                              long timestamp,
                              String senderId,
                              long sequenceNumber,
                              byte[] stateSnapshot,
                              boolean isComplete) implements RabiaMessage {
} 