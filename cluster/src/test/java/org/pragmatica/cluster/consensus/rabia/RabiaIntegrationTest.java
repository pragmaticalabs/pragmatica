package org.pragmatica.cluster.consensus.rabia;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.SnapshotRequest;
import org.pragmatica.cluster.net.AddressBook;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.NodeInfo;
import org.pragmatica.cluster.net.local.LocalNetwork;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;

import java.io.*;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RabiaIntegrationTest {
    /**
     * A very simple Serializer that uses Java built-in object streams
     * to encode/decode snapshots of the KVStore.
     */
    static class TestSerializer implements org.pragmatica.cluster.net.netty.Serializer {
        @Override
        public byte[] encode(Object msg) {
            try (var baos = new ByteArrayOutputStream();
                 var oos = new ObjectOutputStream(baos)) {
                oos.writeObject(msg);
                return baos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes, Class<T> clazz) {
            try (var bais = new ByteArrayInputStream(bytes);
                 var ois = new ObjectInputStream(bais)) {
                return (T) ois.readObject();
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    record TestAddressBook(int clusterSize) implements AddressBook {

        @Override
        public Option<NodeInfo> get(NodeId id) {
            throw new UnsupportedOperationException("Not implemented");
        }

        @Override
        public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
            throw new UnsupportedOperationException("Not implemented");
        }
    }

    /**
     * Holds a small Rabia cluster wired over a single LocalNetwork.
     */
    static class Cluster {
        final LocalNetwork<RabiaProtocolMessage> network = new LocalNetwork<>();
        final List<NodeId> ids = new ArrayList<>();
        final Map<NodeId, RabiaEngine<RabiaProtocolMessage, KVCommand>> engines = new LinkedHashMap<>();
        final Map<NodeId, KVStore<String, String>> stores = new LinkedHashMap<>();
        final TestAddressBook addressBook;
        final TestSerializer serializer = new TestSerializer();

        Cluster(int size) {
            addressBook = new TestAddressBook(size);
            network.start();

            // create nodes
            for (int i = 1; i <= size; i++) {
                var id = NodeId.create("node-" + i);
                ids.add(id);
                addNewNode(id);
            }

            var firstNode = engines.get(ids.getFirst());

            for (var id : ids) {
                firstNode.processMessage(new SnapshotRequest(id));
            }
        }

        void disconnect(NodeId id) {
            network.disconnect(id);
        }

        void addNewNode(NodeId id) {
            var store = new KVStore<String, String>(serializer);
            var engine = new RabiaEngine<>(id, addressBook, network, store);
            network.addNode(id, engine::processMessage);
            stores.put(id, store);
            engines.put(id, engine);
        }
    }

    /**
     * Reflectively pull out the private `storage` map from KVStore.
     */
    private static Map<String, String> readStorage(KVStore<String, String> store) {
        return store.snapshot();
    }

    @Test
    void threeNodeCluster_agreesAndPropagates() {
        Cluster c = new Cluster(3);

        // submit on node1
        c.engines.get(c.ids.get(0))
                 .submitCommands(List.of(new KVCommand.Put<>("k1", "v1")));

        // await all three having it
        Awaitility.await()
                  .atMost(10, TimeUnit.SECONDS)
                  .until(() -> c.stores.values()
                                       .stream()
                                       .allMatch(s -> "v1".equals(readStorage(s).get("k1"))));

        // submit on node2
        c.engines.get(c.ids.get(1))
                 .submitCommands(List.of(new KVCommand.Put<>("k2", "v2")));

        Awaitility.await()
                  .atMost(2, TimeUnit.SECONDS)
                  .until(() -> c.stores.values()
                                       .stream()
                                       .allMatch(s -> "v2".equals(readStorage(s).get("k2"))));
    }

    @Test
    void fiveNodeCluster_withFailures_andSnapshotJoin() {
        Cluster c = new Cluster(5);

        // initial entry a->1
        c.engines.get(c.ids.getFirst())
                 .submitCommands(List.of(new KVCommand.Put<>("a", "1")));
        Awaitility.await()
                  .atMost(2, TimeUnit.SECONDS)
                  .until(() -> c.stores.values()
                                       .stream()
                                       .allMatch(s -> "1".equals(readStorage(s).get("a"))));

        // fail node1
        c.disconnect(c.ids.get(0));

        // still quorum on 4 nodes: put b->2
        c.engines.get(c.ids.get(1))
                 .submitCommands(List.of(new KVCommand.Put<>("b", "2")));
        Awaitility.await()
                  .atMost(2, TimeUnit.SECONDS)
                  .until(() -> c.ids.subList(1, 5)
                                    .stream()
                                    .allMatch(id -> "2".equals(readStorage(c.stores.get(id)).get("b"))));

        // fail node2
        c.disconnect(c.ids.get(1));

        // still quorum on 3 nodes: put c->3
        c.engines.get(c.ids.get(2))
                 .submitCommands(List.of(new KVCommand.Put<>("c", "3")));

        Awaitility.await()
                  .atMost(2, TimeUnit.SECONDS)
                  .until(() -> c.ids.subList(2, 5)
                                    .stream()
                                    .allMatch(id -> "3".equals(readStorage(c.stores.get(id)).get("c"))));

        // fail node3 → only 2 left, quorum=3 ⇒ no new entries
        c.disconnect(c.ids.get(2));
        var beforeSize = readStorage(c.stores.get(c.ids.get(3))).size();

        c.engines.get(c.ids.get(3))
                 .submitCommands(List.of(new KVCommand.Put<>("d", "4")));
        Awaitility.await()
                  .during(Duration.ofSeconds(1))
                  .atMost(2, TimeUnit.SECONDS)
                  .untilAsserted(() -> assertEquals(beforeSize, readStorage(c.stores.get(c.ids.get(3))).size()));

        // bring up node-6 as a replacement
        NodeId node6 = NodeId.create("node-6");
        c.addNewNode(node6);

        // drive snapshot: have node-6 request from the new node (pick node4)
        c.network.broadcast(new SnapshotRequest(node6));

        // node-6 should eventually have all values: a,b,c
        Awaitility.await()
                  .atMost(2, TimeUnit.SECONDS)
                  .until(() -> {
                      var mem = readStorage(c.stores.get(node6));
                      return "1".equals(mem.get("a"))
                              && "2".equals(mem.get("b"))
                              && "3".equals(mem.get("c"));
                  });

        // now nodes 4,5,6 form a quorum of 3: put e->5
        c.engines.get(c.ids.get(3))
                 .submitCommands(List.of(new KVCommand.Put<>("e", "5")));
        Awaitility.await()
                  .atMost(2, TimeUnit.SECONDS)
                  .until(() -> Stream.of(c.ids.get(3), c.ids.get(4), node6)
                                     .allMatch(id -> "5".equals(readStorage(c.stores.get(id)).get("e"))));
    }
}
