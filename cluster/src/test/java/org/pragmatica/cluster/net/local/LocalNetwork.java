package org.pragmatica.cluster.net.local;

import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.net.AddressBook;
import org.pragmatica.cluster.net.ViewChange;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/// Local network implementation suitable for testing purposes
public class LocalNetwork<T extends ProtocolMessage> implements ClusterNetwork<T> {
    private static final Logger logger = LoggerFactory.getLogger(LocalNetwork.class);
    private final Map<NodeId, Consumer<T>> nodes = new ConcurrentHashMap<>();
    private final AddressBook addressBook;

    public LocalNetwork(AddressBook addressBook) {
        this.addressBook = addressBook;
    }

    @Override
    public <M extends ProtocolMessage> void broadcast(M message) {
        nodes.keySet().forEach(nodeId -> send(nodeId, message));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <M extends ProtocolMessage> void send(NodeId nodeId, M message) {
        Thread.ofVirtual().start(() -> {
            nodes.get(nodeId).accept((T) message);
        });
    }

    @Override
    public void connect(NodeId nodeId) {
    }

    @Override
    public void disconnect(NodeId nodeId) {
        nodes.remove(nodeId);
    }

    @Override
    public void observeViewChanges(Consumer<ViewChange> observer) {
    }

    @Override
    public void listen(Consumer<T> listener) {
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean quorumConnected() {
        return nodes.size() >= addressBook.quorumSize();
    }

    public void addNode(NodeId nodeId, Consumer<T> listener) {
        nodes.put(nodeId, listener);
    }
}
