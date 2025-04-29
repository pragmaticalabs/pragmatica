package org.pragmatica.cluster.net;

import org.pragmatica.cluster.consensus.ProtocolMessage;

import java.util.function.Consumer;

public interface ClusterNetwork<T extends ProtocolMessage> {

    <M extends ProtocolMessage> void broadcast(M message);

    <M extends ProtocolMessage> void send(NodeId nodeId, M message);

    void connect(NodeId nodeId);

    void disconnect(NodeId nodeId);

    void observeViewChanges(Consumer<ViewChange> observer);

    void listen(Consumer<T> listener);

    void start();

    void stop();

    boolean quorumConnected();
}
