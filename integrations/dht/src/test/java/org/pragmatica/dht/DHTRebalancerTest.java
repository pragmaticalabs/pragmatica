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
import org.junit.jupiter.api.Timeout;
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
import java.util.function.Function;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.pragmatica.lang.Promise;
import org.pragmatica.consensus.net.WriteOutcome;

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

    /// #2034: sole-copy departure pushes are resent until an applied ack, retargeted only on ring removal, and reported.
    @Nested
    class SoleCopyDeparture {
        private static final DHTConfig RF1 = new DHTConfig(1, 1, 1, DHTConfig.DEFAULT_TIMEOUT);

        /// T1 (RED at 5e3704e08a: the page was sent once). Legacy budget overload, RF 1, the first push is lost.
        @Test
        @Timeout(20)
        void pushOnDeparture_soleCopyFirstPushDropped_resentAndAcked() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            var probe = soleKey(ring, "t1");
            node.putLocal(probe, value("payload")).await();
            var network = ScriptedNetwork.scripted((index, _) -> index == 0 ? Reply.DROP : Reply.ACK);
            var rebalancer = dhtRebalancer(node, network, RF1);
            network.ackVia(rebalancer::onMigrationDataAck);
            var observer = new RecordingObserver();

            rebalancer.pushOnDeparture(timeSpan(2).seconds(), observer).await().onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(network.sends()).as("the dropped page was resent").hasSizeGreaterThanOrEqualTo(2);
            assertThat(network.ackedKeys()).contains(new String(probe, StandardCharsets.UTF_8));
            assertThat(observer.incompleteCount()).isZero();
        }

        private static final DHTConfig RF2 = new DHTConfig(2, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
        private static final DHTConfig RF3 = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);

        /// T3 (H1): 3 nodes at RF 3, one draining: both survivors hold the key, so it is replicated and never raises AT_RISK.
        @Test
        @Timeout(20)
        void pushOnDeparture_threeNodeRf3_neverAcked_isReplicated() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A, PEER_B).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF3);
            node.putLocal(key("t3"), value("payload")).await();
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(300, 30_000, 50), Set.of());

            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.recorder().incomplete()).hasSize(1);
            assertThat(run.recorder().incomplete().getFirst().sole()).isZero();
            assertThat(run.report().soleCopyAtRisk()).isZero();
            assertThat(run.elapsedMillis()).as("base window only, never the 30 s sole extra").isLessThan(10_000);
        }

        /// T4 (H1): five nodes at RF 3, a key with a newcomer: two survivors, replicated.
        @Test
        @Timeout(20)
        void pushOnDeparture_fiveNodeRf3_neverAcked_isReplicated() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF3);
            node.putLocal(keyIncluding(ring, 3, LOCAL, "t4"), value("payload")).await();
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(300, 30_000, 50), Set.of());

            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.recorder().incomplete()).hasSize(1);
            assertThat(run.report().soleCopyAtRisk()).isZero();
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
            assertThat(run.elapsedMillis()).isLessThan(10_000);
        }

        /// T4b: self already pruned. RF 1: sole (1 - 1 = 0). RF 3 on 5 nodes pruned to 4: replicated (3 - 1 = 2); and (E2) it is
        /// never promoted at `baseDeadline`, because its plan-time survivors are unknown: no AT_RISK, and the push ends at base.
        @Test
        @Timeout(20)
        void classify_pruneAhead_usesHolderLowerBound() {
            var soleRing = fiveNodeRing();
            var soleNode = dhtNode(LOCAL, memoryStorageEngine(), soleRing, RF1);
            soleNode.putLocal(soleKey(soleRing, "t4b-sole"), value("payload")).await();
            soleRing.removeNode(LOCAL);
            var sole = push(soleNode, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(200, 300, 50), Set.of());

            assertThat(sole.report().soleCopyAtRisk()).isEqualTo(1);
            assertThat(sole.recorder().atRisk()).containsExactly(1);

            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF3);
            node.putLocal(keyIncluding(ring, 3, LOCAL, "t4b-repl"), value("payload")).await();
            ring.removeNode(LOCAL);
            var replicated = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(200, 2_000, 50), Set.of());

            assertThat(replicated.report().soleCopyAtRisk()).isZero();
            assertThat(replicated.report().replicatedAtRisk()).isEqualTo(1);
            assertThat(replicated.recorder().atRisk()).as("never promoted past base").isEmpty();
            assertThat(replicated.elapsedMillis()).as("ends at base, not at base + sole extra").isLessThan(1_500);
        }

        /// E1: self is in the ring, RF 2, the co-drainer B already pruned. The ring put a node that never held the key into B's slot,
        /// so `currentSet \ leaving` names one "survivor"; the bound subtracts the pruned B: sole, AT_RISK fires.
        @Test
        @Timeout(20)
        void classify_coDrainerPruned_selfInRing_isSole() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            node.putLocal(keyHeldBy(ring, 2, Set.of(LOCAL, PEER_B), "e1"), value("payload")).await();
            ring.removeNode(PEER_B);
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(200, 300, 50), Set.of(PEER_B));

            assertThat(run.recorder().atRisk()).containsExactly(1);
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// T5: a sole page never acked is resent, raises AT_RISK exactly once, and is reported sole.
        @Test
        @Timeout(20)
        void pushOnDeparture_soleNeverAcked_atRiskOnceThenIncomplete() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t5"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var run = push(node, network, T(200, 1_000, 50), Set.of());

            assertThat(network.sends()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(run.recorder().atRisk()).containsExactly(1);
            assertThat(run.recorder().delivered()).isEmpty();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// T6: the target starts acking once AT_RISK was raised; DELIVERED closes it and nothing is reported.
        @Test
        @Timeout(20)
        void pushOnDeparture_soleAckReleasedOnAtRisk_deliveredRecovers() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t6"), value("payload")).await();
            var gate = new AtomicBoolean(false);
            var network = ScriptedNetwork.scripted((_, _) -> gate.get() ? Reply.ACK : Reply.DROP);
            var recorder = new EventRecorder(() -> gate.set(true));
            var run = push(node, network, T(200, 5_000, 50), Set.of(), recorder);

            assertThat(recorder.atRisk()).containsExactly(1);
            assertThat(recorder.delivered()).containsExactly(1);
            assertThat(recorder.incomplete()).isEmpty();
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        /// T7 (H2): a refused send proves nothing about the ring, so the SAME target is retried; the push never carries a stray.
        @Test
        @Timeout(30)
        void pushOnDeparture_refusedButInRing_retriesSameTarget() {
            List<Function<NodeId, WriteOutcome>> refusals = List.of(WriteOutcome.ConnectionDead::new,
                                                                    WriteOutcome.NoPeerState::new,
                                                                    WriteOutcome.BackpressureRefused::new);
            refusals.forEach(this::refusedTwiceThenAcked);
        }

        private void refusedTwiceThenAcked(Function<NodeId, WriteOutcome> refusal) {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t7"), value("payload")).await();
            var network = ScriptedNetwork.scripted((index, _) -> index < 2 ? Reply.refuse(refusal) : Reply.ACK);
            var run = push(node, network, T(2_000, 2_000, 50), Set.of());

            assertThat(network.sends()).hasSize(3);
            assertThat(Set.copyOf(network.targets())).as("one target, never replaced").hasSize(1);
            network.sends().forEach(sent -> assertThat(sent.response().leaving()).containsExactly(LOCAL));
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        /// T7b: EncodeFailed is final for the page: one send, reported.
        @Test
        @Timeout(20)
        void pushOnDeparture_encodeFailed_isFinalAndReported() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t7b"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.refuse(id -> new WriteOutcome.EncodeFailed(id, "MigrationDataResponse")));
            var run = push(node, network, T(200, 300, 50), Set.of());

            assertThat(network.sends()).hasSize(1);
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// T8 (H2): the target leaves the ring after losing the first push; the page goes to the next responsible node, and the push
        /// carries the removed node in `leaving`.
        @Test
        @Timeout(20)
        void pushOnDeparture_targetRemovedFromRing_retargetsWithLeaving() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            var probe = soleKey(ring, "t8");
            node.putLocal(probe, value("payload")).await();
            var network = ScriptedNetwork.scripted((index, sent) -> index == 0 ? removeAndDrop(ring, sent.target()) : Reply.ACK);
            var run = push(node, network, T(2_000, 2_000, 50), Set.of());
            var first = network.sends().getFirst().target();
            var expected = ring.nodesFor(probe, 1, candidate -> !candidate.equals(LOCAL)).getFirst();

            assertThat(network.sends()).hasSize(2);
            assertThat(network.sends().get(1).target()).isEqualTo(expected).isNotEqualTo(first);
            assertThat(network.sends().get(1).response().leaving()).contains(first, LOCAL);
            assertThat(network.ackedKeys()).contains(new String(probe, StandardCharsets.UTF_8));
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        private static Reply removeAndDrop(ConsistentHashRing<NodeId> ring, NodeId target) {
            ring.removeNode(target);
            return Reply.DROP;
        }

        /// T9 (F1): a nack is resent to the same target, never retargeted.
        @Test
        @Timeout(20)
        void pushOnDeparture_nackThenApplied_noReport() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t9"), value("payload")).await();
            var network = ScriptedNetwork.scripted((index, _) -> index < 1 ? Reply.ASYNC_NACK : Reply.ASYNC_ACK);
            var run = push(node, network, T(2_000, 2_000, 50), Set.of());

            assertThat(network.sends()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(Set.copyOf(network.targets())).hasSize(1);
            assertThat(run.recorder().incomplete()).isEmpty();
            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        /// T10 (F1): a persistent nack backs off (50, 100, 200, 400, 400 ms) instead of resending every 50 ms. The cap is 400 ms
        /// here, overriding the T() default of 4 x resend = 200 ms. Load can only lower the count, so the upper bound is safe.
        @Test
        @Timeout(20)
        void pushOnDeparture_persistentNack_backsOff() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t10"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.NACK);
            var tuning = new DeparturePushTuning(ms(100), ms(1_200), ms(50), ms(400), ms(200));
            var run = push(node, network, tuning, Set.of());

            assertThat(network.sends()).hasSizeBetween(2, 12);
            assertThat(run.recorder().atRisk()).containsExactly(1);
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// T11: every other node is co-departing, so the sole key has no target: reported at plan time, never sent.
        @Test
        @Timeout(20)
        void pushOnDeparture_unpushableSole_reportedAtPlanNoSend() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(key("t11"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var run = push(node, network, T(5_000, 30_000, 50), Set.of(PEER_A));

            assertThat(network.sends()).isEmpty();
            assertThat(run.recorder().atRisk()).containsExactly(1);
            assertThat(run.elapsedMillis()).isLessThan(3_000);
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// T12 (N2): one target owed a sole key and a replicated key. Every sole page is lost; the replicated page travels in its own
        /// chain, so it is acked within the base window.
        @Test
        @Timeout(20)
        void pushOnDeparture_stuckSolePage_doesNotStarveReplicatedChain() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            var shape = sharedTargetShape(ring);
            node.putLocal(shape.sole(), value("sole")).await();
            node.putLocal(shape.replicated(), value("replicated")).await();
            var soleText = new String(shape.sole(), StandardCharsets.UTF_8);
            var network = ScriptedNetwork.scripted((_, sent) -> carries(sent, soleText) ? Reply.DROP : Reply.ACK);
            var run = push(node, network, T(300, 1_000, 50), Set.of(PEER_C));

            assertThat(network.ackedKeys()).contains(new String(shape.replicated(), StandardCharsets.UTF_8));
            assertThat(run.report().replicatedAtRisk()).isZero();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
        }

        /// T13 (F7): the only plan-time survivor E leaves the ring before `baseDeadline`; the replicated key is promoted to sole, raises
        /// AT_RISK, and its page keeps being resent past base.
        @Test
        @Timeout(20)
        void pushOnDeparture_existingReplicaRemovedBeforeBase_promotedToSole() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            var probe = keyHeldBy(ring, 2, Set.of(LOCAL, PEER_A), "t13");
            node.putLocal(probe, value("payload")).await();
            var network = ScriptedNetwork.scripted((index, _) -> index == 0 ? removeAndDrop(ring, PEER_A) : Reply.DROP);
            var t0 = System.nanoTime();
            var run = push(node, network, T(1_500, 3_000, 50), Set.of());

            assertThat(run.recorder().atRisk()).containsExactly(1);
            assertThat(network.sends()).anyMatch(sent -> sent.atNanos() - t0 > ms(1_500).nanos());
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// T14 (F2): a window already used up sends nothing and reports every sole key.
        @Test
        @Timeout(20)
        void pushOnDeparture_zeroWindow_reportsWithoutSending() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t14"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
            var recorder = new EventRecorder(() -> {});
            var started = System.nanoTime();
            var report = dhtRebalancer(node, network, RF1).pushOnDeparture(DepartureWindow.departureWindow(started - ms(1).nanos()),
                                                                        T(200, 300, 50),
                                                                        Set.of(),
                                                                        recorder)
                                                          .await()
                                                          .unwrap();

            assertThat(network.sends()).isEmpty();
            assertThat(recorder.atRisk()).containsExactly(1);
            assertThat(report.soleCopyAtRisk()).isEqualTo(1);
            assertThat(System.nanoTime() - started).isLessThan(ms(3_000).nanos());
        }

        /// T14b (F2): an inventory that never enumerates is bounded by the window; the at-risk set is reported unknown.
        @Test
        @Timeout(5)
        void pushOnDeparture_stampsHang_reportsUnknownAtRisk() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, stampsVia(_ -> Promise.promise()), ring, RF1);
            var recorder = new EventRecorder(() -> {});
            var report = dhtRebalancer(node, ScriptedNetwork.scripted((_, _) -> Reply.ACK), RF1)
                             .pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(300).nanos()),
                                              T(10_000, 10_000, 50),
                                              Set.of(),
                                              recorder)
                             .await()
                             .unwrap();

            assertThat(recorder.atRisk()).containsExactly(0);
            assertThat(report.enumerated()).isFalse();
            assertThat(report.dataAtRisk()).isTrue();
        }

        /// T15 (F2): the windows are anchored before enumeration and clipped by the absolute deadline.
        @Test
        @Timeout(20)
        void pushOnDeparture_deadlineAnchoredBeforeEnumeration() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, stampsVia(memory -> Promise.promise(ms(300), () -> memory.stamps().await())), ring, RF1);
            node.putLocal(soleKey(ring, "t15"), value("payload")).await();
            var started = System.nanoTime();
            var report = dhtRebalancer(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), RF1)
                             .pushOnDeparture(DepartureWindow.departureWindow(started + ms(2_500).nanos()),
                                              T(10_000, 10_000, 50),
                                              Set.of(),
                                              new EventRecorder(() -> {}))
                             .await()
                             .unwrap();

            assertThat(report.soleCopyAtRisk()).isEqualTo(1);
            assertThat(System.nanoTime() - started).isLessThan(ms(5_000).nanos());
        }

        /// T16 (N3): once the node halted the loop stops at once and sends nothing more.
        @Test
        @Timeout(20)
        void pushOnDeparture_halted_stopsResending() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t16"), value("payload")).await();
            var halted = new AtomicBoolean(false);
            var network = ScriptedNetwork.scripted((_, _) -> haltAndDrop(halted));
            var started = System.nanoTime();

            dhtRebalancer(node, network, RF1).pushOnDeparture(DepartureWindow.departureWindow(started + ms(60_000).nanos(), halted::get),
                                                              T(10_000, 10_000, 50),
                                                              Set.of(),
                                                              new EventRecorder(() -> {}))
                                             .await();

            assertThat(network.sends()).hasSize(1);
            assertThat(System.nanoTime() - started).isLessThan(ms(3_000).nanos());
        }

        private static Reply haltAndDrop(AtomicBoolean halted) {
            halted.set(true);
            return Reply.DROP;
        }

        /// T17 (F5): a network that nacks INSIDE `send` finds the attempt already installed, so the nack is not lost to the 5 s
        /// attempt timeout.
        @Test
        @Timeout(20)
        void pushOnDeparture_syncNackThenSyncAck_attemptInstalledBeforeSend() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t17"), value("payload")).await();
            var network = ScriptedNetwork.scripted((index, _) -> index == 0 ? Reply.NACK : Reply.ACK);
            var tuning = new DeparturePushTuning(ms(10_000), ms(10_000), ms(5_000), ms(50), ms(200));
            var run = push(node, network, tuning, Set.of());

            assertThat(network.sends()).hasSize(2);
            assertThat(run.elapsedMillis()).isLessThan(2_000);
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        // --- fixtures ---

        private record Run(DeparturePushReport report, EventRecorder recorder, long elapsedMillis) {}

        private Run push(DHTNode node, ScriptedNetwork network, DeparturePushTuning tuning, Set<NodeId> coDeparting) {
            return push(node, network, tuning, coDeparting, new EventRecorder(() -> {}));
        }

        private Run push(DHTNode node,
                         ScriptedNetwork network,
                         DeparturePushTuning tuning,
                         Set<NodeId> coDeparting,
                         EventRecorder recorder) {
            var rebalancer = dhtRebalancer(node, network, node.config());
            network.ackVia(rebalancer::onMigrationDataAck);
            var started = System.nanoTime();
            var report = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(started + ms(60_000).nanos()),
                                                    tuning,
                                                    coDeparting,
                                                    recorder)
                                   .await()
                                   .unwrap();
            return new Run(report, recorder, (System.nanoTime() - started) / 1_000_000);
        }

        private DeparturePushTuning T(long baseMs, long extraMs, long resendMs) {
            return new DeparturePushTuning(ms(baseMs), ms(extraMs), ms(resendMs), ms(4 * resendMs), ms(200));
        }

        private static TimeSpan ms(long millis) {
            return timeSpan(millis).millis();
        }

        private static boolean carries(TimedSend sent, String keyText) {
            return sent.response().entries().stream().anyMatch(kv -> new String(kv.key(), StandardCharsets.UTF_8).equals(keyText));
        }

        /// A key whose responsible set at `rf` is exactly `holders`.
        private byte[] keyHeldBy(ConsistentHashRing<NodeId> ring, int rf, Set<NodeId> holders, String prefix) {
            for (int i = 0; i < 20_000; i++) {
                var candidate = key(prefix + "-" + i);
                if (Set.copyOf(ring.nodesFor(candidate, rf)).equals(holders)) {
                    return candidate;
                }
            }
            throw new AssertionError("no key held by exactly " + holders);
        }

        private byte[] keyIncluding(ConsistentHashRing<NodeId> ring, int rf, NodeId holder, String prefix) {
            for (int i = 0; i < 20_000; i++) {
                var candidate = key(prefix + "-" + i);
                if (ring.nodesFor(candidate, rf).contains(holder)) {
                    return candidate;
                }
            }
            throw new AssertionError("no key held by " + holder);
        }

        private record SharedTarget(byte[] sole, byte[] replicated) {}

        /// RF 2 with C co-departing: a sole key (replicas {LOCAL, C}) and a replicated key (replicas {LOCAL, B}) both owed to one target.
        private SharedTarget sharedTargetShape(ConsistentHashRing<NodeId> ring) {
            for (int i = 0; i < 2_000; i++) {
                var replicated = keyHeldBy(ring, 2, Set.of(LOCAL, PEER_B), "t12-r" + i);
                var target = ring.nodesFor(replicated, 2, candidate -> !candidate.equals(LOCAL) && !candidate.equals(PEER_C))
                                 .stream().filter(candidate -> !candidate.equals(PEER_B)).findFirst().orElseThrow();
                for (int j = 0; j < 2_000; j++) {
                    var sole = keyHeldBy(ring, 2, Set.of(LOCAL, PEER_C), "t12-s" + i + "-" + j);
                    if (ring.nodesFor(sole, 2, candidate -> !candidate.equals(LOCAL) && !candidate.equals(PEER_C)).contains(target)) {
                        return new SharedTarget(sole, replicated);
                    }
                }
            }
            throw new AssertionError("no shared-target shape");
        }

        /// A memory engine whose `stamps()` is replaced.
        private StorageEngine stampsVia(Function<StorageEngine, Promise<List<org.pragmatica.dht.storage.KeyStamp>>> stamps) {
            var memory = memoryStorageEngine();
            return (StorageEngine) Proxy.newProxyInstance(StorageEngine.class.getClassLoader(),
                                                          new Class<?>[]{StorageEngine.class},
                                                          (_, method, args) -> method.getName().equals("stamps") && (args == null || args.length == 0)
                                                                               ? stamps.apply(memory)
                                                                               : invoke(method, memory, args));
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private ConsistentHashRing<NodeId> fiveNodeRing() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A, PEER_B, PEER_C, PEER_D).forEach(ring::addNode);
            return ring;
        }

        /// A key LOCAL alone is responsible for at RF 1.
        private byte[] soleKey(ConsistentHashRing<NodeId> ring, String prefix) {
            for (int i = 0; i < 20_000; i++) {
                var candidate = key(prefix + "-" + i);
                if (ring.nodesFor(candidate, 1).equals(List.of(LOCAL))) {
                    return candidate;
                }
            }
            throw new AssertionError("no RF-1 key held by LOCAL");
        }
    }

    // --- Test infrastructure ---

    private record CapturedMessage(NodeId target, ProtocolMessage message) {}
    private static final class CapturingNetwork implements DHTNetwork {
        final CopyOnWriteArrayList<CapturedMessage> captured = new CopyOnWriteArrayList<>();

        @Override
        public Promise<WriteOutcome> send(NodeId nodeId, ProtocolMessage message) {
            captured.add(new CapturedMessage(nodeId, message));

            return Promise.success(new WriteOutcome.Sent(nodeId));
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
        public Promise<WriteOutcome> send(NodeId target, ProtocolMessage message) {
            captured.add(new CapturedMessage(target, message));
            if (message instanceof DHTMessage.MigrationDataResponse response && response.ackRequested()) {
                ackSink.get().accept(new DHTMessage.MigrationDataAck(response.requestId(), target, true));
            }

            return Promise.success(new WriteOutcome.Sent(target));
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

    /// What a [ScriptedNetwork] does with one departure push.
    private record Reply(String kind, boolean async, Function<NodeId, WriteOutcome> refusal) {
        static final Reply DROP = new Reply("drop", false, null);
        static final Reply ACK = new Reply("ack", false, null);
        static final Reply NACK = new Reply("nack", false, null);
        static final Reply ASYNC_ACK = new Reply("ack", true, null);
        static final Reply ASYNC_NACK = new Reply("nack", true, null);

        static Reply refuse(Function<NodeId, WriteOutcome> refusal) {
            return new Reply("refuse", false, refusal);
        }
    }

    private record TimedSend(NodeId target, DHTMessage.MigrationDataResponse response, long atNanos) {}

    /// A network double whose answer to the n-th departure push (0-based, across all targets) is scripted. An ack or nack is routed
    /// to the sink synchronously inside `send` unless the reply is async. Non-departure messages are captured and dropped.
    private static final class ScriptedNetwork implements DHTNetwork {
        private final CopyOnWriteArrayList<TimedSend> sends = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> ackedKeys = new CopyOnWriteArrayList<>();
        private final AtomicReference<Consumer<DHTMessage.MigrationDataAck>> ackSink = new AtomicReference<>(ack -> {});
        private final java.util.function.BiFunction<Integer, TimedSend, Reply> script;

        private ScriptedNetwork(java.util.function.BiFunction<Integer, TimedSend, Reply> script) {
            this.script = script;
        }

        static ScriptedNetwork scripted(java.util.function.BiFunction<Integer, TimedSend, Reply> script) {
            return new ScriptedNetwork(script);
        }

        void ackVia(Consumer<DHTMessage.MigrationDataAck> sink) {
            ackSink.set(sink);
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, ProtocolMessage message) {
            if (!(message instanceof DHTMessage.MigrationDataResponse response) || !response.ackRequested()) {
                return Promise.success(new WriteOutcome.Sent(target));
            }
            var sent = new TimedSend(target, response, System.nanoTime());
            int index;
            synchronized (sends) {
                index = sends.size();
                sends.add(sent);
            }
            var reply = script.apply(index, sent);
            switch (reply.kind()) {
                case "ack" -> answer(sent, true, reply.async());
                case "nack" -> answer(sent, false, reply.async());
                case "refuse" -> {
                    return Promise.success(reply.refusal().apply(target));
                }
                default -> { }
            }
            return Promise.success(new WriteOutcome.Sent(target));
        }

        private void answer(TimedSend sent, boolean applied, boolean async) {
            var ack = new DHTMessage.MigrationDataAck(sent.response().requestId(), sent.target(), applied);
            if (applied) {
                sent.response().entries().forEach(kv -> ackedKeys.add(new String(kv.key(), StandardCharsets.UTF_8)));
            }
            if (async) {
                CompletableFuture.runAsync(() -> ackSink.get().accept(ack),
                                           CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS));
            } else {
                ackSink.get().accept(ack);
            }
        }

        List<TimedSend> sends() {
            return List.copyOf(sends);
        }

        List<NodeId> targets() {
            return sends.stream().map(TimedSend::target).toList();
        }

        List<String> ackedKeys() {
            return List.copyOf(ackedKeys);
        }
    }

    private record Incomplete(int keys, int sole) {}

    /// Records the #2034 departure events; every event's promise settles at once. `onAtRisk` runs inside the at-risk callback.
    private static final class EventRecorder implements DeparturePushObserver {
        private final CopyOnWriteArrayList<Integer> atRisk = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Integer> delivered = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Incomplete> incomplete = new CopyOnWriteArrayList<>();
        private final Runnable onAtRisk;

        EventRecorder(Runnable onAtRisk) {
            this.onAtRisk = onAtRisk;
        }

        @Override
        public void onIncomplete(int keysAtRisk, List<String> sampleKeys) {
            throw new AssertionError("the #2034 push reports through onIncompleteReport");
        }

        @Override
        public Promise<Unit> onSoleCopyAtRisk(int count, List<String> sampleKeys) {
            atRisk.add(count);
            onAtRisk.run();
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> onSoleCopyDelivered(int count) {
            delivered.add(count);
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> onIncompleteReport(int keysAtRisk, int soleCopyAtRisk, List<String> sampleKeys) {
            incomplete.add(new Incomplete(keysAtRisk, soleCopyAtRisk));
            return Promise.unitPromise();
        }

        List<Integer> atRisk() {
            return List.copyOf(atRisk);
        }

        List<Integer> delivered() {
            return List.copyOf(delivered);
        }

        List<Incomplete> incomplete() {
            return List.copyOf(incomplete);
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
