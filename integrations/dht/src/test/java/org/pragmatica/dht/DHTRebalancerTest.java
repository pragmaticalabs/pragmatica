/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.dht;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

class DHTRebalancerTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId PEER_B = new NodeId("peer-b");
    private static final NodeId REMOVED = new NodeId("removed");

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class FactoryMethod {
        @Test
        void dhtRebalancer_createsInstance_withValidInputs() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            var node = dhtNode(LOCAL, storage, ring, DHTConfig.DEFAULT);
            var network = new CapturingNetwork();

            var rebalancer = dhtRebalancer(node, network, DHTConfig.DEFAULT);

            assertThat(rebalancer).isNotNull();
        }
    }

    @Nested
    class OnNodeRemoved {
        private CapturingNetwork network;

        @BeforeEach
        void setUp() {
            network = new CapturingNetwork();
        }

        @Test
        void onNodeRemoved_emptyStorage_noMigrationSent() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER_A);
            var config = new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
            var node = dhtNode(LOCAL, storage, ring, config);
            var rebalancer = dhtRebalancer(node, network, config);

            ring.removeNode(PEER_A);
            rebalancer.onNodeRemoved(PEER_A);

            assertThat(migrationMessages()).isEmpty();
        }

        @Test
        void onNodeRemoved_dataExists_pushesToNewReplica() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER_A);
            ring.addNode(REMOVED);
            var config = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
            var node = dhtNode(LOCAL, storage, ring, config);

            // Store enough entries so some partitions where LOCAL is primary have data
            for (int i = 0; i < 50; i++) {
                node.putLocal(key("key-" + i), value("value-" + i)).await();
            }

            var rebalancer = dhtRebalancer(node, network, config);

            ring.removeNode(REMOVED);
            rebalancer.onNodeRemoved(REMOVED);

            var migrations = migrationMessages();
            assertThat(migrations).isNotEmpty();

            // All migration targets should be PEER_A (the only remaining peer)
            migrations.forEach(m -> assertThat(m.target()).isEqualTo(PEER_A));

            // All messages should carry non-empty entries from LOCAL
            migrations.stream()
                      .map(m -> (DHTMessage.MigrationDataResponse) m.message())
                      .forEach(response -> assertThat(response.entries()).isNotEmpty());
            migrations.stream()
                      .map(m -> (DHTMessage.MigrationDataResponse) m.message())
                      .forEach(response -> assertThat(response.sender()).isEqualTo(LOCAL));
        }

        @Test
        void onNodeRemoved_thisNodeNotPrimary_noMigration() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER_A);
            ring.addNode(PEER_B);
            var config = new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
            var node = dhtNode(LOCAL, storage, ring, config);

            // Find a key where LOCAL is NOT primary (another node is first in the replica list)
            var nonPrimaryKey = findKeyWhereNodeIsNotPrimary(ring, LOCAL, config.replicationFactor());
            node.putLocal(key(nonPrimaryKey), value("val")).await();

            var rebalancer = dhtRebalancer(node, network, config);

            ring.removeNode(REMOVED);
            rebalancer.onNodeRemoved(REMOVED);

            // Data on partitions where LOCAL is not primary should not be migrated
            var migratedKeys = migrationMessages().stream()
                                                  .flatMap(m -> ((DHTMessage.MigrationDataResponse) m.message()).entries().stream())
                                                  .map(kv -> new String(kv.key(), StandardCharsets.UTF_8))
                                                  .toList();
            assertThat(migratedKeys).doesNotContain(nonPrimaryKey);
        }

        @Test
        void onNodeRemoved_singleNodeRemaining_noMigrationNeeded() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(REMOVED);
            var config = new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
            var node = dhtNode(LOCAL, storage, ring, config);

            for (int i = 0; i < 20; i++) {
                node.putLocal(key("data-" + i), value("val-" + i)).await();
            }

            var rebalancer = dhtRebalancer(node, network, config);

            ring.removeNode(REMOVED);
            rebalancer.onNodeRemoved(REMOVED);

            // Only LOCAL remains — pushToReplicas skips self, no targets available
            assertThat(migrationMessages()).isEmpty();
        }

        @Test
        void onNodeRemoved_fullReplication_skipsRebalancing() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER_A);
            var node = dhtNode(LOCAL, storage, ring, DHTConfig.FULL);

            node.putLocal(key("k1"), value("v1")).await();

            var rebalancer = dhtRebalancer(node, network, DHTConfig.FULL);

            ring.removeNode(PEER_A);
            rebalancer.onNodeRemoved(PEER_A);

            assertThat(migrationMessages()).isEmpty();
        }

        @Test
        void onNodeRemoved_neverSendsToSelf() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER_A);
            ring.addNode(REMOVED);
            var config = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
            var node = dhtNode(LOCAL, storage, ring, config);

            for (int i = 0; i < 50; i++) {
                node.putLocal(key("self-check-" + i), value("v-" + i)).await();
            }

            var rebalancer = dhtRebalancer(node, network, config);

            ring.removeNode(REMOVED);
            rebalancer.onNodeRemoved(REMOVED);

            var selfMessages = network.captured.stream()
                                               .filter(m -> m.target().equals(LOCAL))
                                               .toList();
            assertThat(selfMessages).isEmpty();
        }

        private CopyOnWriteArrayList<CapturedMessage> migrationMessages() {
            var result = new CopyOnWriteArrayList<CapturedMessage>();
            for (var m : network.captured) {
                if (m.message() instanceof DHTMessage.MigrationDataResponse) {
                    result.add(m);
                }
            }
            return result;
        }

        private String findKeyWhereNodeIsNotPrimary(ConsistentHashRing<NodeId> ring, NodeId node, int replicationFactor) {
            for (int i = 0; i < 10000; i++) {
                var candidate = "probe-" + i;
                var nodes = ring.nodesFor(key(candidate), replicationFactor);
                if (!nodes.isEmpty() && !nodes.getFirst().equals(node)) {
                    return candidate;
                }
            }
            throw new AssertionError("Could not find a key where node is not primary");
        }
    }

    @Nested
    class TopologyListenerIntegration {
        @Test
        void topologyListener_triggersRebalance_onNodeRemoved() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER_A);
            ring.addNode(PEER_B);
            var config = new DHTConfig(2, 1, 1, DHTConfig.DEFAULT_TIMEOUT);
            var node = dhtNode(LOCAL, storage, ring, config);
            var network = new CapturingNetwork();
            var rebalancer = dhtRebalancer(node, network, config);
            var listener = DHTTopologyListener.dhtTopologyListener(node, rebalancer);

            storage.put(key("item"), value("data")).await();

            listener.onNodeRemoved(new TopologyChangeNotification.NodeRemoved(PEER_B, List.of(LOCAL, PEER_A)));

            assertThat(ring.nodes()).doesNotContain(PEER_B);
            assertThat(ring.nodes()).containsExactlyInAnyOrder(LOCAL, PEER_A);
        }
    }

    // --- Test infrastructure ---

    private record CapturedMessage(NodeId target, ProtocolMessage message) {}

    private static final class CapturingNetwork implements ClusterNetwork {
        final CopyOnWriteArrayList<CapturedMessage> captured = new CopyOnWriteArrayList<>();

        @Override
        public <M extends ProtocolMessage> Unit broadcast(M message) {
            return Unit.unit();
        }

        @Override
        public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
            captured.add(new CapturedMessage(nodeId, message));
            return Unit.unit();
        }

        @Override
        public void connect(NetworkServiceMessage.ConnectNode connectNode) {}

        @Override
        public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}

        @Override
        public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}

        @Override
        public void handleSend(NetworkServiceMessage.Send send) {}

        @Override
        public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

        @Override
        public void handlePing(NetworkMessage.Ping ping) {}

        @Override
        public void handlePong(NetworkMessage.Pong pong) {}

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public int connectedNodeCount() {
            return 0;
        }

        @Override
        public Set<NodeId> connectedPeers() {
            return Set.of();
        }

        @Override
        public Option<Server> server() {
            return Option.none();
        }
    }
}
