package org.pragmatica.aether.worker.bootstrap;

import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

import java.util.Arrays;

/// Response containing the KV store snapshot for bootstrapping.
///
/// @param kvState        serialized KV store state
/// @param sequenceNumber the decision sequence number at the time of the snapshot
@Codec public record SnapshotResponse( byte[] kvState, long sequenceNumber) implements Message.Wired {
    public SnapshotResponse {
        kvState = kvState.clone();
    }

    @Override public byte[] kvState() {
        return kvState.clone();
    }

    @Override public boolean equals(Object o) {
        return o instanceof SnapshotResponse other &&
        sequenceNumber == other.sequenceNumber && Arrays.equals(kvState, other.kvState);
    }

    @Override public int hashCode() {
        return 31 * Arrays.hashCode(kvState) + Long.hashCode(sequenceNumber);
    }

    public static SnapshotResponse snapshotResponse(byte[] kvState, long sequenceNumber) {
        return new SnapshotResponse(kvState, sequenceNumber);
    }
}
