package org.pragmatica.aether.stream.consensus;

import org.pragmatica.consensus.Command;

/// Consensus command for strong-consistency stream publishing.
/// Proposed through Rabia and applied on all nodes in the same total order.
///
/// The payload is defensively copied on construction to ensure immutability,
/// as required by the Rabia consensus protocol.
public record StreamConsensusCommand(String streamName, int partition, byte[] payload, long timestamp) implements Command {

    /// Factory method with defensive copy of payload.
    public static StreamConsensusCommand streamConsensusCommand(String streamName,
                                                               int partition,
                                                               byte[] payload,
                                                               long timestamp) {
        return new StreamConsensusCommand(streamName, partition, payload.clone(), timestamp);
    }

    /// Defensive copy on access — callers cannot mutate the internal payload.
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
