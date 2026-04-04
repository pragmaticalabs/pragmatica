package org.pragmatica.aether.worker.bootstrap;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


/// Request sent by a bootstrapping worker to obtain KV state snapshot.
///
/// @param requester the NodeId of the worker requesting the snapshot
@Codec public record SnapshotRequest(NodeId requester) implements Message.Wired {
    public static SnapshotRequest snapshotRequest(NodeId requester) {
        return new SnapshotRequest(requester);
    }
}
