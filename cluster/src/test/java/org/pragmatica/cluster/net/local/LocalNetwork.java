package org.pragmatica.cluster.net.local;

import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.net.*;
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
    private Consumer<QuorumState> quorumObserver = _ -> {};

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
        Thread.ofVirtual().start(() -> nodes.get(nodeId).accept((T) message));
    }

    @Override
    public void connect(NodeId nodeId) {
    }

    @Override
    public void disconnect(NodeId nodeId) {
        nodes.remove(nodeId);
        if (nodes.size() < addressBook.quorumSize() - 1) {
            quorumObserver.accept(QuorumState.DISAPPEARED);
        }
    }

    @Override
    public void observeViewChanges(Consumer<TopologyChange> observer) {
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
        if (nodes.size() == addressBook.quorumSize()) {
            quorumObserver.accept(QuorumState.APPEARED);
        }
    }

    @Override
    public void observeQuorumState(Consumer<QuorumState> quorumObserver) {
        this.quorumObserver = quorumObserver;
    }
}
