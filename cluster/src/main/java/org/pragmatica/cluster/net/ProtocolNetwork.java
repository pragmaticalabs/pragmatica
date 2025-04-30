package org.pragmatica.cluster.net;

import org.pragmatica.cluster.consensus.ProtocolMessage;

public interface ProtocolNetwork<T extends ProtocolMessage> {
    <M extends ProtocolMessage> void broadcast(M message);

    <M extends ProtocolMessage> void send(NodeId nodeId, M message);

    boolean quorumConnected();
}
