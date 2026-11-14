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

import java.lang.ref.WeakReference;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.time.Duration;
import org.pragmatica.lang.Promise;
import org.pragmatica.testlogging.LogCapture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import java.util.stream.Stream;
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

        /// #3049: the F7 re-evaluation is scheduled at the base deadline (10 s by default). A finished push must cancel it:
        /// an uncancelled timer pins the run, its node, storage and observer until it fires, which retained whole stopped
        /// clusters for 30 s in the co-drain test.
        @Test
        void pushOnDeparture_completedPush_releasesItsObserverBeforeTheBaseDeadline() {
            var observerRef = completedPushObserver();

            assertThat(collected(observerRef)).as("a finished push keeps nothing alive through its scheduled re-evaluation")
                                              .isTrue();
        }

        private WeakReference<RecordingObserver> completedPushObserver() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG);
            var probe = findKeyWithNewcomer(ring, LOCAL, CONFIG.effectiveReplicationFactor(ring.nodeCount()));

            node.putLocal(probe, value("payload")).await();

            var network = new AckingNetwork();
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            network.ackVia(rebalancer::onMigrationDataAck);
            var observer = new RecordingObserver();

            rebalancer.pushOnDeparture(observer).await().onFailure(cause -> Assertions.fail(cause.message()));

            return new WeakReference<>(observer);
        }

        private static boolean collected(WeakReference<?> reference) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);

            while (reference.get() != null && System.nanoTime() < deadline) {
                System.gc();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            }

            return reference.get() == null;
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

        /// T11 (#3208): every other node is named in the drain set, so the sole key has no target outside it: reported at plan time. The named
        /// node is still pushed to, because its entry may be stale and it may be the only survivor, but its ack does not deliver the key.
        @Test
        @Timeout(20)
        void pushOnDeparture_onlyNamedTarget_reportedAtPlan_ackDoesNotDeliver() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(key("t11"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
            var run = push(node, network, T(5_000, 30_000, 50), Set.of(PEER_A));

            assertThat(network.sends()).isNotEmpty().allSatisfy(sent -> assertThat(sent.target()).isEqualTo(PEER_A));
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

        /// #2684 F1: the base-deadline re-evaluation raises AT_RISK, and its observer call is held open; the last sole ack lands
        /// meanwhile and the push finishes. The acking thread returns without waiting for the held call (the transition lock is never
        /// held across an observer call), DELIVERED is not called before AT_RISK returned, and the push does not resolve while the
        /// AT_RISK call is in flight: its event is awaited like every other.
        @Test
        @Timeout(30)
        void pushOnDeparture_atRiskCallInFlightAtLastAck_resolvesOnlyAfterAtRiskThenDelivered() throws InterruptedException {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "f1"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var recorder = new EventRecorder(() -> holdOpen(entered, release));
            var rebalancer = dhtRebalancer(node, network, RF1);
            var push = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(60_000).nanos()),
                                                  new DeparturePushTuning(ms(300), ms(10_000), ms(10_000), ms(10_000), ms(200)),
                                                  Set.of(),
                                                  recorder);

            assertThat(entered.await(10, TimeUnit.SECONDS)).as("control: the re-evaluation's AT_RISK call is in flight").isTrue();
            var sent = network.sends().getFirst();
            var acker = Thread.ofPlatform()
                              .start(() -> rebalancer.onMigrationDataAck(new DHTMessage.MigrationDataAck(sent.response().requestId(),
                                                                                                         sent.target(),
                                                                                                         true)));
            var ackReturned = acker.join(Duration.ofSeconds(2));
            var resolvedWhileHeld = push.await(ms(1_000)).isSuccess();
            var deliveredWhileHeld = recorder.delivered();

            release.countDown();
            var report = push.await().unwrap();

            assertThat(ackReturned).as("the acking thread never waits for an observer call").isTrue();
            assertThat(deliveredWhileHeld).as("DELIVERED is not called before AT_RISK returned").isEmpty();
            assertThat(resolvedWhileHeld).as("the push does not resolve while its AT_RISK call is in flight").isFalse();
            assertThat(recorder.atRisk()).containsExactly(1);
            assertThat(recorder.delivered()).containsExactly(1);
            assertThat(report.soleCopyAtRisk()).isZero();
        }

        /// #2684 P1: the winner of the re-evaluation is held in the log line of its AT_RISK raise, an effect that runs BEFORE the
        /// observer call, outside the transition lock. The event was decided under the lock, so it is already in `events` (and
        /// so is the DELIVERED event the last ack decides next): the push must not resolve while the winner is still draining. A
        /// registration done by the effect itself would leave `events` empty here and the push would resolve at once.
        @Test
        @Timeout(30)
        void pushOnDeparture_winnerHeldBeforeItsObserverCall_pushWaitsForTheRegisteredEvents() throws InterruptedException {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "p1"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var recorder = new EventRecorder(() -> {});
            var rebalancer = dhtRebalancer(node, network, RF1);

            try (var _ = LogCapture.capture(DeparturePushRun.class.getName(), Level.WARN, true, holdAtRiskLog(entered, release, _ -> {}))) {
                var push = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(60_000).nanos()),
                                                      new DeparturePushTuning(ms(300), ms(10_000), ms(10_000), ms(10_000), ms(200)),
                                                      Set.of(),
                                                      recorder);

                assertThat(entered.await(10, TimeUnit.SECONDS)).as("control: the winner is held in its AT_RISK log line").isTrue();
                var sent = network.sends().getFirst();
                var acker = Thread.ofPlatform()
                                  .start(() -> rebalancer.onMigrationDataAck(new DHTMessage.MigrationDataAck(sent.response().requestId(),
                                                                                                             sent.target(),
                                                                                                             true)));
                var ackReturned = acker.join(Duration.ofSeconds(2));
                var resolvedWhileHeld = push.await(ms(1_000)).isSuccess();
                var atRiskWhileHeld = recorder.atRisk();

                release.countDown();
                var report = push.await().unwrap();

                assertThat(ackReturned).as("the acking thread never waits for the draining winner").isTrue();
                assertThat(atRiskWhileHeld).as("control: the observer call is still behind the held log line").isEmpty();
                assertThat(resolvedWhileHeld).as("the push does not resolve while the winner's registered events are pending").isFalse();
                assertThat(recorder.atRisk()).containsExactly(1);
                assertThat(recorder.delivered()).containsExactly(1);
                assertThat(report.soleCopyAtRisk()).isZero();
            }
        }

        /// #2684 P1: effects run in the order their transitions queued them, across transitions. The re-evaluation's promotion
        /// (log, AT_RISK log, AT_RISK call) is held in its second effect; the last ack's transition queues its DELIVERED effects
        /// meanwhile. Whatever the winner already ran stays before them, and whatever it has left runs before them too: the
        /// promotion line and the AT_RISK call precede the DELIVERED line and call.
        @Test
        @Timeout(30)
        void pushOnDeparture_effectsOfTwoTransitionsQueuedBeforeOneDrain_runInTransitionOrder() throws InterruptedException {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            var probe = keyHeldBy(ring, 2, Set.of(LOCAL, PEER_A), "p1order");
            node.putLocal(probe, value("payload")).await();
            var firstSend = new CountDownLatch(1);
            var network = ScriptedNetwork.scripted((_, _) -> countDownThenDrop(firstSend));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var trace = new CopyOnWriteArrayList<String>();
            var recorderRef = new AtomicReference<EventRecorder>();
            var recorder = new EventRecorder(() -> trace.add("AT_RISK call, delivered calls seen: " + recorderRef.get().delivered().size()));
            recorderRef.set(recorder);
            var rebalancer = dhtRebalancer(node, network, node.config());

            try (var _ = LogCapture.capture(DeparturePushRun.class.getName(),
                                            Level.INFO,
                                            true,
                                            holdAtRiskLog(entered, release, line -> trace.add(line + ", delivered calls seen: " + recorder.delivered().size())))) {
                var push = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(60_000).nanos()),
                                                      T(300, 10_000, 10_000),
                                                      Set.of(),
                                                      recorder);

                assertThat(firstSend.await(10, TimeUnit.SECONDS)).as("control: the replicated page was sent").isTrue();
                ring.removeNode(PEER_A);
                assertThat(entered.await(10, TimeUnit.SECONDS)).as("control: the winner is held in its AT_RISK log line").isTrue();
                var sent = network.sends().getFirst();
                var acker = Thread.ofPlatform()
                                  .start(() -> rebalancer.onMigrationDataAck(new DHTMessage.MigrationDataAck(sent.response().requestId(),
                                                                                                             sent.target(),
                                                                                                             true)));
                var ackReturned = acker.join(Duration.ofSeconds(2));

                release.countDown();
                push.await();

                assertThat(ackReturned).as("control: the last ack was decided while the winner was held").isTrue();
                assertThat(recorder.delivered()).as("control: the delivered event was raised").containsExactly(1);
                assertThat(trace).containsExactly("lost every plan-time survivor, delivered calls seen: 0",
                                                  "not yet acknowledged, delivered calls seen: 0",
                                                  "AT_RISK call, delivered calls seen: 0",
                                                  "every sole-copy chunk was acknowledged, delivered calls seen: 0");
            }
        }

        /// Logs of the push run are observed on the logging thread: the AT_RISK line holds its thread until `release`; every
        /// line is tagged by its start text for `tagger`.
        private static Consumer<LogEvent> holdAtRiskLog(CountDownLatch entered, CountDownLatch release, Consumer<String> tagger) {
            return event -> {
                var message = event.getMessage().getFormattedMessage();

                var tag = tagOf(message);

                if (!tag.equals("other")) {
                    tagger.accept(tag);
                }
                if (message.contains("not yet acknowledged")) {
                    holdOpen(entered, release);
                }
            };
        }

        private static String tagOf(String message) {
            return Stream.of("lost every plan-time survivor", "not yet acknowledged", "every sole-copy chunk was acknowledged")
                         .filter(message::contains)
                         .findFirst()
                         .orElse("other");
        }

        /// #2684 F2: replicated key {LOCAL, A}; A leaves the ring before `baseDeadline`, so the re-evaluation must promote the key to
        /// sole and its page continue to `soleDeadline`. The re-evaluation that wins is held inside its transition, after claiming the
        /// re-evaluation and before reading the ring: a helper holds the ring's write lock. The page's chain, released only once the
        /// winner is queued on that lock, then checks its deadline past base. It must wait for the promotion, not stop the page.
        @Test
        @Timeout(40)
        void pushOnDeparture_reevaluationLoserPastBase_waitsForThePromotion() throws InterruptedException {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            var probe = keyHeldBy(ring, 2, Set.of(LOCAL, PEER_A), "f2");
            node.putLocal(probe, value("payload")).await();
            var probeText = new String(probe, StandardCharsets.UTF_8);
            var firstSend = new CountDownLatch(1);
            var network = ScriptedNetwork.scripted((_, _) -> countDownThenDrop(firstSend));
            var ringLock = ringLock(ring);
            var lockHeld = new CountDownLatch(1);
            var releaseLock = new CountDownLatch(1);
            var holder = Thread.ofPlatform().unstarted(() -> holdWriteLock(ringLock, lockHeld, releaseLock));
            var started = System.nanoTime();
            var gate = new PromotionRaceGate(holder, lockHeld, ringLock, started + ms(300).nanos());
            var recorder = new EventRecorder(() -> {});
            var rebalancer = dhtRebalancer(node, network, node.config());
            network.ackVia(rebalancer::onMigrationDataAck);
            var push = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(started + ms(60_000).nanos(), gate),
                                                  T(300, 5_000, 1_000),
                                                  Set.of(),
                                                  recorder);

            assertThat(firstSend.await(10, TimeUnit.SECONDS)).as("control: the replicated page was sent").isTrue();
            ring.removeNode(PEER_A);
            push.await(ms(1_000));
            var releasedAt = System.nanoTime();
            releaseLock.countDown();
            push.await();
            holder.join(5_000);
            var sendsAfterPromotion = network.sends()
                                             .stream()
                                             .filter(sent -> sent.atNanos() > releasedAt && carries(sent, probeText))
                                             .count();

            assertThat(gate.winnerQueued()).as("control: the winner was held inside its transition when the loser checked").isTrue();
            assertThat(sendsAfterPromotion).as("the promoted key is resent after its promotion (page continues to soleDeadline)")
                                           .isPositive();
        }

        /// #2684 F3: self in ring, RF 2, co-drainer B pruned. The key is sole because the node X now in B's slot may never have held
        /// it, so targeting must not treat X as an existing holder: X is pushed to, and as the only target that acks, it delivers the
        /// key.
        @Test
        @Timeout(20)
        void pushOnDeparture_coDrainerPruned_soleKeyPushedToTheSubstituteToo() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            var probe = keyHeldBy(ring, 2, Set.of(LOCAL, PEER_B), "f3");
            node.putLocal(probe, value("payload")).await();
            ring.removeNode(PEER_B);
            var substitute = ring.nodesFor(probe, 2).stream().filter(member -> !member.equals(LOCAL)).findFirst().orElseThrow();
            var network = ScriptedNetwork.scripted((_, sent) -> sent.target().equals(substitute) ? Reply.ACK : Reply.DROP);
            var run = push(node, network, T(200, 300, 50), Set.of(PEER_B));

            assertThat(network.targets()).as("the substitute in the pruned co-drainer's slot is a target").contains(substitute);
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        private static void holdOpen(CountDownLatch entered, CountDownLatch release) {
            entered.countDown();
            awaitQuietly(release, 15_000);
        }

        private static Reply countDownThenDrop(CountDownLatch latch) {
            latch.countDown();
            return Reply.DROP;
        }

        /// The ring's lock is private and the only point inside a push-run transition that can be held from outside; a seam on the
        /// production ring for one test is not worth its surface, so the test reaches it by reflection.
        private static ReentrantReadWriteLock ringLock(ConsistentHashRing<NodeId> ring) {
            try {
                var field = ConsistentHashRing.class.getDeclaredField("lock");
                field.setAccessible(true);
                return (ReentrantReadWriteLock) field.get(ring);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("ConsistentHashRing.lock is the instrument of this test", e);
            }
        }

        private static void holdWriteLock(ReentrantReadWriteLock lock, CountDownLatch held, CountDownLatch release) {
            lock.writeLock().lock();
            try {
                held.countDown();
                awaitQuietly(release, 30_000);
            } finally {
                lock.writeLock().unlock();
            }
        }

        private static void awaitQuietly(CountDownLatch latch, long millis) {
            try {
                latch.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static boolean calledFrom(String method) {
            return StackWalker.getInstance()
                              .walk(frames -> frames.anyMatch(frame -> frame.getClassName().endsWith("DeparturePushRun")
                                                                       && frame.getMethodName().equals(method)));
        }

        /// The `halted` supplier as a gate (never halts): the first re-evaluation starts the ring-lock holder before its transition;
        /// the first deadline check past base waits until that winner is queued on the ring lock.
        private static final class PromotionRaceGate implements BooleanSupplier {
            private final Thread holder;
            private final CountDownLatch lockHeld;
            private final ReentrantReadWriteLock ringLock;
            private final long baseNanos;
            private final AtomicInteger reevaluations = new AtomicInteger();
            private final AtomicBoolean loserGated = new AtomicBoolean(false);
            private final AtomicBoolean winnerQueued = new AtomicBoolean(false);

            PromotionRaceGate(Thread holder, CountDownLatch lockHeld, ReentrantReadWriteLock ringLock, long baseNanos) {
                this.holder = holder;
                this.lockHeld = lockHeld;
                this.ringLock = ringLock;
                this.baseNanos = baseNanos;
            }

            @Override
            public boolean getAsBoolean() {
                if (calledFrom("reevaluate") && reevaluations.getAndIncrement() == 0) {
                    holder.start();
                    awaitQuietly(lockHeld, 10_000);
                } else if (calledFrom("shouldStop") && System.nanoTime() >= baseNanos && loserGated.compareAndSet(false, true)) {
                    awaitWinnerQueued();
                }
                return false;
            }

            private void awaitWinnerQueued() {
                var until = System.nanoTime() + ms(10_000).nanos();
                while (!ringLock.hasQueuedThreads() && System.nanoTime() < until) {
                    LockSupport.parkNanos(1_000_000);
                }
                winnerQueued.set(ringLock.hasQueuedThreads());
            }

            boolean winnerQueued() {
                return winnerQueued.get();
            }
        }

        // --- round B (#2684): pins for lines the first suite left unguarded ---

        /// H2: a refused send is retried after `resendAfter`, never in a tight loop (an upper bound on sends: load can only lower it).
        @Test
        @Timeout(20)
        void pushOnDeparture_persistentRefusal_pausesBetweenSends() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "b-refusal"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.refuse(WriteOutcome.ConnectionDead::new));
            var run = push(node, network, T(1_000, 500, 200), Set.of());

            assertThat(network.sends()).hasSizeBetween(2, 10);
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// F1: the nack backoff doubles up to `nackBackoffCap` and no further. Cap 50 ms: no gap between two sends may reach the
        /// uncapped 800 ms step (25, 50, 100, 200, 400, 800), whatever the load.
        @Test
        @Timeout(20)
        void pushOnDeparture_persistentNack_backoffStopsAtTheCap() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "b-cap"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.NACK);
            var run = push(node, network, new DeparturePushTuning(ms(2_500), ms(500), ms(25), ms(50), ms(200)), Set.of());

            assertThat(network.sends()).hasSizeGreaterThanOrEqualTo(6);
            assertThat(maxGapNanos(network.sends())).isLessThan(ms(600).nanos());
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        private static long maxGapNanos(List<TimedSend> sends) {
            long max = 0;
            for (int i = 1; i < sends.size(); i++) {
                max = Math.max(max, sends.get(i).atNanos() - sends.get(i - 1).atNanos());
            }
            return max;
        }

        /// A replicated page stops at `baseDeadline` (its key still has a survivor), and the push ends with it rather than at the later
        /// backstop: an attempt's wait is clipped to the page's remaining time, however long `resendAfter` is.
        @Test
        @Timeout(20)
        void pushOnDeparture_unackedAttemptWait_isClippedToThePageDeadline() {
            var run = replicatedPageUnder(Reply.DROP, "b-wait");

            assertThat(run.elapsedMillis()).isLessThan(1_500);
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
        }

        /// The pause after a nack is clipped the same way.
        @Test
        @Timeout(20)
        void pushOnDeparture_nackPause_isClippedToThePageDeadline() {
            var run = replicatedPageUnder(Reply.NACK, "b-pause");

            assertThat(run.elapsedMillis()).isLessThan(1_500);
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
        }

        private Run replicatedPageUnder(Reply reply, String prefix) {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            node.putLocal(keyHeldBy(ring, 2, Set.of(LOCAL, PEER_A), prefix), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> reply);

            return push(node, network, new DeparturePushTuning(ms(400), ms(5_000), ms(3_000), ms(3_000), ms(200)), Set.of());
        }

        /// A clean sole ack raises no event at all: DELIVERED is the recovery of an AT_RISK that was never raised.
        @Test
        @Timeout(20)
        void pushOnDeparture_cleanSoleAck_raisesNoEvent() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "b-clean"), value("payload")).await();
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.ACK), T(2_000, 2_000, 50), Set.of());

            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.recorder().delivered()).isEmpty();
            assertThat(run.recorder().incomplete()).isEmpty();
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        /// An observer that throws loses its own event only: the push still resolves, and the later events still reach the observer.
        @Test
        @Timeout(20)
        void pushOnDeparture_throwingObserver_isIsolated() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "b-throw"), value("payload")).await();
            var recorder = new EventRecorder(SoleCopyDeparture::boom);
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(200, 300, 50), Set.of(), recorder);

            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
            assertThat(recorder.atRisk()).containsExactly(1);
            assertThat(recorder.incomplete()).containsExactly(new Incomplete(1, 1));
        }

        private static void boom() {
            throw new IllegalStateException("observer boom");
        }

        /// E4: a late quiesce leaves the events `eventDeliveryWait`, but never past the window's event floor; here the floor is the
        /// deadline, 150 ms away, so an event in flight for 3 s is not awaited for the 2 s the tuning would allow.
        @Test
        @Timeout(20)
        void pushOnDeparture_lateQuiesce_eventWaitStopsAtTheFloor() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "b-floor"), value("payload")).await();
            var recorder = new EventRecorder(() -> {}, () -> Promise.promise(ms(3_000), org.pragmatica.lang.Result::unitResult));
            var started = System.nanoTime();
            var report = dhtRebalancer(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), RF1)
                             .pushOnDeparture(DepartureWindow.departureWindow(started + ms(150).nanos()),
                                              new DeparturePushTuning(ms(10_000), ms(10_000), ms(50), ms(200), ms(2_000)),
                                              Set.of(),
                                              recorder)
                             .await()
                             .unwrap();

            assertThat(recorder.atRisk()).as("control: the event was raised and is in flight").containsExactly(1);
            assertThat(System.nanoTime() - started).isLessThan(ms(1_000).nanos());
            assertThat(report.soleCopyAtRisk()).isEqualTo(1);
        }

        /// After the node halted nothing more is reported or awaited: the AT_RISK call halts the node and its event never settles;
        /// the push resolves at once, without the incomplete report and without waiting for that event.
        @Test
        @Timeout(20)
        void pushOnDeparture_haltedByTheAtRiskCall_reportsAndAwaitsNothingMore() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "b-halt"), value("payload")).await();
            var halted = new AtomicBoolean(false);
            var recorder = new EventRecorder(() -> halted.set(true), () -> Promise.promise());
            var started = System.nanoTime();

            dhtRebalancer(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), RF1)
                .pushOnDeparture(DepartureWindow.departureWindow(started + ms(3_000).nanos(), halted::get),
                                 new DeparturePushTuning(ms(300), ms(500), ms(50), ms(200), ms(200)),
                                 Set.of(),
                                 recorder)
                .await();

            assertThat(recorder.atRisk()).as("control: the halting call was made").containsExactly(1);
            assertThat(recorder.incomplete()).as("no report after a halt").isEmpty();
            assertThat(System.nanoTime() - started).as("the in-flight event is not awaited after a halt").isLessThan(ms(2_000).nanos());
        }

        /// A halt before the base deadline also silences the scheduled re-evaluation: no AT_RISK is raised at the base deadline.
        @Test
        @Timeout(20)
        void pushOnDeparture_haltedBeforeBase_raisesNothingAtTheBaseDeadline() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "b-halt-base"), value("payload")).await();
            var halted = new AtomicBoolean(false);
            var recorder = new EventRecorder(() -> {});
            var network = ScriptedNetwork.scripted((_, _) -> haltAndDrop(halted));

            dhtRebalancer(node, network, RF1).pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(60_000).nanos(), halted::get),
                                                              T(300, 1_000, 50),
                                                              Set.of(),
                                                              recorder)
                                             .await();
            awaitQuietly(new CountDownLatch(1), 800);

            assertThat(network.sends()).hasSize(1);
            assertThat(recorder.atRisk()).isEmpty();
            assertThat(recorder.incomplete()).isEmpty();
        }

        /// A node that is already halted when the push starts raises nothing at plan time, whether the window is used up or the
        /// inventory never enumerates.
        @Test
        @Timeout(20)
        void pushOnDeparture_haltedAtStart_raisesNothing() {
            var ring = fiveNodeRing();
            var usedUp = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            usedUp.putLocal(soleKey(ring, "b-halt-start"), value("payload")).await();
            var planRecorder = new EventRecorder(() -> {});
            var hung = dhtNode(LOCAL, stampsVia(_ -> Promise.promise()), ring, RF1);
            var hungRecorder = new EventRecorder(() -> {});
            var past = System.nanoTime() - ms(1).nanos();
            var plan = dhtRebalancer(usedUp, ScriptedNetwork.scripted((_, _) -> Reply.ACK), RF1)
                           .pushOnDeparture(DepartureWindow.departureWindow(past, () -> true), T(200, 300, 50), Set.of(), planRecorder);
            var enumeration = dhtRebalancer(hung, ScriptedNetwork.scripted((_, _) -> Reply.ACK), RF1)
                                  .pushOnDeparture(DepartureWindow.departureWindow(past, () -> true), T(200, 300, 50), Set.of(), hungRecorder);

            plan.await();
            enumeration.await();

            assertThat(planRecorder.atRisk()).isEmpty();
            assertThat(hungRecorder.atRisk()).isEmpty();
        }

        /// F7 at the end of the push: pages that end early (EncodeFailed is final) with the only plan-time survivor gone, long before
        /// `baseDeadline`, still promote the key at the closing re-evaluation: AT_RISK and a sole-copy report, not a replicated one.
        @Test
        @Timeout(20)
        void pushOnDeparture_pagesEndEarlyAfterTheSurvivorLeft_closingReevaluationPromotes() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            node.putLocal(keyHeldBy(ring, 2, Set.of(LOCAL, PEER_A), "b-close"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> removeAndFailToEncode(ring, PEER_A));
            var run = push(node, network, T(10_000, 10_000, 50), Set.of());

            assertThat(network.sends()).hasSize(1);
            assertThat(run.recorder().atRisk()).containsExactly(1);
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        private static Reply removeAndFailToEncode(ConsistentHashRing<NodeId> ring, NodeId survivor) {
            ring.removeNode(survivor);
            return Reply.refuse(id -> new WriteOutcome.EncodeFailed(id, "MigrationDataResponse"));
        }

        /// E2: with a co-drainer already pruned the survivors are unknown (`existingAtPlan` empty), so a replicated key whose current
        /// set then leaves the ring is NOT promoted: disclosed as replicated, never guessed sole.
        @Test
        @Timeout(20)
        void pushOnDeparture_coDrainerPruned_survivorsLeave_isNeverPromoted() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF3);
            var probe = keyHeldBy(ring, 3, Set.of(LOCAL, PEER_B, PEER_C), "b-e2");
            node.putLocal(probe, value("payload")).await();
            ring.removeNode(PEER_B);
            var survivors = ring.nodesFor(probe, 3).stream().filter(member -> !member.equals(LOCAL)).toList();
            var network = ScriptedNetwork.scripted((index, _) -> index == 0 ? removeAllAndDrop(ring, survivors) : Reply.DROP);
            var run = push(node, network, T(300, 600, 50), Set.of(PEER_B));

            assertThat(survivors).as("control: two survivors, so the key is replicated at plan time").hasSize(2);
            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.report().soleCopyAtRisk()).isZero();
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
        }

        private static Reply removeAllAndDrop(ConsistentHashRing<NodeId> ring, List<NodeId> nodes) {
            nodes.forEach(ring::removeNode);
            return Reply.DROP;
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
        private final java.util.function.Supplier<Promise<Unit>> atRiskResult;

        EventRecorder(Runnable onAtRisk) {
            this(onAtRisk, Promise::unitPromise);
        }

        /// `atRiskResult` is the promise the at-risk call returns: the push awaits it like every event.
        EventRecorder(Runnable onAtRisk, java.util.function.Supplier<Promise<Unit>> atRiskResult) {
            this.onAtRisk = onAtRisk;
            this.atRiskResult = atRiskResult;
        }

        @Override
        public void onIncomplete(int keysAtRisk, List<String> sampleKeys) {
            throw new AssertionError("the #2034 push reports through onIncompleteReport");
        }

        @Override
        public Promise<Unit> onSoleCopyAtRisk(int count, List<String> sampleKeys) {
            atRisk.add(count);
            onAtRisk.run();
            return atRiskResult.get();
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
