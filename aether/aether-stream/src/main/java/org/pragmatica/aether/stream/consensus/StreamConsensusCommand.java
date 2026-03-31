package org.pragmatica.aether.stream.consensus;

import java.util.Arrays;

import org.pragmatica.consensus.Command;

/// Consensus command for strong-consistency stream publishing.
/// Proposed through Rabia and applied on all nodes in the same total order.
///
/// The payload is defensively copied on construction to ensure immutability,
/// as required by the Rabia consensus protocol.
public record StreamConsensusCommand(String streamName, int partition, byte[] payload, long timestamp) implements Command {

    /// Defensive copy of mutable byte array on construction.
    public StreamConsensusCommand {
        payload = payload.clone();
    }

    /// Factory method following JBCT naming convention.
    public static StreamConsensusCommand streamConsensusCommand(String streamName,
                                                               int partition,
                                                               byte[] payload,
                                                               long timestamp) {
        return new StreamConsensusCommand(streamName, partition, payload, timestamp);
    }

    /// Defensive copy on access -- callers cannot mutate the internal payload.
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamConsensusCommand other
               && partition == other.partition
               && timestamp == other.timestamp
               && streamName.equals(other.streamName)
               && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        var result = streamName.hashCode();
        result = 31 * result + partition;
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Long.hashCode(timestamp);
        return result;
    }
}
