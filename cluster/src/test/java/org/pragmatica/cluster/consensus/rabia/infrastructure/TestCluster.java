package org.pragmatica.cluster.consensus.rabia.infrastructure;

import org.pragmatica.cluster.consensus.rabia.ProtocolConfig;
import org.pragmatica.cluster.consensus.rabia.RabiaEngine;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.QuorumState;
import org.pragmatica.cluster.net.local.LocalNetwork;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Holds a small Rabia cluster wired over a single LocalNetwork.
public class TestCluster {
    private final LocalNetwork<RabiaProtocolMessage> network;
    private final List<NodeId> ids = new ArrayList<>();
    private final Map<NodeId, RabiaEngine<RabiaProtocolMessage, KVCommand>> engines = new LinkedHashMap<>();
    private final Map<NodeId, KVStore<String, String>> stores = new LinkedHashMap<>();
    private final TestAddressBook addressBook;
    private final TestSerializer serializer = new TestSerializer();

    public TestCluster(int size) {
        addressBook = new TestAddressBook(size);
        network = new LocalNetwork<>(addressBook);

        // create nodes
        for (int i = 1; i <= size; i++) {
            var id = NodeId.create("node-" + i);
            ids.add(id);
            addNewNode(id);
        }
        network.start();
    }

    public Map<NodeId, RabiaEngine<RabiaProtocolMessage, KVCommand>> engines() {
        return engines;
    }

    public Map<NodeId, KVStore<String, String>> stores() {
        return stores;
    }

    public NodeId getFirst() {
        return ids.getFirst();
    }

    public List<NodeId> ids() {
        return ids;
    }

    public LocalNetwork<RabiaProtocolMessage> network() {
        return network;
    }

    private void quorumChange(QuorumState quorumState) {
        engines.values()
               .forEach(engine -> engine.quorumState(quorumState));
    }

    public void disconnect(NodeId id) {
        network.disconnect(id);
    }

    public void addNewNode(NodeId id) {
        var store = new KVStore<String, String>(serializer);
        var engine = new RabiaEngine<>(id, addressBook, network, store, ProtocolConfig.testConfig());
        network.observeQuorumState(this::quorumChange);
        network.addNode(id, engine::processMessage);
        stores.put(id, store);
        engines.put(id, engine);
        //store.observeStateChanges(new StateChangePrinter(id));
    }

    public void awaitNode(NodeId nodeId) {
        engines.get(nodeId)
               .startPromise()
               .await(timeSpan(10).seconds())
               .onFailure(cause -> fail("Failed to start " + nodeId.id() + " " + cause));
    }

    public void awaitStart() {
        var promises = engines.values()
                              .stream()
                              .map(RabiaEngine::startPromise)
                              .toList();

        Promise.allOf(promises)
               .await(timeSpan(10).seconds())
               .onFailureRun(() -> fail("Failed to start all nodes within 10 seconds"));
    }

    public void submitAndWait(NodeId nodeId, KVCommand command) {
        engines.get(nodeId)
               .apply(List.of(command))
               .await(timeSpan(10).seconds())
               .onFailure(cause -> fail("Failed to apply command: (a, 1): " + cause));
    }

    public boolean allNodesHaveValue(String k1, String v1) {
        return network.connectedNodes().stream()
                      .allMatch(id -> v1.equals(stores.get(id).snapshot().get(k1)));
    }
}
