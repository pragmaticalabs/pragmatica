package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Pluggable transport for sending replication messages to replica nodes.
/// Implementations are provided by the network layer (node module).
@FunctionalInterface public interface ReplicationTransport {
    @Contract void send(NodeId target, ReplicationMessage message);

    ReplicationTransport NOOP = (_, _) -> {};
}
