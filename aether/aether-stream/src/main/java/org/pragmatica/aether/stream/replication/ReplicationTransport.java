package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

/// Pluggable transport for sending replication messages to replica nodes.
/// Implementations are provided by the network layer (node module).
@FunctionalInterface public interface ReplicationTransport {
    /// Send a replication message to the target node. Fire-and-forget semantics.
    @Contract void send(NodeId target, ReplicationMessage message);

    /// No-op transport for testing or non-replicated streams.
    ReplicationTransport NOOP = (_, _) -> {};
}
