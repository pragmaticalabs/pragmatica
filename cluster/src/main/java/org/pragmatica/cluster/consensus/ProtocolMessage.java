package org.pragmatica.cluster.consensus;

import org.pragmatica.cluster.net.NodeId;

/// Marker interface for all protocol messages
public interface ProtocolMessage {
    NodeId sender();
}
