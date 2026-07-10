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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.topology.MembershipDecision;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class DHTRebalancerTest {
    private static final NodeId LOCAL = new NodeId("local");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId PEER_B = new NodeId("peer-b");
    private static final NodeId PEER_C = new NodeId("peer-c");
    private static final NodeId PEER_D = new NodeId("peer-d");
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

            listener.onNodeRemoved(MembershipDecision.nodeRemoved(PEER_B, List.of(LOCAL, PEER_A)));

            assertThat(ring.nodes()).doesNotContain(PEER_B);
            assertThat(ring.nodes()).containsExactlyInAnyOrder(LOCAL, PEER_A);
        }
    }

    /// Departing-node self-push (issue #427). A 5-node ring (rf=3) guarantees a genuine post-departure
    /// newcomer for some keys, so the delta target set is non-empty and the loss mode is reproducible.
    @Nested
    class DepartureSelfPush {
        private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);

        private ConsistentHashRing<NodeId> fiveNodeRing() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            ring.addNode(PEER_A);
            ring.addNode(PEER_B);
            ring.addNode(PEER_C);
            ring.addNode(PEER_D);
            return ring;
        }

        @Test
        void pushOnDeparture_uniquelyHeldKey_pushedToNewcomerWithAck() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG);
            var replicationFactor = CONFIG.effectiveReplicationFactor(ring.nodeCount());
            var probe = findKeyWithNewcomer(ring, LOCAL, replicationFactor);
            var expectedTargets = departureTargets(ring, probe, LOCAL, replicationFactor);

            node.putLocal(probe, value("payload")).await();

            var network = new AckingNetwork();
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            network.ackVia(rebalancer::onMigrationDataAck);
            var observer = new RecordingObserver();

            rebalancer.pushOnDeparture(observer).await().onFailure(cause -> Assertions.fail(cause.message()));

            var pushes = network.migrationResponses();
            assertThat(pushes).isNotEmpty();
            pushes.forEach(response -> assertThat(response.ackRequested()).isTrue());
            pushes.forEach(response -> assertThat(response.sender()).isEqualTo(LOCAL));
            assertThat(network.targets()).containsExactlyInAnyOrderElementsOf(expectedTargets);
            assertThat(pushedKeys(pushes)).contains(asString(probe));
            assertThat(observer.incompleteCount()).isZero();
        }

        @Test
        void pushOnDeparture_alreadyReplicatedKey_notReSentToExistingReplicas() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG);
            var replicationFactor = CONFIG.effectiveReplicationFactor(ring.nodeCount());
            var probe = findKeyWithNewcomer(ring, LOCAL, replicationFactor);
            var existingReplicas = existingReplicas(ring, probe, LOCAL, replicationFactor);

            node.putLocal(probe, value("payload")).await();

            var network = new AckingNetwork();
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            network.ackVia(rebalancer::onMigrationDataAck);

            rebalancer.pushOnDeparture(new RecordingObserver()).await();

            assertThat(network.targets()).doesNotContainAnyElementsOf(existingReplicas);
            assertThat(network.targets()).doesNotContain(LOCAL);
        }

        @Test
        void pushOnDeparture_selfAlreadyPruned_pushesToWholeNewSet() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG);
            var replicationFactor = CONFIG.effectiveReplicationFactor(ring.nodeCount());
            var probe = findKeyWithNewcomer(ring, LOCAL, replicationFactor);
            var origReplicas = existingReplicas(ring, probe, LOCAL, replicationFactor);

            node.putLocal(probe, value("payload")).await();

            // Prune-ahead-of-drain ordering (Case B): self leaves the ring BEFORE the push runs.
            ring.removeNode(LOCAL);
            var postSet = ring.nodesFor(probe, CONFIG.effectiveReplicationFactor(ring.nodeCount()));

            var network = new AckingNetwork();
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            network.ackVia(rebalancer::onMigrationDataAck);

            rebalancer.pushOnDeparture(new RecordingObserver()).await().onFailure(cause -> Assertions.fail(cause.message()));

            // With self no longer identifiable in the ring, push to the WHOLE post-departure responsible
            // set: newcomers get the chunk, and existing replicas tolerate the idempotent versioned
            // re-put (the storm guard cannot apply without self in the ring).
            assertThat(network.targets()).containsExactlyInAnyOrderElementsOf(postSet);
            assertThat(network.targets()).containsAll(origReplicas);
            assertThat(network.targets()).doesNotContain(LOCAL);
        }

        @Test
        void pushOnDeparture_budgetOverrun_emitsIncomplete() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG);
            var replicationFactor = CONFIG.effectiveReplicationFactor(ring.nodeCount());
            var probe = findKeyWithNewcomer(ring, LOCAL, replicationFactor);

            node.putLocal(probe, value("payload")).await();

            var network = new CapturingNetwork();  // never acks — forces the budget to expire
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            var observer = new RecordingObserver();

            rebalancer.pushOnDeparture(timeSpan(200).millis(), observer).await().onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(observer.incompleteCount()).isEqualTo(1);
            assertThat(observer.lastKeysAtRisk()).isGreaterThanOrEqualTo(1);
            assertThat(observer.lastSample()).isNotEmpty();
        }

        @Test
        void pushOnDeparture_fullReplication_noOp() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, DHTConfig.FULL);
            node.putLocal(key("k1"), value("v1")).await();
            var network = new CapturingNetwork();
            var rebalancer = dhtRebalancer(node, network, DHTConfig.FULL);
            var observer = new RecordingObserver();

            rebalancer.pushOnDeparture(observer).await().onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(network.captured).isEmpty();
            assertThat(observer.incompleteCount()).isZero();
        }

        @Test
        void pushOnDeparture_emptyStorage_noOp() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG);
            var network = new CapturingNetwork();
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            var observer = new RecordingObserver();

            rebalancer.pushOnDeparture(observer).await().onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(network.captured).isEmpty();
            assertThat(observer.incompleteCount()).isZero();
        }

        private List<String> pushedKeys(List<DHTMessage.MigrationDataResponse> pushes) {
            return pushes.stream()
                         .flatMap(response -> response.entries().stream())
                         .map(kv -> asString(kv.key()))
                         .toList();
        }

        private byte[] findKeyWithNewcomer(ConsistentHashRing<NodeId> ring, NodeId self, int replicationFactor) {
            for (int i = 0; i < 20_000; i++) {
                var candidate = key("dep-probe-" + i);
                if (ring.nodesFor(candidate, replicationFactor).contains(self)
                    && !departureTargets(ring, candidate, self, replicationFactor).isEmpty()) {
                    return candidate;
                }
            }
            throw new AssertionError("no key with a post-departure newcomer found");
        }

        private List<NodeId> departureTargets(ConsistentHashRing<NodeId> ring, byte[] probe, NodeId self, int replicationFactor) {
            var newSet = ring.nodesFor(probe, replicationFactor, candidate -> !candidate.equals(self));
            var existing = existingReplicas(ring, probe, self, replicationFactor);
            return newSet.stream()
                         .filter(candidate -> !existing.contains(candidate))
                         .toList();
        }

        private HashSet<NodeId> existingReplicas(ConsistentHashRing<NodeId> ring, byte[] probe, NodeId self, int replicationFactor) {
            var existing = new HashSet<>(ring.nodesFor(probe, replicationFactor));
            existing.remove(self);
            return existing;
        }

        private String asString(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    // --- Test infrastructure ---

    private record CapturedMessage(NodeId target, ProtocolMessage message) {}
    private static final class CapturingNetwork implements DHTNetwork {
        final CopyOnWriteArrayList<CapturedMessage> captured = new CopyOnWriteArrayList<>();

        @Override
        public void send(NodeId nodeId, ProtocolMessage message) {
            captured.add(new CapturedMessage(nodeId, message));
        }
    }

    /// Captures sends AND, for a departure push (ackRequested), immediately routes a matching
    /// [DHTMessage.MigrationDataAck] back into the sender's rebalancer — modelling a surviving replica
    /// that applies and acknowledges the pushed chunk.
    private static final class AckingNetwork implements DHTNetwork {
        final CopyOnWriteArrayList<CapturedMessage> captured = new CopyOnWriteArrayList<>();
        private final AtomicReference<Consumer<DHTMessage.MigrationDataAck>> ackSink = new AtomicReference<>(ack -> {});

        void ackVia(Consumer<DHTMessage.MigrationDataAck> sink) {
            ackSink.set(sink);
        }

        @Override
        public void send(NodeId target, ProtocolMessage message) {
            captured.add(new CapturedMessage(target, message));
            if (message instanceof DHTMessage.MigrationDataResponse response && response.ackRequested()) {
                ackSink.get().accept(new DHTMessage.MigrationDataAck(response.requestId(), target));
            }
        }

        List<DHTMessage.MigrationDataResponse> migrationResponses() {
            return captured.stream()
                           .map(CapturedMessage::message)
                           .filter(m -> m instanceof DHTMessage.MigrationDataResponse)
                           .map(m -> (DHTMessage.MigrationDataResponse) m)
                           .toList();
        }

        List<NodeId> targets() {
            return captured.stream()
                           .filter(m -> m.message() instanceof DHTMessage.MigrationDataResponse)
                           .map(CapturedMessage::target)
                           .toList();
        }
    }

    /// Records departure-push overrun notifications for assertions.
    private static final class RecordingObserver implements DeparturePushObserver {
        private final AtomicInteger incompleteCount = new AtomicInteger();
        private final AtomicInteger lastKeysAtRisk = new AtomicInteger();
        private final CopyOnWriteArrayList<String> lastSample = new CopyOnWriteArrayList<>();

        @Override
        public void onIncomplete(int keysAtRisk, List<String> sampleKeys) {
            incompleteCount.incrementAndGet();
            lastKeysAtRisk.set(keysAtRisk);
            lastSample.clear();
            lastSample.addAll(sampleKeys);
        }

        int incompleteCount() {
            return incompleteCount.get();
        }

        int lastKeysAtRisk() {
            return lastKeysAtRisk.get();
        }

        List<String> lastSample() {
            return List.copyOf(lastSample);
        }
    }
}
