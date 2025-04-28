package org.pragmatica.cluster.net;

import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.consensus.ViewChange;

import java.util.function.Consumer;

public interface ClusterNetwork<T extends ProtocolMessage> {

    void broadcast(T message);

    void send(NodeId nodeId, T message);

    void connect(NodeId nodeId);

    void disconnect(NodeId nodeId);

    void observeViewChanges(Consumer<ViewChange> observer);

    void listen(Consumer<T> listener);

    void start();

    void stop();
}
