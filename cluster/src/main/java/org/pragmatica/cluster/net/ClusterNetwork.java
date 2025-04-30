package org.pragmatica.cluster.net;

import org.pragmatica.cluster.consensus.ProtocolMessage;

import java.util.function.Consumer;

public interface ClusterNetwork<T extends ProtocolMessage> extends ProtocolNetwork<T> {
    void connect(NodeId nodeId);

    void disconnect(NodeId nodeId);

    void observeViewChanges(Consumer<ViewChange> observer);

    void listen(Consumer<T> listener);

    void start();

    void stop();
}
