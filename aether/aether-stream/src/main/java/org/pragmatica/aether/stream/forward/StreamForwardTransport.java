package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Pluggable transport for sending stream forward messages to other nodes.
/// Implementations are provided by the network layer (node module).
@FunctionalInterface public interface StreamForwardTransport {
    @Contract void send(NodeId target, StreamForwardMessage message);

    StreamForwardTransport NOOP = (_, _) -> {};
}
