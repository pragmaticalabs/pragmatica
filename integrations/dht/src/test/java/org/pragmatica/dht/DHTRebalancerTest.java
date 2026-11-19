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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import java.time.Duration;
import org.pragmatica.lang.Promise;
import org.pragmatica.testlogging.LogCapture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.pragmatica.consensus.net.WriteOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.DepartureDirectRuns.bystander;
import static org.pragmatica.dht.DepartureDirectRuns.delivererOf;
import static org.pragmatica.dht.DepartureDirectRuns.direct;
import static org.pragmatica.dht.DepartureDirectRuns.fiveNodeRing;
import static org.pragmatica.dht.DepartureDirectRuns.sameDeliverer;
import static org.pragmatica.dht.DepartureDirectRuns.sameRequest;
import static org.pragmatica.dht.DepartureDirectRuns.soleKey;
import static org.pragmatica.dht.DepartureDirectRuns.takeover;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class DHTRebalancerTest {
    static final NodeId LOCAL = new NodeId("local");
    static final NodeId PEER_A = new NodeId("peer-a");
    static final NodeId PEER_B = new NodeId("peer-b");
    static final NodeId PEER_C = new NodeId("peer-c");
    static final NodeId PEER_D = new NodeId("peer-d");
    private static final NodeId REMOVED = new NodeId("removed");

    static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] value(String s) {
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

            DeparturePushes.push(rebalancer, observer).await().onFailure(cause -> Assertions.fail(cause.message()));

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

            DeparturePushes.push(rebalancer, observer).await().onFailure(cause -> Assertions.fail(cause.message()));

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

            DeparturePushes.push(rebalancer, new RecordingObserver()).await();

            assertThat(network.targets()).doesNotContainAnyElementsOf(existingReplicas);
            assertThat(network.targets()).doesNotContain(LOCAL);
        }

        @Test
        void pushOnDeparture_selfAlreadyPruned_deliversWithinTheWholeNewSet() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, CONFIG);
            var replicationFactor = CONFIG.effectiveReplicationFactor(ring.nodeCount());
            var probe = findKeyWithNewcomer(ring, LOCAL, replicationFactor);

            node.putLocal(probe, value("payload")).await();

            // Prune-ahead-of-drain ordering (Case B): self leaves the ring BEFORE the push runs.
            ring.removeNode(LOCAL);
            var postSet = ring.nodesFor(probe, CONFIG.effectiveReplicationFactor(ring.nodeCount()));

            var network = new AckingNetwork();
            var rebalancer = dhtRebalancer(node, network, CONFIG);
            network.ackVia(rebalancer::onMigrationDataAck);

            DeparturePushes.push(rebalancer, new RecordingObserver()).await().onFailure(cause -> Assertions.fail(cause.message()));

            // With self no longer identifiable in the ring, every node of the WHOLE post-departure responsible set is a target
            // (the storm guard cannot apply without self in the ring). #3345: one acked holder among them makes the key safe, so
            // the pages to the others are cancelled at the exit's re-plan and need not all be sent.
            assertThat(network.targets()).isNotEmpty();
            assertThat(postSet).containsAll(network.targets());
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

            DeparturePushes.push(rebalancer, timeSpan(200).millis(), observer).await().onFailure(cause -> Assertions.fail(cause.message()));

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

            DeparturePushes.push(rebalancer, observer).await().onFailure(cause -> Assertions.fail(cause.message()));

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

            DeparturePushes.push(rebalancer, observer).await().onFailure(cause -> Assertions.fail(cause.message()));

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

            DeparturePushes.push(rebalancer, timeSpan(2).seconds(), observer).await().onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(network.sends()).as("the dropped page was resent").hasSizeGreaterThanOrEqualTo(2);
            assertThat(network.ackedKeys()).contains(new String(probe, StandardCharsets.UTF_8));
            assertThat(observer.incompleteCount()).isZero();
        }

        private static final DHTConfig RF2 = new DHTConfig(2, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
        private static final DHTConfig RF3 = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);

        /// T3 (H1): 3 nodes at RF 3, one draining: both survivors hold the key, so a never-acked key is owed as REPLICATED: no
        /// AT_RISK, and the report counts it apart from the sole copies (#3345: the class only shapes the events and the report).
        @Test
        @Timeout(20)
        void pushOnDeparture_threeNodeRf3_neverAcked_isReplicated() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A, PEER_B).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF3);
            node.putLocal(key("t3"), value("payload")).await();
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(300, 0, 50), Set.of());

            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 0));
            assertThat(run.report().soleCopyAtRisk()).isZero();
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
            assertThat(run.elapsedMillis()).as("ends at its own window, never a sole extra").isLessThan(10_000);
        }

        /// T4 (H1): five nodes at RF 3, a key with a newcomer: two survivors, replicated.
        @Test
        @Timeout(20)
        void pushOnDeparture_fiveNodeRf3_neverAcked_isReplicated() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF3);
            node.putLocal(keyIncluding(ring, 3, LOCAL, "t4"), value("payload")).await();
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(300, 0, 50), Set.of());

            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 0));
            assertThat(run.report().soleCopyAtRisk()).isZero();
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
            assertThat(run.elapsedMillis()).as("ends at its own window, never a sole extra").isLessThan(10_000);
        }

        /// T4b: self already pruned. RF 1: sole (1 - 1 = 0). RF 3 on 5 nodes pruned to 4: replicated (3 - 1 = 2). The class shows
        /// in the report and the incomplete event; the sole key, still owed at the deadline, raises AT_RISK there, the replicated
        /// one never does.
        @Test
        @Timeout(20)
        void classify_pruneAhead_usesHolderLowerBound() {
            var soleRing = fiveNodeRing();
            var soleNode = dhtNode(LOCAL, memoryStorageEngine(), soleRing, RF1);
            soleNode.putLocal(soleKey(soleRing, "t4b-sole"), value("payload")).await();
            soleRing.removeNode(LOCAL);
            var sole = push(soleNode, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(200, 300, 50), Set.of());

            assertThat(sole.report().soleCopyAtRisk()).isEqualTo(1);
            assertThat(sole.report().replicatedAtRisk()).isZero();
            assertThat(sole.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
            assertThat(sole.recorder().atRisk()).as("a sole key owed at the deadline").containsExactly(1);

            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF3);
            node.putLocal(keyIncluding(ring, 3, LOCAL, "t4b-repl"), value("payload")).await();
            ring.removeNode(LOCAL);
            var replicated = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(200, 300, 50), Set.of());

            assertThat(replicated.report().soleCopyAtRisk()).isZero();
            assertThat(replicated.report().replicatedAtRisk()).isEqualTo(1);
            assertThat(replicated.recorder().incomplete()).containsExactly(new Incomplete(1, 0));
            assertThat(replicated.recorder().atRisk()).isEmpty();
        }

        /// E1: self is in the ring, RF 2, the co-drainer B already pruned. The ring put a node that never held the key into B's slot,
        /// so `currentSet \ leaving` names one "survivor"; the bound subtracts the pruned B: sole. Never acked, it is still owed at
        /// the deadline: AT_RISK fires there, then the incomplete report.
        @Test
        @Timeout(20)
        void classify_coDrainerPruned_selfInRing_isSole() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            node.putLocal(keyHeldBy(ring, 2, Set.of(LOCAL, PEER_B), "e1"), value("payload")).await();
            ring.removeNode(PEER_B);
            var run = push(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), T(200, 300, 50), Set.of(PEER_B));

            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
            assertThat(run.recorder().atRisk()).as("a sole key owed at the deadline").containsExactly(1);
        }

        /// T5: a sole page never acked is resent until the deadline. It has a deliverer, so no AT_RISK at plan time (#3345); still
        /// owed at the deadline, it raises AT_RISK once there, then the incomplete report, and nothing claims it delivered.
        @Test
        @Timeout(20)
        void pushOnDeparture_soleNeverAcked_atRiskOnceThenIncomplete() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(soleKey(ring, "t5"), value("payload")).await();
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var run = push(node, network, T(200, 1_000, 50), Set.of());

            assertThat(network.sends()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(run.recorder().atRisk()).as("raised once, at the deadline").containsExactly(1);
            assertThat(run.recorder().delivered()).isEmpty();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// T6: a sole key with no deliverer raises AT_RISK at plan time; a node joining from the at-risk callback gives it one, its
        /// ack makes it safe, DELIVERED closes the at-risk and nothing is reported incomplete.
        @Test
        @Timeout(20)
        void pushOnDeparture_soleAckReleasedOnAtRisk_deliveredRecovers() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(key("t6"), value("payload")).await();
            var network = ScriptedNetwork.scriptedOver(ring, (_, _) -> Reply.ACK);
            var recorder = new EventRecorder(() -> join(ring, network, PEER_A));
            var run = push(node, network, T(200, 5_000, 50), Set.of(), recorder);

            assertThat(recorder.atRisk()).containsExactly(1);
            assertThat(recorder.delivered()).containsExactly(1);
            assertThat(recorder.incomplete()).isEmpty();
            assertThat(run.report().soleCopyAtRisk()).isZero();
            assertThat(network.sends()).isNotEmpty().allSatisfy(sent -> assertThat(sent.target()).isEqualTo(PEER_A));
        }

        /// `member` joins `ring`, followed by the rebalancer's `onViewChange`, as `DHTTopologyListener` does after `changeRing`.
        private static void join(ConsistentHashRing<NodeId> ring, ScriptedNetwork network, NodeId member) {
            ring.addNode(member);
            network.signalViewChange();
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

        /// T8 (H2): the target leaves the ring after losing the first push; the ring change reaches the run as `onViewChange` (as
        /// `DHTTopologyListener` delivers it), the page goes to the next responsible node, and its `view` no longer has the removed node.
        @Test
        @Timeout(20)
        void pushOnDeparture_targetRemovedFromRing_retargetsWithLeaving() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            var probe = soleKey(ring, "t8");
            node.putLocal(probe, value("payload")).await();
            var network = ScriptedNetwork.scriptedOver(ring, (index, sent) -> index == 0 ? removeAndDrop(ring, sent.target()) : Reply.ACK);
            var run = push(node, network, T(2_000, 2_000, 50), Set.of());
            var first = network.sends().getFirst().target();
            var expected = ring.nodesFor(probe, 1, candidate -> !candidate.equals(LOCAL)).getFirst();

            assertThat(network.sends()).hasSize(2);
            assertThat(network.sends().get(1).target()).isEqualTo(expected).isNotEqualTo(first);
            // #3345: `leaving` is L (self + drain set); the removed target reaches the receiver as its absence from the page's `view`
            // (the pusher's ring after the change), so the receiver still computes the post-removal placement (#1818 L1).
            assertThat(network.sends().get(1).response().leaving()).containsExactly(LOCAL);
            assertThat(network.sends().get(1).response().view()).doesNotContain(first).contains(expected);
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
            var tuning = timing(100, 1_200, 50, 400, 200);
            var run = push(node, network, tuning, Set.of());

            assertThat(network.sends()).hasSizeBetween(2, 12);
            assertThat(run.recorder().atRisk()).as("a sole key owed at the deadline").containsExactly(1);
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
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
            var run = push(node, network, T(300, 700, 50), Set.of(PEER_A));

            assertThat(network.sends()).isNotEmpty().allSatisfy(sent -> assertThat(sent.target()).isEqualTo(PEER_A));
            assertThat(network.ackedKeys()).contains("t11");
            assertThat(run.recorder().atRisk()).containsExactly(1);
            assertThat(run.recorder().delivered()).isEmpty();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// #3208: a sole key whose only target is named in the drain set is raised at plan time, not at the base re-evaluation.
        @Test
        @Timeout(20)
        void pushOnDeparture_onlyNamedTargetNeverAcks_raisedAtPlanNotAtBase() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(key("t11b"), value("payload")).await();
            var started = System.nanoTime();
            var raisedAfterMillis = new java.util.concurrent.atomic.AtomicLong(-1);
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var recorder = new EventRecorder(() -> raisedAfterMillis.compareAndSet(-1, (System.nanoTime() - started) / 1_000_000));

            push(node, network, T(3_000, 4_000, 50), Set.of(PEER_A), recorder);

            assertThat(raisedAfterMillis.get()).as("raised at plan time, not at the 3000 ms base").isBetween(0L, 1_500L);
            assertThat(recorder.atRisk()).containsExactly(1);
        }

        /// T12 (N2): one target owed a sole key and a replicated key. Every sole page is lost; the replicated page travels in its own
        /// chain, so it is acked within the base window; the deadline probe (P3) confirms its holder, so only the sole key is owed.
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
            assertThat(run.report().replicatedAtRisk()).as("acked, then confirmed by the deadline probe (P3)").isZero();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 1));
        }

        /// #3345 P1 (bound): one page of 8 keys bound for the same target, one of which the receiver nacks every time (a whole-page
        /// nack, as the receiver gives for one entry it cannot take). The page is split in halves down to single keys: every
        /// good key is acked, the bad key alone stays unacked; no exit runs while it is owed, so the deadline probe (P3) confirms
        /// the acked keys' holder and the bad key alone is owed and reported. Bound: sends other than the bad key's single-key page
        /// <= 2 * log2(N) = 6 for N = 8 (the first page, two halves on each of the log2(N) - 1 levels above the leaf, and the bad
        /// key's good sibling leaf), and the bad key's own resends back off.
        @Test
        @Timeout(20)
        void pushOnDeparture_nackedPageWithOneBadKey_splitsDownToTheKey_boundedSends() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            var keys = IntStream.range(0, 8).mapToObj(i -> "p1-" + i).toList();
            keys.forEach(text -> node.putLocal(key(text), value("payload")).await());
            var network = ScriptedNetwork.scripted((_, sent) -> carries(sent, "p1-5") ? Reply.NACK : Reply.ACK);
            var run = push(node, network, timing(100, 1_200, 50, 400, 200), Set.of());

            var otherThanTheBadKey = network.sends().stream().filter(sent -> !isOnly(sent, "p1-5")).count();

            assertThat(network.sends().getFirst().response().entries()).as("control: the 8 keys travel in one page").hasSize(8);
            assertThat(network.ackedKeys()).as("every good key is safe").containsAll(keys.stream().filter(text -> !text.equals("p1-5")).toList());
            assertThat(network.ackedKeys()).doesNotContain("p1-5");
            assertThat(otherThanTheBadKey).as("at most log2(8) split levels, two pages each, plus the first page").isLessThanOrEqualTo(6);
            assertThat(network.sends().size() - otherThanTheBadKey).as("the bad key alone backs off").isBetween(1L, 12L);
            assertThat(run.recorder().incomplete()).as("acked keys confirmed by the deadline probe (P3)").containsExactly(new Incomplete(1, 1));
            assertThat(run.report().soleCopyAtRisk()).isEqualTo(1);
        }

        /// #3345 P1 (transient): the first page of 4 keys is nacked once, then every page is acked. The nack carries no reason
        /// (`MigrationDataAck` has only `applied`), so the push cannot tell a refusal from a passing back-off nack and splits
        /// once: both halves are accepted and nothing is split further (3 sends: no split storm). Every key is delivered.
        @Test
        @Timeout(20)
        void pushOnDeparture_transientNack_oneSplitThenAccepted_noSplitStorm() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            var keys = IntStream.range(0, 4).mapToObj(i -> "p1t-" + i).toList();
            keys.forEach(text -> node.putLocal(key(text), value("payload")).await());
            var network = ScriptedNetwork.scripted((index, _) -> index == 0 ? Reply.NACK : Reply.ACK);
            var run = push(node, network, timing(100, 1_200, 50, 400, 200), Set.of());

            assertThat(network.sends().getFirst().response().entries()).as("control: the 4 keys travel in one page").hasSize(4);
            assertThat(network.sends()).as("the first page and its two halves, nothing more").hasSize(3);
            assertThat(network.ackedKeys()).containsExactlyInAnyOrderElementsOf(keys);
            assertThat(run.recorder().incomplete()).isEmpty();
            assertThat(run.report().owed()).isZero();
        }

        /// #3345 P1 (cost of the split): a target that nacks every page. As no ack field tells a refusal from a back-off nack, every
        /// nack splits; the cost stays bounded. Bound: sends of several keys = N - 1 = 7 for N = 8 (each inner page of the split
        /// tree is sent once, never resent), and each key's single-key sends <= 1 + log2(cap / b0) + ceil(W / cap) = 1 + 3 + 4 = 8
        /// (b0 = 50 ms first pause, cap = 400 ms, W = 1300 ms window): no storm, and every key stays owed.
        @Test
        @Timeout(20)
        void pushOnDeparture_targetNacksEverything_innerPagesNMinus1_singleKeySendsBackOff() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            var keys = IntStream.range(0, 8).mapToObj(i -> "p1n-" + i).toList();
            keys.forEach(text -> node.putLocal(key(text), value("payload")).await());
            var network = ScriptedNetwork.scripted((_, _) -> Reply.NACK);
            var run = push(node, network, timing(100, 1_200, 50, 400, 200), Set.of());

            var multiKey = network.sends().stream().filter(sent -> sent.response().entries().size() > 1).count();

            assertThat(network.sends().getFirst().response().entries()).as("control: the 8 keys travel in one page").hasSize(8);
            assertThat(multiKey).as("each inner page of the split tree sent once: N - 1").isEqualTo(7);
            keys.forEach(text -> assertThat(network.sends().stream().filter(sent -> isOnly(sent, text)).count())
                                     .as("single-key sends of %s", text)
                                     .isBetween(1L, 8L));
            assertThat(network.ackedKeys()).isEmpty();
            assertThat(run.report().owed()).isEqualTo(8);
        }

        /// #3345 P1 (cost of splitting on silence): a target that never answers. A timed-out page of several keys splits like a
        /// nacked one. Bound: sends of several keys = N - 1 = 7 for N = 8, and each key's single-key sends <= 1 + ceil(W / resend)
        /// = 1 + 26 = 27 (W = 1300 ms window, resend = 50 ms): every key stays owed.
        @Test
        @Timeout(20)
        void pushOnDeparture_silentTarget_innerPagesNMinus1_singleKeySendsBoundedByWindowOverResend() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            List.of(LOCAL, PEER_A).forEach(ring::addNode);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            var keys = IntStream.range(0, 8).mapToObj(i -> "p1s-" + i).toList();
            keys.forEach(text -> node.putLocal(key(text), value("payload")).await());
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var run = push(node, network, timing(100, 1_200, 50, 400, 200), Set.of());

            var multiKey = network.sends().stream().filter(sent -> sent.response().entries().size() > 1).count();

            assertThat(network.sends().getFirst().response().entries()).as("control: the 8 keys travel in one page").hasSize(8);
            assertThat(multiKey).as("each inner page of the split tree sent once: N - 1").isEqualTo(7);
            keys.forEach(text -> assertThat(network.sends().stream().filter(sent -> isOnly(sent, text)).count())
                                     .as("single-key sends of %s", text)
                                     .isBetween(1L, 27L));
            assertThat(run.report().owed()).isEqualTo(8);
        }

        /// T13:the only plan-time survivor A leaves the ring at the first send. A plan-time survivor is no holder (#3345): without an
        /// ack the key stays owed, its page keeps being resent after A left, and the report counts it owed (as classed at plan time,
        /// replicated; the F7 promotion to sole is gone). It has a deliverer, so no AT_RISK.
        @Test
        @Timeout(20)
        void pushOnDeparture_existingReplicaRemovedMidPush_staysOwed() {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            var probe = keyHeldBy(ring, 2, Set.of(LOCAL, PEER_A), "t13");
            node.putLocal(probe, value("payload")).await();
            var network = ScriptedNetwork.scriptedOver(ring, (index, _) -> index == 0 ? removeAndDrop(ring, PEER_A) : Reply.DROP);
            var t0 = System.nanoTime();
            var run = push(node, network, T(1_500, 500, 50), Set.of());

            assertThat(network.sends()).anyMatch(sent -> sent.atNanos() - t0 > ms(1_500).nanos());
            assertThat(run.recorder().atRisk()).isEmpty();
            assertThat(run.recorder().incomplete()).containsExactly(new Incomplete(1, 0));
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
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
                                                                        T(200, 300, 50).tuning(),
                                                                        Set::of,
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
                                              T(10_000, 10_000, 50).tuning(),
                                              Set::of,
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
                                              T(10_000, 10_000, 50).tuning(),
                                              Set::of,
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
                                                              T(10_000, 10_000, 50).tuning(),
                                                              Set::of,
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
            var tuning = timing(10_000, 10_000, 5_000, 50, 200);
            var run = push(node, network, tuning, Set.of());

            assertThat(network.sends()).hasSize(2);
            assertThat(run.elapsedMillis()).isLessThan(2_000);
            assertThat(run.report().soleCopyAtRisk()).isZero();
        }

        /// #2684 F1: AT_RISK is raised at plan time (the drainer is the ring's only node, so the sole key has no deliverer, #3345) and
        /// its observer call is held open; a node joins, its page is acked and the push finishes meanwhile. The acking thread returns
        /// without waiting for the held call (the transition lock is never held across an observer call), DELIVERED is not called
        /// before AT_RISK returned, and the push does not resolve while the AT_RISK call is in flight: its event is awaited like
        /// every other.
        @Test
        @Timeout(30)
        void pushOnDeparture_atRiskCallInFlightAtLastAck_resolvesOnlyAfterAtRiskThenDelivered() throws InterruptedException {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var recorder = new EventRecorder(() -> holdOpen(entered, release));
            var held = HeldAtRisk.heldAtRisk(recorder, "f1");

            assertThat(entered.await(10, TimeUnit.SECONDS)).as("control: the plan-time AT_RISK call is in flight").isTrue();
            var ackReturned = held.joinAndAck();
            var resolvedWhileHeld = held.push().await(ms(1_000)).isSuccess();
            var deliveredWhileHeld = recorder.delivered();

            release.countDown();
            var report = held.push().await().unwrap();

            assertThat(ackReturned).as("the acking thread never waits for an observer call").isTrue();
            assertThat(deliveredWhileHeld).as("DELIVERED is not called before AT_RISK returned").isEmpty();
            assertThat(resolvedWhileHeld).as("the push does not resolve while its AT_RISK call is in flight").isFalse();
            assertThat(recorder.atRisk()).containsExactly(1);
            assertThat(recorder.delivered()).containsExactly(1);
            assertThat(report.soleCopyAtRisk()).isZero();
        }

        /// #2684 P1: the AT_RISK raise is held in its log line, an effect that runs BEFORE the observer call, outside the transition
        /// lock. The event was decided under the lock, so it is already in `events` (and so is the DELIVERED event the joined node's
        /// ack decides next): the push must not resolve while the raising thread is still draining its effects.
        @Test
        @Timeout(30)
        void pushOnDeparture_winnerHeldBeforeItsObserverCall_pushWaitsForTheRegisteredEvents() throws InterruptedException {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var recorder = new EventRecorder(() -> {});

            try (var _ = LogCapture.capture(DeparturePushRun.class.getName(), Level.WARN, true, holdAtRiskLog(entered, release, _ -> {}))) {
                var held = HeldAtRisk.heldAtRisk(recorder, "p1");

                assertThat(entered.await(10, TimeUnit.SECONDS)).as("control: the raise is held in its AT_RISK log line").isTrue();
                var ackReturned = held.joinAndAck();
                var resolvedWhileHeld = held.push().await(ms(1_000)).isSuccess();
                var atRiskWhileHeld = recorder.atRisk();

                release.countDown();
                var report = held.push().await().unwrap();

                assertThat(ackReturned).as("the acking thread never waits for the draining raise").isTrue();
                assertThat(atRiskWhileHeld).as("control: the observer call is still behind the held log line").isEmpty();
                assertThat(resolvedWhileHeld).as("the push does not resolve while its registered events are pending").isFalse();
                assertThat(recorder.atRisk()).containsExactly(1);
                assertThat(recorder.delivered()).containsExactly(1);
                assertThat(report.soleCopyAtRisk()).isZero();
            }
        }

        /// #2684 P1: effects run in the order their transitions queued them, across transitions. The plan-time AT_RISK raise (log
        /// line, observer call) is held in its log line; the joined node's ack and the exit queue the DELIVERED effects meanwhile.
        /// The AT_RISK line and call precede the DELIVERED line and call.
        @Test
        @Timeout(30)
        void pushOnDeparture_effectsOfTwoTransitionsQueuedBeforeOneDrain_runInTransitionOrder() throws InterruptedException {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var trace = new CopyOnWriteArrayList<String>();
            var recorderRef = new AtomicReference<EventRecorder>();
            var recorder = new EventRecorder(() -> trace.add("AT_RISK call, delivered calls seen: " + recorderRef.get().delivered().size()));
            recorderRef.set(recorder);

            try (var _ = LogCapture.capture(DeparturePushRun.class.getName(),
                                            Level.INFO,
                                            true,
                                            holdAtRiskLog(entered, release, line -> trace.add(line + ", delivered calls seen: " + recorder.delivered().size())))) {
                var held = HeldAtRisk.heldAtRisk(recorder, "p1order");

                assertThat(entered.await(10, TimeUnit.SECONDS)).as("control: the raise is held in its AT_RISK log line").isTrue();
                var ackReturned = held.joinAndAck();

                release.countDown();
                held.push().await();

                assertThat(ackReturned).as("control: the ack was decided while the raise was held").isTrue();
                assertThat(recorder.delivered()).as("control: the delivered event was raised").containsExactly(1);
                assertThat(trace).containsExactly("without a holder, delivered calls seen: 0",
                                                  "AT_RISK call, delivered calls seen: 0",
                                                  "every sole-copy chunk has a holder, delivered calls seen: 0");
            }
        }

        /// A push whose sole key has no deliverer at plan time (the drainer is the ring's only node): AT_RISK is raised at plan
        /// time on the thread that enumerated the inventory, never on the test thread. [#joinAndAck] then gives the key a deliverer.
        private record HeldAtRisk(ConsistentHashRing<NodeId> ring,
                                  ScriptedNetwork network,
                                  DHTRebalancer rebalancer,
                                  Promise<DeparturePushReport> push) {
            static HeldAtRisk heldAtRisk(EventRecorder recorder, String keyText) {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();
                ring.addNode(LOCAL);
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                node.putLocal(key(keyText), value("payload")).await();
                var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
                var rebalancer = dhtRebalancer(node, network, RF1);
                network.ackVia(rebalancer::onMigrationDataAck);
                var push = onAnotherThread(() -> rebalancer.pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + ms(60_000).nanos()),
                                                                             timing(300, 10_000, 10_000, 10_000, 200).tuning(),
                                                                             Set::of,
                                                                             recorder));
                return new HeldAtRisk(ring, network, rebalancer, push);
            }

            /// PEER_A joins, followed by `onViewChange` as `DHTTopologyListener` does; the page it is sent is acked from another
            /// thread. True iff the acking thread returned within 2 s.
            boolean joinAndAck() throws InterruptedException {
                ring.addNode(PEER_A);
                Thread.ofPlatform().start(rebalancer::onViewChange);
                var sent = firstSend();
                var acker = Thread.ofPlatform()
                                  .start(() -> rebalancer.onMigrationDataAck(new DHTMessage.MigrationDataAck(sent.response().requestId(),
                                                                                                             sent.target(),
                                                                                                             true)));
                return acker.join(Duration.ofSeconds(2));
            }

            private TimedSend firstSend() {
                var until = System.nanoTime() + ms(10_000).nanos();
                while (network.sends().isEmpty() && System.nanoTime() < until) {
                    LockSupport.parkNanos(1_000_000);
                }
                assertThat(network.sends()).as("control: the joined node was paged").isNotEmpty();
                assertThat(network.sends().getFirst().target()).isEqualTo(PEER_A);
                return network.sends().getFirst();
            }

            private static Promise<DeparturePushReport> onAnotherThread(Supplier<Promise<DeparturePushReport>> start) {
                Promise<DeparturePushReport> push = Promise.promise();
                Thread.ofVirtual().start(() -> start.get().onResult(push::resolve));
                return push;
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
                if (message.contains("without a holder")) {
                    holdOpen(entered, release);
                }
            };
        }

        private static String tagOf(String message) {
            return Stream.of("without a holder", "every sole-copy chunk has a holder")
                         .filter(message::contains)
                         .findFirst()
                         .orElse("other");
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

        private static void awaitQuietly(CountDownLatch latch, long millis) {
            try {
                latch.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
            var run = push(node, network, timing(2_500, 500, 25, 50, 200), Set.of());

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

        /// The push ends at its deadline (400 ms) with the key owed, although the unanswered page would be resent only after 3 s: the
        /// run's one deadline (#3345) does not wait for the page's resend interval.
        @Test
        @Timeout(20)
        void pushOnDeparture_unackedAttemptWait_endsAtTheDeadline() {
            var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
            var run = replicatedPageUnder(network, "b-wait");

            assertThat(run.elapsedMillis()).isLessThan(1_500);
            assertThat(network.sends()).hasSize(1);
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
        }

        /// The same for the 3 s pause after a nack.
        @Test
        @Timeout(20)
        void pushOnDeparture_nackPause_endsAtTheDeadline() {
            var network = ScriptedNetwork.scripted((_, _) -> Reply.NACK);
            var run = replicatedPageUnder(network, "b-pause");

            assertThat(run.elapsedMillis()).isLessThan(1_500);
            assertThat(network.sends()).hasSize(1);
            assertThat(run.report().replicatedAtRisk()).isEqualTo(1);
        }

        private Run replicatedPageUnder(ScriptedNetwork network, String prefix) {
            var ring = fiveNodeRing();
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF2);
            node.putLocal(keyHeldBy(ring, 2, Set.of(LOCAL, PEER_A), prefix), value("payload")).await();

            return push(node, network, timing(400, 0, 3_000, 3_000, 200), Set.of());
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
        /// The key has no deliverer (the drainer is the ring's only node), so AT_RISK is raised at plan time (#3345) and throws.
        @Test
        @Timeout(20)
        void pushOnDeparture_throwingObserver_isIsolated() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(key("b-throw"), value("payload")).await();
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
                                              timing(10_000, 10_000, 50, 200, 2_000).tuning(),
                                              Set::of,
                                              recorder)
                             .await()
                             .unwrap();

            assertThat(recorder.atRisk()).as("control: the event was raised and is in flight").containsExactly(1);
            assertThat(System.nanoTime() - started).isLessThan(ms(1_000).nanos());
            assertThat(report.soleCopyAtRisk()).isEqualTo(1);
        }

        /// After the node halted nothing more is reported or awaited: the AT_RISK call halts the node and its event never settles;
        /// the push resolves at once, without the incomplete report and without waiting for that event. The key has no deliverer
        /// (the drainer is the ring's only node), so AT_RISK is raised at plan time (#3345).
        @Test
        @Timeout(20)
        void pushOnDeparture_haltedByTheAtRiskCall_reportsAndAwaitsNothingMore() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL);
            var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
            node.putLocal(key("b-halt"), value("payload")).await();
            var halted = new AtomicBoolean(false);
            var recorder = new EventRecorder(() -> halted.set(true), () -> Promise.promise());
            var started = System.nanoTime();

            dhtRebalancer(node, ScriptedNetwork.scripted((_, _) -> Reply.DROP), RF1)
                .pushOnDeparture(DepartureWindow.departureWindow(started + ms(3_000).nanos(), halted::get),
                                 timing(300, 500, 50, 200, 200).tuning(),
                                 Set::of,
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
                                                              T(300, 1_000, 50).tuning(),
                                                              Set::of,
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
                           .pushOnDeparture(DepartureWindow.departureWindow(past, () -> true), T(200, 300, 50).tuning(), Set::of, planRecorder);
            var enumeration = dhtRebalancer(hung, ScriptedNetwork.scripted((_, _) -> Reply.ACK), RF1)
                                  .pushOnDeparture(DepartureWindow.departureWindow(past, () -> true), T(200, 300, 50).tuning(), Set::of, hungRecorder);

            plan.await();
            enumeration.await();

            assertThat(planRecorder.atRisk()).isEmpty();
            assertThat(hungRecorder.atRisk()).isEmpty();
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

        /// #3345 rule (I) and its triggers, against [DeparturePushRun] run directly (its counters are read after the run): a key is
        /// delivered only by an applied ack from a holder in its current tenure at its stamp, after a passed pre-exit probe.
        @Nested
        class DepartureHolder {
            /// Red first. The target leaves V and returns (a new tenure); an ack for the page of its earlier tenure arrives after the
            /// re-entry and never counts: the key stays owed and is paged again in the new tenure.
            @Test
            @Timeout(20)
            void ackFromAnEarlierTenureNeverCounts() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h1");
                node.putLocal(k, value("payload")).await();
                var target = delivererOf(ring, k, Set.of(LOCAL));
                var earlier = new AtomicReference<TimedSend>();
                var delivered = new AtomicBoolean();
                var self = new AtomicReference<ScriptedNetwork>();
                var network = ScriptedNetwork.scriptedOver(ring, (_, sent) -> earlierTenure(ring, target, sent, earlier, delivered, self));
                self.set(network);
                var run = direct(node, network, 1_500, 100, Set::of);

                assertThat(delivered).as("the earlier tenure's ack arrived after the re-entry").isTrue();
                assertThat(network.sends()).as("paged again in the new tenure")
                                           .anyMatch(sent -> sent.target().equals(target) && !sameRequest(sent, earlier.get()));
                assertThat(run.report().owed()).isEqualTo(1);
            }

            private Reply earlierTenure(ConsistentHashRing<NodeId> ring,
                                        NodeId target,
                                        TimedSend sent,
                                        AtomicReference<TimedSend> earlier,
                                        AtomicBoolean delivered,
                                        AtomicReference<ScriptedNetwork> self) {
                if (sent.target().equals(target) && earlier.compareAndSet(null, sent)) {
                    ring.removeNode(target);
                } else if (!sent.target().equals(target) && !ring.nodes().contains(target)) {
                    ring.addNode(target);
                } else if (sent.target().equals(target) && !sameRequest(sent, earlier.get()) && delivered.compareAndSet(false, true)) {
                    self.get().deliver(earlier.get(), Reply.ACK);
                }
                return Reply.DROP;
            }

            /// Red first (R4): a key anti-entropy brought to the drainer after the fence came from a current holder; the exit re-read
            /// finds it and does not owe it.
            @Test
            @Timeout(20)
            void keyAfterTheFenceIsNotOwed() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                node.putLocal(soleKey(ring, "h2"), value("payload")).await();
                var late = key("h2-late");
                var written = new AtomicBoolean();
                var network = ScriptedNetwork.scripted((_, sent) -> carries(sent, "h2-late")
                                                                    ? Reply.DROP
                                                                    : writeOnceThenAck(node, late, written));
                var run = direct(node, network, 1_000, 100, Set::of);

                assertThat(written).as("the late key was written during the drain").isTrue();
                assertThat(node.storage().stamps().await().unwrap()).anyMatch(stamp -> Arrays.equals(stamp.key(), late));
                assertThat(network.sends()).noneMatch(sent -> carries(sent, "h2-late"));
                assertThat(run.report().enumeratedKeys()).isEqualTo(1);
                assertThat(run.report().owed()).isZero();
            }

            private static Reply writeOnceThenAck(DHTNode node, byte[] key, AtomicBoolean written) {
                if (written.compareAndSet(false, true)) {
                    write(node, key, "rewritten");
                }
                return Reply.ACK;
            }

            private static final AtomicInteger LOGICAL = new AtomicInteger();

            /// A local write stamped with the wall clock now (HLC), as a client write is.
            private static void write(DHTNode node, byte[] key, String text) {
                node.putLocalVersioned(key, value(text), HlcTimestamp.pack(System.currentTimeMillis(), LOGICAL.incrementAndGet())).await();
            }

            /// A key the first inventory missed whose stamp predates the fence is found by the exit re-read and owed.
            @Test
            @Timeout(20)
            void preExitReReadFindsALateKey() {
                var ring = fiveNodeRing();
                var late = key("h3-late");
                var reads = new AtomicInteger();
                var storage = stampsVia(memory -> memory.stamps()
                                                        .map(stamps -> reads.getAndIncrement() == 0
                                                                       ? stamps.stream().filter(stamp -> !Arrays.equals(stamp.key(), late)).toList()
                                                                       : stamps));
                var node = dhtNode(LOCAL, storage, ring, RF1);
                node.putLocal(soleKey(ring, "h3"), value("payload")).await();
                write(node, late, "late");
                LockSupport.parkNanos(ms(5).nanos());
                var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
                var run = direct(node, network, 1_500, 100, Set::of);

                assertThat(reads.get()).as("the inventory was re-read").isGreaterThanOrEqualTo(2);
                assertThat(network.sends()).anyMatch(sent -> carries(sent, "h3-late"));
                assertThat(run.report().enumeratedKeys()).isEqualTo(2);
                assertThat(run.report().owed()).isZero();
            }

            /// Within one tenure, an ack for a page a re-plan cancelled (a joiner took the key over, then left) still counts.
            @Test
            @Timeout(20)
            void lateAckWithinOneTenureCounts() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h4");
                node.putLocal(k, value("payload")).await();
                var target = delivererOf(ring, k, Set.of(LOCAL));
                var joiner = takeover(ring, k);
                var cancelled = new AtomicReference<TimedSend>();
                var delivered = new AtomicBoolean();
                var self = new AtomicReference<ScriptedNetwork>();
                var network = ScriptedNetwork.scriptedOver(ring,
                                                           (_, sent) -> cancelledThenLate(ring, target, joiner, sent, cancelled, delivered, self, Reply.ACK));
                self.set(network);
                var run = direct(node, network, 1_500, 100, Set::of);

                assertThat(delivered).as("the cancelled page's ack arrived late").isTrue();
                assertThat(network.sends()).anyMatch(sent -> sent.target().equals(joiner));
                assertThat(run.report().owed()).isZero();
            }

            /// The first page to `target` is cancelled by `joiner` taking the key over; the joiner leaves at its first page; at the
            /// target's next page the cancelled page is answered with `late`.
            private Reply cancelledThenLate(ConsistentHashRing<NodeId> ring,
                                            NodeId target,
                                            NodeId joiner,
                                            TimedSend sent,
                                            AtomicReference<TimedSend> cancelled,
                                            AtomicBoolean delivered,
                                            AtomicReference<ScriptedNetwork> self,
                                            Reply late) {
                if (sent.target().equals(target) && cancelled.compareAndSet(null, sent)) {
                    ring.addNode(joiner);
                } else if (sent.target().equals(joiner)) {
                    ring.removeNode(joiner);
                } else if (sent.target().equals(target) && !sameRequest(sent, cancelled.get()) && delivered.compareAndSet(false, true)) {
                    self.get().deliver(cancelled.get(), late);
                }
                return Reply.DROP;
            }

            /// Safe is per (key, stamp): the key is rewritten locally after its page was read; the ack counts for the old stamp and
            /// the exit re-read owes the newer one again.
            @Test
            @Timeout(20)
            void newerLocalStampIsOwedAgain() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h5");
                write(node, k, "v1");
                var written = new AtomicBoolean();
                var network = ScriptedNetwork.scripted((_, _) -> writeOnceThenAck(node, k, written));
                var run = direct(node, network, 1_500, 100, Set::of);

                assertThat(network.sends()).as("the newer stamp was paged again").hasSizeGreaterThanOrEqualTo(2);
                assertThat(network.sends()).allMatch(sent -> isOnly(sent, new String(k, StandardCharsets.UTF_8)));
                assertThat(run.report().owed()).isZero();
            }

            /// #3345: a probe unanswered past `resendAfter` is sent again, never a holder lost: the key is not paged again and the
            /// run closes before its deadline.
            @Test
            @Timeout(20)
            void probeSilencePastResendIsResentNotHolderLost() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                node.putLocal(soleKey(ring, "h6"), value("payload")).await();
                var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
                var probes = new AtomicInteger();
                network.probesAnswer(_ -> probes.getAndIncrement() == 0 ? Reply.DROP : Reply.ACK);
                var started = System.nanoTime();
                var run = direct(node, network, 3_000, 100, Set::of);

                assertThat(run.run().holderLosses()).isZero();
                assertThat(network.probes()).as("the silent probe was sent again").hasSizeGreaterThanOrEqualTo(2);
                assertThat(network.sends()).as("no re-push").hasSize(1);
                assertThat(run.report().owed()).isZero();
                assertThat(System.nanoTime() - started).as("closed before the deadline").isLessThan(ms(3_000).nanos());
            }

            /// A holder lost between a passed probe and the close (here a draining nack for a cancelled page of a node that holds
            /// nothing) re-opens the run: a second exit attempt probes again before the close.
            @Test
            @Timeout(20)
            void holderLostAfterProbeReopens() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h7");
                node.putLocal(k, value("payload")).await();
                var target = delivererOf(ring, k, Set.of(LOCAL));
                var joiner = takeover(ring, k);
                var cancelled = new AtomicReference<TimedSend>();
                var self = new AtomicReference<ScriptedNetwork>();
                var network = ScriptedNetwork.scriptedOver(ring, (_, sent) -> joinerTakesOver(ring, target, joiner, sent, cancelled));
                self.set(network);
                var probes = new AtomicInteger();
                network.probesAnswer(probe -> lossDuringFirstProbe(probes, cancelled, self));
                var run = direct(node, network, 3_000, 100, Set::of);

                assertThat(cancelled.get()).isNotNull();
                assertThat(run.run().holderLosses()).isEqualTo(1);
                assertThat(network.probes()).as("re-opened: probed again").hasSize(2);
                assertThat(run.report().owed()).isZero();
            }

            private static Reply joinerTakesOver(ConsistentHashRing<NodeId> ring,
                                                 NodeId target,
                                                 NodeId joiner,
                                                 TimedSend sent,
                                                 AtomicReference<TimedSend> cancelled) {
                if (sent.target().equals(target) && cancelled.compareAndSet(null, sent)) {
                    ring.addNode(joiner);
                    return Reply.DROP;
                }
                return sent.target().equals(joiner) ? Reply.ACK : Reply.DROP;
            }

            private static Reply lossDuringFirstProbe(AtomicInteger probes,
                                                      AtomicReference<TimedSend> cancelled,
                                                      AtomicReference<ScriptedNetwork> self) {
                if (probes.getAndIncrement() == 0) {
                    self.get().deliver(cancelled.get(), Reply.DRAINING_NACK);
                }
                return Reply.ACK;
            }

            /// A nack that says "draining" is a holder lost: the run re-plans with the target now in L and pages the next node.
            @Test
            @Timeout(20)
            void drainingNackIsHolderLost() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h8");
                node.putLocal(k, value("payload")).await();
                var target = delivererOf(ring, k, Set.of(LOCAL));
                var next = delivererOf(ring, k, Set.of(LOCAL, target));
                var drainSet = new AtomicReference<Set<NodeId>>(Set.of());
                var network = ScriptedNetwork.scripted((_, sent) -> drainingAt(target, sent, drainSet));
                var run = direct(node, network, 1_500, 100, drainSet::get);

                assertThat(run.run().holderLosses()).isEqualTo(1);
                assertThat(network.sends()).anyMatch(sent -> sent.target().equals(next));
                assertThat(run.report().owed()).isZero();
            }

            private static Reply drainingAt(NodeId target, TimedSend sent, AtomicReference<Set<NodeId>> drainSet) {
                if (sent.target().equals(target)) {
                    drainSet.set(Set.of(target));
                    return Reply.DRAINING_NACK;
                }
                return Reply.ACK;
            }

            /// No target outside L: the co-drainer gets the page as a named-prefix target and acks it, yet it is no deliverer, so the
            /// key stays owed.
            @Test
            @Timeout(20)
            void emptyTargetSetIsOwed() {
                var ring = ConsistentHashRing.<NodeId>consistentHashRing();
                List.of(LOCAL, PEER_A).forEach(ring::addNode);
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                node.putLocal(soleKey(ring, "h9"), value("payload")).await();
                var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
                var run = direct(node, network, 500, 100, () -> Set.of(PEER_A));

                assertThat(network.ackedKeys()).as("the co-drainer acked the key").isNotEmpty();
                assertThat(run.report().owed()).isEqualTo(1);
            }

            /// A key removed after the inventory is read is never sent and never counted delivered; with no exit re-read (another
            /// key stays owed to the deadline) it is still owed in the report.
            @Test
            @Timeout(20)
            void keyRemovedMeanwhileIsNotDelivered() {
                var ring = fiveNodeRing();
                var removed = soleKey(ring, "h10-removed");
                var storage = stampsVia(memory -> memory.stamps().flatMap(stamps -> memory.remove(removed).map(_ -> stamps)));
                var node = dhtNode(LOCAL, storage, ring, RF1);
                node.putLocal(removed, value("payload")).await();
                node.putLocal(soleKey(ring, "h10-kept"), value("payload")).await();
                var network = ScriptedNetwork.scripted((_, _) -> Reply.DROP);
                var run = direct(node, network, 800, 100, Set::of);

                assertThat(network.sends()).isNotEmpty().noneMatch(sent -> carries(sent, new String(removed, StandardCharsets.UTF_8)));
                assertThat(run.report().enumeratedKeys()).isEqualTo(2);
                assertThat(run.report().owed()).isEqualTo(2);
            }

            /// EncodeFailed splits the page once into single-key pages; the key whose own page still fails is undeliverable, counted
            /// apart and still owed; the other key is acked and, confirmed by the deadline probe (P3), delivered.
            @Test
            @Timeout(20)
            void encodeFailedSplitsThenUndeliverable() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var good = soleKey(ring, "h11");
                var bad = sameDeliverer(ring, good, "h11-bad");
                var badText = new String(bad, StandardCharsets.UTF_8);
                node.putLocal(good, value("payload")).await();
                node.putLocal(bad, value("payload")).await();
                var network = ScriptedNetwork.scripted((_, sent) -> carries(sent, badText)
                                                                    ? Reply.refuse(id -> new WriteOutcome.EncodeFailed(id, "MigrationDataResponse"))
                                                                    : Reply.ACK);
                var run = direct(node, network, 800, 100, Set::of);

                assertThat(network.sends().getFirst().response().entries()).as("one page of both keys first").hasSize(2);
                assertThat(network.sends()).filteredOn(sent -> isOnly(sent, badText)).hasSize(1);
                assertThat(network.ackedKeys()).containsExactly(new String(good, StandardCharsets.UTF_8));
                assertThat(run.report().undeliverable()).isEqualTo(1);
                assertThat(run.report().owed()).as("the acked key is confirmed by the deadline probe (P3)").isEqualTo(1);
            }

            /// #3345 P3: a run that ends at its deadline (one key is never acked, so no exit starts) probes the holder of its acked
            /// key before it reports, and owes exactly the key never acked.
            @Test
            @Timeout(20)
            void deadlineCloseProbesTheAckedKeysHolder_owesOnlyTheUnackedKey() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var acked = soleKey(ring, "h20");
                var unacked = sameDeliverer(ring, acked, "h20-unacked");
                var ackedText = new String(acked, StandardCharsets.UTF_8);
                var unackedText = new String(unacked, StandardCharsets.UTF_8);
                node.putLocal(acked, value("payload")).await();
                node.putLocal(unacked, value("payload")).await();
                var network = ScriptedNetwork.scripted((_, sent) -> carries(sent, unackedText) ? Reply.DROP : Reply.ACK);
                var run = direct(node, network, 800, 100, Set::of);

                assertThat(run.report().owed()).as("exactly the key never acked").isEqualTo(1);
                assertThat(network.ackedKeys()).contains(ackedText).doesNotContain(unackedText);
                assertThat(network.probes()).as("the deadline probe of the acked key's holder")
                                            .isNotEmpty()
                                            .allMatch(probe -> probe.target().equals(delivererOf(ring, acked, Set.of(LOCAL))));
                assertThat(run.run().exitAttempts()).as("no exit: the unacked key stays owed").isZero();
            }

            /// #3345 P3 (verifier M3): the holder acks, then stops answering probes; the unacked key's chain keeps the exit from
            /// starting; the deadline probe goes unanswered, so the acked key is owed in the incomplete report.
            @Test
            @Timeout(20)
            void deadlineProbeUnanswered_ackedKeyIsOwed() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var acked = soleKey(ring, "h21");
                var unacked = sameDeliverer(ring, acked, "h21-unacked");
                var ackedText = new String(acked, StandardCharsets.UTF_8);
                var unackedText = new String(unacked, StandardCharsets.UTF_8);
                node.putLocal(acked, value("payload")).await();
                node.putLocal(unacked, value("payload")).await();
                var network = ScriptedNetwork.scripted((_, sent) -> carries(sent, unackedText) ? Reply.DROP : Reply.ACK);
                network.probesAnswer(_ -> Reply.DROP);
                var run = direct(node, network, 800, 100, Set::of);

                assertThat(network.ackedKeys()).as("control: the holder acked").contains(ackedText);
                assertThat(network.probes()).as("control: the deadline probe was sent").isNotEmpty();
                assertThat(run.report().owed()).as("the acked key, its holder silent at the deadline probe, is owed").isEqualTo(2);
            }

            /// #3345 P3, the cost: a deadline close ends no later than the window plus one probe deadline (`resendAfter`), here
            /// with the probe unanswered so the close waits the whole probe deadline.
            @Test
            @Timeout(20)
            void deadlineClose_endsWithinTheWindowPlusOneProbeDeadline() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var acked = soleKey(ring, "h22");
                var unacked = sameDeliverer(ring, acked, "h22-unacked");
                var unackedText = new String(unacked, StandardCharsets.UTF_8);
                node.putLocal(acked, value("payload")).await();
                node.putLocal(unacked, value("payload")).await();
                var network = ScriptedNetwork.scripted((_, sent) -> carries(sent, unackedText) ? Reply.DROP : Reply.ACK);
                network.probesAnswer(_ -> Reply.DROP);
                var started = System.nanoTime();
                var run = direct(node, network, 600, 300, Set::of);
                var elapsed = System.nanoTime() - started;

                assertThat(network.probes()).as("control: probed at the deadline, not before")
                                            .isNotEmpty()
                                            .allMatch(probe -> probe.atNanos() - started >= ms(600).nanos());
                assertThat(run.report().owed()).isEqualTo(2);
                assertThat(elapsed).as("window 600 ms + probe deadline 300 ms (+ scheduling slack)")
                                   .isGreaterThanOrEqualTo(ms(600).nanos())
                                   .isLessThan(ms(600 + 300 + 250).nanos());
            }

            /// #3345 P3 with P2: a halted run sends no probe; it closes at once with nothing more sent or raised.
            @Test
            @Timeout(20)
            void haltedRun_sendsNoProbe() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var acked = soleKey(ring, "h23");
                var unacked = sameDeliverer(ring, acked, "h23-unacked");
                var ackedText = new String(acked, StandardCharsets.UTF_8);
                var unackedText = new String(unacked, StandardCharsets.UTF_8);
                node.putLocal(acked, value("payload")).await();
                node.putLocal(unacked, value("payload")).await();
                var halted = new AtomicBoolean(false);
                var unackedSends = new AtomicInteger();
                var network = ScriptedNetwork.scripted((_, sent) -> haltAtSecondSoloSend(sent, unackedText, unackedSends, halted));
                var started = System.nanoTime();
                var run = direct(node, network, 3_000, 100, Set::of, new AtomicReference<>(), halted::get);

                assertThat(halted.get()).as("control: the run was halted").isTrue();
                assertThat(network.ackedKeys()).as("control: a key was acked, so a probe would be due").contains(ackedText);
                assertThat(network.probes()).as("a halt sends nothing").isEmpty();
                assertThat(System.nanoTime() - started).as("closed at the halt, not at the deadline").isLessThan(ms(3_000).nanos());
            }

            private static Reply haltAtSecondSoloSend(TimedSend sent, String unackedText, AtomicInteger unackedSends, AtomicBoolean halted) {
                if (isOnly(sent, unackedText) && unackedSends.incrementAndGet() == 2) {
                    halted.set(true);
                }
                return carries(sent, unackedText) ? Reply.DROP : Reply.ACK;
            }

            /// Acks and nacks never re-plan: reconciles = start + view changes + holder losses + exit attempts.
            @Test
            @Timeout(20)
            void nackTriggersNoReconcile() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                node.putLocal(soleKey(ring, "h12"), value("payload")).await();
                var network = ScriptedNetwork.scripted((index, _) -> index < 5 ? Reply.NACK : Reply.ACK);
                var run = direct(node, network, 2_000, 20, Set::of);

                assertThat(network.sends()).hasSize(6);
                assertThat(run.report().owed()).isZero();
                assertReconcilesBounded(run.run());
            }

            /// 50 flaps of a node outside the key's placement, each page nacked: reconciles stay within view changes + losses +
            /// attempts + 1 (counted on the run).
            @Test
            @Timeout(30)
            void flapIsBounded() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h13");
                node.putLocal(k, value("payload")).await();
                var flapper = bystander(ring, k);
                var network = ScriptedNetwork.scriptedOver(ring, (index, _) -> index < 50 ? flap(ring, flapper) : Reply.ACK);
                var run = direct(node, network, 6_000, 10, Set::of);

                assertThat(run.run().viewChanges()).isGreaterThanOrEqualTo(50);
                assertThat(run.report().owed()).isZero();
                assertReconcilesBounded(run.run());
            }

            private static Reply flap(ConsistentHashRing<NodeId> ring, NodeId flapper) {
                if (ring.nodes().contains(flapper)) {
                    ring.removeNode(flapper);
                } else {
                    ring.addNode(flapper);
                }
                return Reply.NACK;
            }

            private static void assertReconcilesBounded(DeparturePushRun run) {
                assertThat(run.reconciles()).isPositive()
                                            .isLessThanOrEqualTo(1 + run.viewChanges() + run.holderLosses() + run.exitAttempts());
            }

            /// A node entering V that becomes the key's deliverer re-owes a key a former deliverer held: it is paged to the joiner.
            @Test
            @Timeout(20)
            void addEdgeReowes() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h14");
                node.putLocal(k, value("payload")).await();
                var joiner = takeover(ring, k);
                var network = ScriptedNetwork.scripted((_, _) -> Reply.ACK);
                var probes = new AtomicInteger();
                var handle = new AtomicReference<DeparturePushRun>();
                network.probesAnswer(_ -> joinAtFirstProbe(ring, joiner, probes, handle));
                var run = direct(node, network, 1_500, 100, Set::of, handle);

                assertThat(network.sends()).anyMatch(sent -> sent.target().equals(joiner));
                assertThat(run.report().owed()).isZero();
            }

            /// The joiner enters V while the first probe is in flight; the view change reaches the run as `DHTTopologyListener`
            /// delivers it, after the ring change.
            private static Reply joinAtFirstProbe(ConsistentHashRing<NodeId> ring,
                                                  NodeId joiner,
                                                  AtomicInteger probes,
                                                  AtomicReference<DeparturePushRun> handle) {
                if (probes.getAndIncrement() == 0) {
                    ring.addNode(joiner);
                    handle.get().onViewChange();
                }
                return Reply.ACK;
            }

            /// L is the drain set read now, not a union: a co-drainer that withdrew is the deliverer again at the next re-plan, so its
            /// ack (taken while it was only a named-prefix target) makes the key safe; the next node never answers.
            @Test
            @Timeout(20)
            void withdrawnCoDrainerLeavesL() {
                var ring = fiveNodeRing();
                var node = dhtNode(LOCAL, memoryStorageEngine(), ring, RF1);
                var k = soleKey(ring, "h15");
                node.putLocal(k, value("payload")).await();
                var coDrainer = delivererOf(ring, k, Set.of(LOCAL));
                var next = delivererOf(ring, k, Set.of(LOCAL, coDrainer));
                var drainSet = new AtomicReference<Set<NodeId>>(Set.of(coDrainer));
                var handle = new AtomicReference<DeparturePushRun>();
                var network = ScriptedNetwork.scripted((_, sent) -> withdrawAtFirstPage(coDrainer, sent, drainSet, handle));
                var run = direct(node, network, 1_500, 100, drainSet::get, handle);

                assertThat(network.sends()).as("while it drained, the next node was the deliverer").anyMatch(sent -> sent.target().equals(next));
                assertThat(network.sends()).anyMatch(sent -> sent.target().equals(coDrainer));
                assertThat(run.report().owed()).isZero();
            }

            /// The co-drainer withdraws at the first page (to another node) and the ring changes, as the view update delivers it.
            private static Reply withdrawAtFirstPage(NodeId coDrainer,
                                                     TimedSend sent,
                                                     AtomicReference<Set<NodeId>> drainSet,
                                                     AtomicReference<DeparturePushRun> handle) {
                if (sent.target().equals(coDrainer)) {
                    return Reply.ACK;
                }
                if (!drainSet.getAndSet(Set.of()).isEmpty()) {
                    Thread.ofVirtual().start(handle.get()::onViewChange);
                }
                return Reply.DROP;
            }
        }

        // --- fixtures ---

        private record Run(DeparturePushReport report, EventRecorder recorder, long elapsedMillis) {}

        private Run push(DHTNode node, ScriptedNetwork network, Timing timing, Set<NodeId> coDeparting) {
            return push(node, network, timing, coDeparting, new EventRecorder(() -> {}));
        }

        private Run push(DHTNode node,
                         ScriptedNetwork network,
                         Timing timing,
                         Set<NodeId> coDeparting,
                         EventRecorder recorder) {
            var rebalancer = dhtRebalancer(node, network, node.config());
            network.ackVia(rebalancer::onMigrationDataAck);
            network.viewChangeVia(rebalancer::onViewChange);
            var started = System.nanoTime();
            var report = rebalancer.pushOnDeparture(DepartureWindow.departureWindow(started + timing.windowNanos()),
                                                    timing.tuning(),
                                                    () -> coDeparting,
                                                    recorder)
                                   .await()
                                   .unwrap();
            return new Run(report, recorder, (System.nanoTime() - started) / 1_000_000);
        }

        /// The legacy (base, sole extra) attempt budget of a push (#2034) as one attempts window (#3345): the push runs until nothing is
        /// owed or `baseMs + extraMs` has passed, then waits `waitMs` for its events.
        private record Timing(long attemptsMs, DeparturePushTuning tuning) {
            long windowNanos() {
                return ms(attemptsMs).nanos() + tuning.eventDeliveryWait().nanos();
            }
        }

        private static Timing timing(long baseMs, long extraMs, long resendMs, long capMs, long waitMs) {
            return new Timing(baseMs + extraMs,
                              new DeparturePushTuning(ms(resendMs), ms(capMs), DeparturePushTuning.DEFAULT.reportAfter(), ms(waitMs)));
        }

        private Timing T(long baseMs, long extraMs, long resendMs) {
            return timing(baseMs, extraMs, resendMs, 4 * resendMs, 200);
        }

        private static TimeSpan ms(long millis) {
            return timeSpan(millis).millis();
        }

        private static boolean isOnly(TimedSend sent, String keyText) {
            return sent.response().entries().size() == 1 && carries(sent, keyText);
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
                           .filter(response -> !response.entries().isEmpty())
                           .toList();
        }

        List<NodeId> targets() {
            return captured.stream()
                           .filter(m -> m.message() instanceof DHTMessage.MigrationDataResponse response
                                        && !response.entries().isEmpty())
                           .map(CapturedMessage::target)
                           .toList();
        }
    }

    /// What a [ScriptedNetwork] does with one departure push.
    /// An ack or nack carries the receiver's boot token, store generation and "draining" flag (#3345: token 0, generation 0, not
    /// draining unless a test scripts otherwise).
    record Reply(String kind, boolean async, Function<NodeId, WriteOutcome> refusal, long token, long generation, boolean draining) {
        static final Reply DROP = new Reply("drop", false, null, 0L, 0L, false);
        static final Reply ACK = new Reply("ack", false, null, 0L, 0L, false);
        static final Reply NACK = new Reply("nack", false, null, 0L, 0L, false);
        static final Reply ASYNC_ACK = new Reply("ack", true, null, 0L, 0L, false);
        static final Reply ASYNC_NACK = new Reply("nack", true, null, 0L, 0L, false);
        static final Reply DRAINING_NACK = new Reply("nack", false, null, 0L, 0L, true);

        static Reply refuse(Function<NodeId, WriteOutcome> refusal) {
            return new Reply("refuse", false, refusal, 0L, 0L, false);
        }

        /// An applied ack from a process whose boot token is `token`.
        static Reply ackWithToken(long token) {
            return new Reply("ack", false, null, token, 0L, false);
        }

        /// The same answer, delivered later from another thread.
        Reply later() {
            return new Reply(kind, true, refusal, token, generation, draining);
        }
    }

    record TimedSend(NodeId target, DHTMessage.MigrationDataResponse response, long atNanos) {}

    /// A network double whose answer to the n-th departure push (0-based, across all targets) is scripted. An ack or nack is routed
    /// to the sink synchronously inside `send` unless the reply is async. Non-departure messages are captured and dropped.
    static final class ScriptedNetwork implements DHTNetwork {
        private final CopyOnWriteArrayList<TimedSend> sends = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<TimedSend> probes = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> ackedKeys = new CopyOnWriteArrayList<>();
        private final AtomicReference<Consumer<DHTMessage.MigrationDataAck>> ackSink = new AtomicReference<>(ack -> {});
        private final AtomicReference<Runnable> viewChangeSink = new AtomicReference<>(() -> {});
        private final AtomicReference<Function<TimedSend, Reply>> probeScript = new AtomicReference<>(_ -> Reply.ACK);
        private final AtomicBoolean probeSendsHang = new AtomicBoolean();
        private final java.util.function.BiFunction<Integer, TimedSend, Reply> script;
        private final Option<ConsistentHashRing<NodeId>> watchedRing;
        private final AtomicReference<Set<NodeId>> lastView;

        private ScriptedNetwork(java.util.function.BiFunction<Integer, TimedSend, Reply> script,
                                Option<ConsistentHashRing<NodeId>> watchedRing) {
            this.script = script;
            this.watchedRing = watchedRing;
            this.lastView = new AtomicReference<>(watchedRing.map(ring -> Set.copyOf(ring.nodes())).or(Set.of()));
        }

        static ScriptedNetwork scripted(java.util.function.BiFunction<Integer, TimedSend, Reply> script) {
            return new ScriptedNetwork(script, Option.none());
        }

        /// A network whose script changes `ring`: like `DHTTopologyListener` after each `changeRing`, every change the script
        /// makes is followed by the rebalancer's `onViewChange` (#3345's re-plan trigger), delivered from another thread.
        static ScriptedNetwork scriptedOver(ConsistentHashRing<NodeId> ring,
                                            java.util.function.BiFunction<Integer, TimedSend, Reply> script) {
            return new ScriptedNetwork(script, Option.some(ring));
        }

        void ackVia(Consumer<DHTMessage.MigrationDataAck> sink) {
            ackSink.set(sink);
        }

        /// How the pre-exit probes (EMPTY pages) are answered; by default an ack at once with token 0.
        void probesAnswer(Function<TimedSend, Reply> answer) {
            probeScript.set(answer);
        }

        /// Every probe send from now on returns a promise that never resolves (a transport that never reports its outcome).
        void probeSendsHang() {
            probeSendsHang.set(true);
        }

        void viewChangeVia(Runnable sink) {
            viewChangeSink.set(sink);
        }

        private void signalViewChange() {
            watchedRing.map(ring -> Set.copyOf(ring.nodes()))
                       .filter(view -> !view.equals(lastView.getAndSet(view)))
                       .onPresent(_ -> Thread.ofVirtual().start(viewChangeSink.get()));
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, ProtocolMessage message) {
            if (!(message instanceof DHTMessage.MigrationDataResponse response) || !response.ackRequested()) {
                return Promise.success(new WriteOutcome.Sent(target));
            }
            var sent = new TimedSend(target, response, System.nanoTime());
            if (response.entries().isEmpty()) {
                // #3345: an EMPTY page is the pre-exit probe of a holder: kept apart from the data pages, answered as scripted
                // (by default an ack at once).
                probes.add(sent);
                if (probeSendsHang.get()) {
                    return Promise.promise();
                }
                respond(probeScript.get().apply(sent), sent);
                return Promise.success(new WriteOutcome.Sent(target));
            }
            int index;
            synchronized (sends) {
                index = sends.size();
                sends.add(sent);
            }
            var reply = script.apply(index, sent);
            signalViewChange();
            if (reply.kind().equals("refuse")) {
                return Promise.success(reply.refusal().apply(target));
            }
            respond(reply, sent);
            return Promise.success(new WriteOutcome.Sent(target));
        }

        private void respond(Reply reply, TimedSend sent) {
            switch (reply.kind()) {
                case "ack" -> answer(sent, true, reply);
                case "nack" -> answer(sent, false, reply);
                default -> { }
            }
        }

        private void answer(TimedSend sent, boolean applied, Reply reply) {
            var ack = new DHTMessage.MigrationDataAck(sent.response().requestId(),
                                                      sent.target(),
                                                      applied,
                                                      reply.token(),
                                                      reply.generation(),
                                                      reply.draining());
            if (applied) {
                sent.response().entries().forEach(kv -> ackedKeys.add(new String(kv.key(), StandardCharsets.UTF_8)));
            }
            if (reply.async()) {
                CompletableFuture.runAsync(() -> ackSink.get().accept(ack),
                                           CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS));
            } else {
                ackSink.get().accept(ack);
            }
        }

        /// Answer `sent` now with `reply` (a late or stale answer a test delivers itself).
        void deliver(TimedSend sent, Reply reply) {
            respond(reply, sent);
        }

        List<TimedSend> sends() {
            return List.copyOf(sends);
        }

        List<TimedSend> probes() {
            return List.copyOf(probes);
        }

        List<NodeId> targets() {
            return sends.stream().map(TimedSend::target).toList();
        }

        List<String> ackedKeys() {
            return List.copyOf(ackedKeys);
        }
    }

    record Incomplete(int keys, int sole) {}

    /// Records the #2034 departure events; every event's promise settles at once. `onAtRisk` runs inside the at-risk callback.
    static final class EventRecorder implements DeparturePushObserver {
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
