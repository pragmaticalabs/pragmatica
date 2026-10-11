// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #2087: a stream entry is not added until its configured acknowledgements arrive, and NO read path serves an
/// offset above the acknowledged high-water. Every consumer read ends in one ring bound (the ring's visible position),
/// so these tests pin that bound at the node that serves it: the owner (local read, the read an owner serves a
/// forwarded consumer, the bounds a cursor is built from) and a replica (which learns the position from the owner).
class ReadCommittedStreamTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final NodeId PEER2 = NodeId.randomNodeId();
    private static final Epoch EPOCH_1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch EPOCH_2 = Epoch.epoch(1L, 2L, 1L);

    private StreamPartitionManager manager;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    /// The owner with confirmation factor 3 (two peer acknowledgements): records the owner holds but has not seen
    /// acknowledged are invisible through every read the owner serves.
    @Nested
    class Owner {

        @Test
        void unacknowledgedRecords_areServedByNoOwnerReadPath() {
            var registry = registryWithPeers();
            var replication = replicationManager(SELF, registry);

            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 3, 3);
            var first = publish(manager, "e0");
            publish(manager, "e1");
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, first));

            assertThat(readLocal(manager)).as("local read: one of two required acks is not an acknowledgement").isEmpty();
            assertThat(readServing(manager)).as("the read an owner serves a forwarded consumer").isEmpty();
            assertThat(manager.visibleBounds(STREAM, PARTITION).map(VisibleBounds::isEmpty).or(false))
                .as("the bounds a cursor or a replay is built from")
                .isTrue();

            replication.handleAck(replicateAck(PEER2, STREAM, PARTITION, first));

            assertThat(readLocal(manager)).as("both acks cover offset 0, and only it").containsExactly("e0");
            assertThat(readServing(manager)).containsExactly("e0");
            assertThat(manager.visibleBounds(STREAM, PARTITION).map(VisibleBounds::visibleHead).or(-2L)).isEqualTo(first);
        }
    }

    /// A replica holds records the owner has not seen acknowledged; it serves only what the owner reported visible.
    @Nested
    class Replica {

        @Test
        void heldRecords_areServedOnlyUpToTheOwnersReportedPosition() {
            manager = replicaWithRecords(3);

            assertThat(readLocal(manager)).as("held, but nothing reported visible by the owner").isEmpty();
            assertThat(readServing(manager)).as("the read a replica serves a forwarded consumer").isEmpty();
            assertThat(manager.visibleBounds(STREAM, PARTITION).map(VisibleBounds::isEmpty).or(false)).isTrue();

            manager.commitAdvanced(STREAM, PARTITION, 1L, EPOCH_1);

            assertThat(readLocal(manager)).containsExactly("r0", "r1");
            assertThat(readServing(manager)).containsExactly("r0", "r1");
            assertThat(manager.visibleBounds(STREAM, PARTITION).map(VisibleBounds::visibleHead).or(-2L)).isEqualTo(1L);

            manager.commitAdvanced(STREAM, PARTITION, 2L, EPOCH_1);

            assertThat(readLocal(manager)).containsExactly("r0", "r1", "r2");
        }

        @Test
        void reportedPosition_neverExposesMoreThanTheReplicaHolds() {
            manager = replicaWithRecords(2);

            manager.commitAdvanced(STREAM, PARTITION, 9L, EPOCH_1);

            assertThat(readLocal(manager)).containsExactly("r0", "r1");
            assertThat(manager.visibleBounds(STREAM, PARTITION).map(VisibleBounds::visibleHead).or(-2L)).isEqualTo(1L);
        }

        @Test
        void recordLandingAfterTheReport_isHeldBackUntilTheOwnerReportsIt() {
            manager = replicaWithRecords(1);
            manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);

            manager.appendRecovered(STREAM, PARTITION, "r1".getBytes(UTF_8), 2L).onFailure(cause -> fail(cause.message()));

            assertThat(readLocal(manager)).as("r1 is durable here and not reported").containsExactly("r0");

            manager.commitAdvanced(STREAM, PARTITION, 1L, EPOCH_1);

            assertThat(readLocal(manager)).containsExactly("r0", "r1");
        }

        /// The epoch rule: a report under a newer owner epoch REPLACES the recorded position (even with a lower value),
        /// a report under an older epoch is ignored, and visibility is never lowered by either.
        @Test
        void epochChange_replacesThePosition_olderEpochIsIgnored_visibleNeverLowers() {
            manager = replicaWithRecords(3);
            manager.commitAdvanced(STREAM, PARTITION, 1L, EPOCH_1);
            manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_2);
            manager.appendRecovered(STREAM, PARTITION, "r3".getBytes(UTF_8), 4L).onFailure(cause -> fail(cause.message()));

            assertThat(readLocal(manager)).as("newer epoch reported 0: nothing new is exposed, nothing is taken back")
                                          .containsExactly("r0", "r1");

            manager.commitAdvanced(STREAM, PARTITION, 3L, EPOCH_1);

            assertThat(readLocal(manager)).as("the old owner's report no longer counts").containsExactly("r0", "r1");

            manager.commitAdvanced(STREAM, PARTITION, 3L, EPOCH_2);

            assertThat(readLocal(manager)).containsExactly("r0", "r1", "r2", "r3");
        }

        /// A repeat or a delayed message under the SAME epoch with a lower value must not pull the recorded position down: the
        /// next record to land would otherwise be held back although the owner has already reported it visible.
        @Test
        void sameEpochLowerReport_isIgnored() {
            manager = replicaWithRecords(1);
            manager.commitAdvanced(STREAM, PARTITION, 1L, EPOCH_1);
            manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);

            manager.appendRecovered(STREAM, PARTITION, "r1".getBytes(UTF_8), 2L).onFailure(cause -> fail(cause.message()));

            assertThat(readLocal(manager)).as("r1 was already reported visible under this epoch").containsExactly("r0", "r1");
        }

        /// A node that is the owner takes no position from anyone: it is the authority.
        @Test
        void ownerIgnoresAReportedPosition() {
            var replication = replicationManager(SELF, registryWithPeers());

            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 3, 3);
            publish(manager, "e0");

            manager.commitAdvanced(STREAM, PARTITION, 5L, EPOCH_1);

            assertThat(readLocal(manager)).as("the owner's own acknowledgements decide, not a position reported to it").isEmpty();
        }
    }

    /// The owner tells its replicas; a lost announcement costs one tick, never a wrong answer.
    @Nested
    class Announcement {

        @Test
        void ownerAnnouncesItsVisiblePosition_notOnThePublishersThread_andRepeatsOnTheTick() throws Exception {
            var registry = registryWithPeers();
            var sent = new CopyOnWriteArrayList<ReplicationMessage.CommitAdvance>();
            var senders = new CopyOnWriteArrayList<Thread>();
            var replication = replicationManager(SELF, registry, (target, message) -> record(sent, senders, target, message));

            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 3, 3);
            var offset = publish(manager, "e0");

            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));
            replication.handleAck(replicateAck(PEER2, STREAM, PARTITION, offset));
            awaitAnnounced(sent, offset);

            assertThat(sent).as("only the acknowledged position is ever announced")
                            .allSatisfy(message -> assertThat(message.committedThrough()).isEqualTo(offset));
            assertThat(senders).as("announced from the ring's notifier, never from a publisher or an ack handler")
                               .doesNotContain(Thread.currentThread());

            sent.clear();
            manager.repeatVisible();

            assertThat(sent).as("the tick repeats the position to both replicas").hasSize(2);
            assertThat(sent).allSatisfy(message -> assertThat(message.committedThrough()).isEqualTo(offset));
        }

        @Test
        void aLostAnnouncement_isRepairedByTheRepeat_andNothingIsExposedBeforeIt() throws Exception {
            var registry = registryWithPeers();
            var sent = new CopyOnWriteArrayList<ReplicationMessage.CommitAdvance>();
            var replication = replicationManager(SELF, registry, (target, message) -> record(sent, new CopyOnWriteArrayList<>(), target, message));
            var ownerSide = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);

            try (var _ = new AutoCloseableManager(ownerSide)) {
                createStream(ownerSide, 3, 3);
                manager = replicaWithRecords(1);
                var offset = publish(ownerSide, "r0");

                replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));
                replication.handleAck(replicateAck(PEER2, STREAM, PARTITION, offset));
                awaitAnnounced(sent, offset);

                // The announcement is dropped on the way: the replica holds r0 and has been told nothing.
                assertThat(readLocal(manager)).isEmpty();

                sent.clear();
                ownerSide.repeatVisible();
                sent.stream()
                    .findFirst()
                    .ifPresent(message -> manager.commitAdvanced(STREAM, PARTITION, message.committedThrough(), message.ownerEpoch()));

                assertThat(readLocal(manager)).containsExactly("r0");
            }
        }
    }

    /// A node that is no longer a replica but is still landing a catch-up (a promoted one) follows the owner rule: what
    /// it lands is not visible until its own confirmation peers acknowledge it.
    @Nested
    class Promoted {

        @Test
        void catchUpLandedOnANewOwner_isVisibleOnlyOnceItsPeersAcknowledgeIt() {
            var replication = replicationManager(SELF, registryWithPeers());

            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 3, 3);
            manager.appendRecovered(STREAM, PARTITION, "c0".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));

            assertThat(readLocal(manager)).as("landed here, acknowledged by nobody").isEmpty();

            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));
            replication.handleAck(replicateAck(PEER2, STREAM, PARTITION, 0L));

            assertThat(readLocal(manager)).containsExactly("c0");
        }
    }

    @Test
    void markVerifiedOnANodeThatIsNoLongerAReplica_doesNotOutrunThePeerAcknowledgements() {
        var replication = replicationManager(SELF, registryWithPeers());

        manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
        createStream(manager, 3, 3);
        manager.appendRecovered(STREAM, PARTITION, "c0".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
        manager.appendRecovered(STREAM, PARTITION, "c1".getBytes(UTF_8), 2L).onFailure(cause -> fail(cause.message()));

        manager.markVerified(STREAM, PARTITION, 1L);

        assertThat(readLocal(manager)).as("compared with the owner is not acknowledged by the peers").isEmpty();
    }

    @Nested
    class Silence {

        @Test
        void aReplicaAnnouncesNothing_andAnOwnerWithNothingVisibleRepeatsNothing() throws Exception {
            var sent = new CopyOnWriteArrayList<ReplicationMessage.CommitAdvance>();
            var senders = new CopyOnWriteArrayList<Thread>();
            var replication = replicationManager(SELF, registryWithPeers(), (target, message) -> record(sent, senders, target, message));

            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
            createStream(manager, 3, 3);
            manager.appendRecovered(STREAM, PARTITION, "r0".getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
            manager.commitAdvanced(STREAM, PARTITION, 0L, EPOCH_1);
            manager.repeatVisible();
            Thread.sleep(200);

            assertThat(readLocal(manager)).as("control: the position did advance on this replica").containsExactly("r0");
            assertThat(sent).as("a replica is a follower: it announces nothing").isEmpty();

            manager.close();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 3, 3);
            publish(manager, "e0");
            manager.repeatVisible();
            Thread.sleep(200);

            assertThat(sent).as("an owner with nothing visible has nothing to repeat").isEmpty();
        }
    }

    private static void record(List<ReplicationMessage.CommitAdvance> sent,
                               List<Thread> senders,
                               NodeId target,
                               ReplicationMessage message) {
        if (message instanceof ReplicationMessage.CommitAdvance advance) {
            sent.add(advance);
            senders.add(Thread.currentThread());
        }
    }

    private static void awaitAnnounced(List<ReplicationMessage.CommitAdvance> sent, long offset) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (sent.stream().noneMatch(message -> message.committedThrough() == offset) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(sent).as("the owner announced the acknowledged position").isNotEmpty();
    }

    private record AutoCloseableManager(StreamPartitionManager manager) implements AutoCloseable {
        @Override
        public void close() {
            manager.close();
        }
    }

    private static StreamPartitionManager replicaWithRecords(int count) {
        var replica = streamPartitionManager(Long.MAX_VALUE);

        replica.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        createStream(replica, 3, 2);
        for (var i = 0; i < count; i++) {
            replica.appendRecovered(STREAM, PARTITION, ("r" + i).getBytes(UTF_8), 1L + i)
                   .onFailure(cause -> fail("replica append failed: " + cause.message()));
        }
        return replica;
    }

    private static ReplicaRegistry registryWithPeers() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        registry.registerReplica(STREAM, PARTITION, PEER2);
        return registry;
    }

    private static void createStream(StreamPartitionManager target, int replicas, int minSyncReplicas) {
        target.createStream(StreamConfig.streamConfig(STREAM,
                                                      1,
                                                      RetentionPolicy.retentionPolicy(),
                                                      "earliest",
                                                      1_048_576L,
                                                      ConsistencyMode.EVENTUAL,
                                                      replicas,
                                                      minSyncReplicas,
                                                      StreamCompression.NONE,
                                                      Option.none()))
              .onFailure(cause -> fail(cause.message()));
    }

    private static long publish(StreamPartitionManager target, String payload) {
        return target.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
                     .onFailure(cause -> fail("publish failed: " + cause.message()))
                     .or(-1L);
    }

    private static List<String> readLocal(StreamPartitionManager target) {
        return target.readLocal(STREAM, PARTITION, 0L, 100)
                     .map(events -> events.stream().map(event -> new String(event.data(), UTF_8)).toList())
                     .onFailure(cause -> fail("read failed: " + cause.message()))
                     .or(List.of());
    }

    private static List<String> readServing(StreamPartitionManager target) {
        return target.readServing(STREAM, PARTITION, 0L, 100)
                     .map(events -> events.stream().map(event -> new String(event.data(), UTF_8)).toList())
                     .onFailure(cause -> fail("serving read failed: " + cause.message()))
                     .or(List.of());
    }

    private static long visibleOffset(StreamPartitionManager target) {
        return target.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::visibleOffset).or(Long.MIN_VALUE);
    }
}
