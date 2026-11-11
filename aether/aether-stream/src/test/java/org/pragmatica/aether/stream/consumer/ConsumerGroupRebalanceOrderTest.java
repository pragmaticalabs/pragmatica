// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.consumer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// #2626 (ordering census D1): Rabia commits pending batches in content-hash order, not submission order, so two
/// rebalances submitted within one commit latency can commit the OLDER assignment last. These tests hold the
/// coordinator's submissions un-committed and then commit them against a REAL [KVStore] in a chosen — reversed —
/// order, so the commit order is under the test's control and no sleep is involved. The assertion is on the
/// COMMITTED assignment, which is what consumers read: the newest member list must own the partitions.
@Timeout(60)
class ConsumerGroupRebalanceOrderTest {
    private static final NodeId SELF = new NodeId("leader-node");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final String GROUP = "my-group";
    private static final String STREAM = "events";
    private static final int PARTITIONS = 4;

    private final List<List<KVCommand<AetherKey>>> held = Collections.synchronizedList(new ArrayList<>());
    private KVStore<AetherKey, AetherValue> store;
    private ConsumerGroupRegistry registry;
    private ConsumerGroupCoordinator coordinator;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        registry = ConsumerGroupRegistry.consumerGroupRegistry();
        router.addRoute(ValuePut.class, this::route);
        router.addRoute(ValueRemove.class, this::routeRemove);
        coordinator = newCoordinator(1L);
    }

    @SuppressWarnings("unchecked")
    private void route(ValuePut<?, ?> event) {
        if (event.cause().key() instanceof ConsumerGroupKey) {
            registry.onConsumerGroupPut((ValuePut<ConsumerGroupKey, ConsumerGroupValue>) event);
        }
    }

    @SuppressWarnings("unchecked")
    private void routeRemove(ValueRemove<?, ?> event) {
        if (event.cause().key() instanceof ConsumerGroupKey) {
            registry.onConsumerGroupRemove((ValueRemove<ConsumerGroupKey, ConsumerGroupValue>) event);
        }
    }

    private ConsumerGroupCoordinator newCoordinator(long leaderTerm) {
        var created = ConsumerGroupCoordinator.consumerGroupCoordinator(heldClusterNode(),
                                                                        registry,
                                                                        () -> Epoch.epoch(1L, leaderTerm, 0L));

        created.onLeaderChange(new LeaderChange(Option.some(SELF), true));

        return created;
    }

    /// The commit is the test's to schedule: `apply` only records the batch.
    private ClusterNode<KVCommand<AetherKey>> heldClusterNode() {
        return new ClusterNode<>() {
            @Override
            public NodeId self() {
                return SELF;
            }

            @Override
            public TopologyManager topologyManager() {
                return null;
            }

            @Override
            public Promise<Unit> start() {
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.success(Unit.unit());
            }

            @Override
            @SuppressWarnings("unchecked")
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                held.add(commands);
                return Promise.success((List<R>) List.of());
            }
        };
    }

    private void commit(int heldIndex) {
        store.process(store.createBatch(held.get(heldIndex)));
    }

    private Map<Integer, String> committedOwners() {
        var owners = new TreeMap<Integer, String>();

        for (var partition = 0; partition < PARTITIONS; partition++) {
            recordOwner(owners, partition);
        }

        return owners;
    }

    private void recordOwner(Map<Integer, String> owners, int partition) {
        store.get(ConsumerGroupKey.consumerGroupKey(GROUP, STREAM, partition))
             .onPresent(value -> owners.put(partition, ((ConsumerGroupValue) value).consumerId()));
    }

    @Nested
    class JoinThenJoin {
        @Test
        void rebalance_newestJoinWins_whenOlderBatchCommitsLast() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);
            assertThat(held).hasSize(2);

            commit(1);
            commit(0);

            assertThat(committedOwners()).as("c2 joined last and must own its share, not be starved by the older batch")
                                         .containsValue("c2");
            assertThat(committedOwners().values()).containsExactlyInAnyOrder("c1", "c2", "c1", "c2");
        }

        @Test
        void rebalance_newestJoinWins_whenBatchesCommitInSubmissionOrder() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);

            commit(0);
            commit(1);

            assertThat(committedOwners().values()).containsExactlyInAnyOrder("c1", "c2", "c1", "c2");
        }
    }

    @Nested
    class JoinThenLeave {
        @Test
        void rebalance_departedConsumerReleasesPartitions_whenOlderBatchesCommitLast() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);
            coordinator.leaveGroup(GROUP, STREAM, "c1");
            assertThat(held).hasSize(3);

            commit(2);
            commit(1);
            commit(0);

            assertThat(committedOwners().values()).as("c1 left; the newest batch gives every partition to c2")
                                                  .containsOnly("c2")
                                                  .hasSize(PARTITIONS);
        }
    }

    /// #2635: the last member leaving used to submit nothing, so the committed assignments kept naming it.
    @Nested
    class LastMemberLeaves {
        @Test
        void leaveGroup_lastMember_unassignsEveryCommittedPartition() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            commit(0);
            assertThat(committedOwners().values()).as("precondition: c1 owns every partition").containsOnly("c1");

            coordinator.leaveGroup(GROUP, STREAM, "c1")
                       .onFailure(cause -> Assertions.fail("Expected success, got: " + cause.message()));
            commit(1);

            assertThat(committedOwners()).as("no committed assignment names the departed member").isEmpty();
            assertThat(registry.assignmentsFor(GROUP, STREAM)).as("no ghost owner for status and metrics").isEmpty();
            assertThat(registry.partitionsFor(GROUP, STREAM, NODE_A)).isEmpty();
        }

        @Test
        void joinGroup_afterTheLastMemberLeft_receivesEveryPartition() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            commit(0);
            coordinator.leaveGroup(GROUP, STREAM, "c1");
            commit(1);

            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);
            commit(2);

            assertThat(committedOwners().values()).containsOnly("c2").hasSize(PARTITIONS);
        }

        @Test
        void leaveGroup_lastMemberWithNothingCommitted_submitsNothing() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            held.clear();

            coordinator.leaveGroup(GROUP, STREAM, "c1")
                       .onFailure(cause -> Assertions.fail("Expected success, got: " + cause.message()));

            assertThat(held).isEmpty();
        }

        /// The removal's witness carries the epoch it was minted under, so a removal committing AFTER a newer join's
        /// assignments is refused by the applier instead of deleting them.
        @Test
        void leaveGroup_lastMember_staleRemovalCommittingAfterANewerJoinDoesNotDeleteIt() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            commit(0);
            coordinator.leaveGroup(GROUP, STREAM, "c1");
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);

            commit(2);
            commit(1);

            assertThat(committedOwners().values()).containsOnly("c2").hasSize(PARTITIONS);
        }

        @Test
        void leaveGroup_lastMember_isRefusedLoudly_whenCommittedAssignmentsCarryANewerTerm() {
            newCoordinator(2L).joinGroup(GROUP, STREAM, PARTITIONS, "c3", NODE_A);
            commit(0);
            held.clear();
            var lagging = newCoordinator(1L);

            lagging.joinGroup(GROUP, STREAM, PARTITIONS, "c4", NODE_B);
            var result = lagging.leaveGroup(GROUP, STREAM, "c4");

            result.onSuccess(_ -> Assertions.fail("a deposed leader must not clear a newer term's assignments"))
                  .onFailure(cause -> assertThat(cause).isEqualTo(ConsumerGroupCoordinator.CoordinatorError.SUPERSEDED));
            assertThat(held).isEmpty();
            assertThat(committedOwners().values()).containsOnly("c3").hasSize(PARTITIONS);
        }
    }

    @Nested
    class SuccessorLeader {
        @Test
        void rebalance_newLeaderBatchIsNotRefused_whenCommittedGenerationIsAhead() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);
            commit(0);
            commit(1);
            held.clear();

            var successor = newCoordinator(2L);

            successor.joinGroup(GROUP, STREAM, PARTITIONS, "c3", NODE_A);
            commit(0);

            assertThat(committedOwners().values()).as("the successor leader's rebalance must land over the committed one")
                                                  .containsOnly("c3")
                                                  .hasSize(PARTITIONS);
        }
    }

    @Nested
    class DeposedLeader {
        /// L1 (term 1) is deposed by L2 (term 2) but its batches are still in flight. L2 has not seen L1's commits,
        /// so the two member lists are unrelated: only the epoch decides, and the older term must lose in BOTH orders.
        @Test
        void rebalance_deposedLeadersLateBatchIsRefused_whenItCommitsAfterTheSuccessors() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);
            newCoordinator(2L).joinGroup(GROUP, STREAM, PARTITIONS, "c3", NODE_A);
            assertThat(held).hasSize(3);

            commit(2);
            commit(1);
            commit(0);

            assertThat(committedOwners().values()).containsOnly("c3").hasSize(PARTITIONS);
        }

        @Test
        void rebalance_successorWins_whenTheDeposedLeadersBatchCommitsFirst() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);
            newCoordinator(2L).joinGroup(GROUP, STREAM, PARTITIONS, "c3", NODE_A);

            commit(0);
            commit(1);
            commit(2);

            assertThat(committedOwners().values()).containsOnly("c3").hasSize(PARTITIONS);
        }
    }

    @Nested
    class LaggingTerm {
        @Test
        void joinGroup_isRefusedLoudly_whenCommittedAssignmentsCarryANewerTerm() {
            newCoordinator(2L).joinGroup(GROUP, STREAM, PARTITIONS, "c3", NODE_A);
            commit(0);
            held.clear();

            var lagging = newCoordinator(1L);
            var result = lagging.joinGroup(GROUP, STREAM, PARTITIONS, "c4", NODE_B);

            result.onSuccess(_ -> Assertions.fail("a lagging term must be refused, not silently submitted"))
                  .onFailure(cause -> assertThat(cause).isEqualTo(ConsumerGroupCoordinator.CoordinatorError.SUPERSEDED));
            assertThat(held).as("a refused rebalance submits nothing").isEmpty();
        }
    }

    @Nested
    class ReAdoptedTerm {
        /// A leader re-adopted at the SAME term restarts its counter at zero; it must stamp above what it committed
        /// before, or the fence (strictly older) would not refuse it but two rebalances would share an epoch.
        @Test
        void rebalance_counterIsReseededAboveTheCommittedOne_whenTheTermIsUnchanged() {
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c1", NODE_A);
            coordinator.joinGroup(GROUP, STREAM, PARTITIONS, "c2", NODE_B);
            commit(0);
            commit(1);
            var committedCounter = epochOf(held.get(1)).localCounter();
            held.clear();

            newCoordinator(1L).joinGroup(GROUP, STREAM, PARTITIONS, "c3", NODE_A);

            assertThat(epochOf(held.getFirst()).localCounter()).isGreaterThan(committedCounter);
            commit(0);
            assertThat(committedOwners().values()).containsOnly("c3").hasSize(PARTITIONS);
        }
    }

    @Nested
    class ConcurrentJoins {
        private static final int CONSUMERS = 6;
        private static final int ROUNDS = 150;

        /// Whatever order the batches commit in, the newest one must carry every member that had joined: the
        /// member snapshot and the epoch are taken together, so a later epoch never holds an older list.
        @Test
        void rebalance_highestGenerationBatchSeesEveryMember_underConcurrentJoins() throws InterruptedException {
            for (var round = 0; round < ROUNDS; round++) {
                held.clear();
                var fresh = newCoordinator(1L);

                joinConcurrently(fresh);

                assertThat(consumersOfNewestBatch()).as("round %d", round).hasSize(CONSUMERS);
            }
        }

        private void joinConcurrently(ConsumerGroupCoordinator target) throws InterruptedException {
            var ready = new AtomicInteger();
            var done = new CountDownLatch(CONSUMERS);

            for (var i = 0; i < CONSUMERS; i++) {
                var consumerId = "c" + i;

                Thread.ofPlatform().start(() -> joinAfterBarrier(target, consumerId, ready, done));
            }

            done.await();
        }

        private void joinAfterBarrier(ConsumerGroupCoordinator target,
                                      String consumerId,
                                      AtomicInteger ready,
                                      CountDownLatch done) {
            ready.incrementAndGet();
            while (ready.get() < CONSUMERS) {
                Thread.onSpinWait();
            }
            target.joinGroup(GROUP, STREAM, CONSUMERS, consumerId, NODE_A);
            done.countDown();
        }

        private List<String> consumersOfNewestBatch() {
            return held.stream()
                       .max(Comparator.comparing(ConsumerGroupRebalanceOrderTest::epochOf))
                       .orElseThrow()
                       .stream()
                       .map(ConsumerGroupRebalanceOrderTest::consumerOf)
                       .distinct()
                       .toList();
        }
    }

    private static Epoch epochOf(List<KVCommand<AetherKey>> batch) {
        return ((ConsumerGroupValue) ((KVCommand.Put<AetherKey, AetherValue>) batch.getFirst()).value()).epoch();
    }

    private static String consumerOf(KVCommand<AetherKey> command) {
        return ((ConsumerGroupValue) ((KVCommand.Put<AetherKey, AetherValue>) command).value()).consumerId();
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
