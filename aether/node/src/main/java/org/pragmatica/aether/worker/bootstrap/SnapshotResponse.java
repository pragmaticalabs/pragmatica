package org.pragmatica.aether.worker.bootstrap;

import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

/// Response containing the KV store snapshot for bootstrapping.
///
/// @param kvState        serialized KV store state
/// @param sequenceNumber the decision sequence number at the time of the snapshot
@Codec
public record SnapshotResponse(byte[] kvState, long sequenceNumber) implements Message.Wired {
    public static SnapshotResponse snapshotResponse(byte[] kvState, long sequenceNumber) {
        return new SnapshotResponse(kvState, sequenceNumber);
    }
}
