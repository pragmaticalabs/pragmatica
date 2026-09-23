// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #1235: consumers see a partition only up to its VISIBLE position — durable on the owner (WAL fsync)
/// AND acknowledged by `minSyncReplicas - 1` distinct peers. A push listener fires when that position
/// advances, never on the bare append. Before the fix the ring notified listeners inside `append` and
/// every read served the raw head, so a consumer could act on an event that owner failover then erased.
class StreamPartitionVisibilityTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RECORDS = 5;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();

    @TempDir
    Path walDir;

    private StreamPartitionManager manager;

    @AfterEach
    void closeManager() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
    }

    /// minSync = 2 with one peer: the publish is durable on the owner at once (no WAL), but it is not
    /// visible until the peer acknowledges it.
    @Nested
    class MinSyncTwo {

        /// The ticket's acceptance test.
        @Test
        void publish_staysInvisibleAndSilent_whilePeerAckIsPending_thenBecomesVisibleOnce() {
            var replication = replicationWithPeer();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 2, 2);
            var notifications = listen(manager);

            var offset = publish(manager, "e0");
            var pending = manager.awaitReplication(STREAM, PARTITION, offset, 1);

            assertThat(pending.isResolved()).as("the publish is waiting for the peer").isFalse();
            assertThat(visibleOffset(manager)).as("nothing became visible, so nothing was queued for the notifier")
                                              .isEqualTo(-1L);
            assertThat(settledNotifications(notifications)).as("no push notification for an unacknowledged event").isZero();
            assertThat(readAll(manager)).as("read(0) must not expose the unacknowledged event").isEmpty();

            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));

            assertThat(pending.await().isSuccess()).as("the peer ack resolves the publish").isTrue();
            assertThat(readAll(manager)).as("acknowledged ⇒ visible").containsExactly("e0");
            assertThat(awaitNotifications(notifications, 1)).as("the listener fires once, when the event becomes visible")
                                                         .isEqualTo(1);
        }

        /// Read-your-writes for an acknowledged publish: whatever continues the publish promise already
        /// sees the event. The ack must advance visibility BEFORE it resolves the pending await.
        @Test
        void ackedPublish_isVisibleToTheContinuationOfItsOwnAwait() {
            var replication = replicationWithPeer();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 2, 2);

            var offset = publish(manager, "e0");
            var seenByContinuation = manager.awaitReplication(STREAM, PARTITION, offset, 1)
                                            .map(_ -> readAll(manager));

            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));

            assertThat(seenByContinuation.await().or(List.of())).containsExactly("e0");
        }

        /// rev1309 F1: two peers, minSync = 3 (minAcks = 2), two CONCURRENT acks. The interleaving: both
        /// pre-update observer calls run first, each overlaying only its own ack (1 < 2, no advance); both
        /// registry updates land; the publisher's await then resolves from the registry SNAPSHOT before
        /// either post-update observer call runs. The state is forced directly (two registry rows written,
        /// no observer call), which is exactly what that interleaving leaves behind; the await must still
        /// hand its continuation a visible event. `[unverified: not reproduced by racing real threads]`
        @Test
        void twoConcurrentAcks_awaitResolvedFromSnapshot_continuationSeesTheEvent() {
            var peer2 = NodeId.randomNodeId();
            ReplicaRegistry registry = replicaRegistry();

            registry.registerReplica(STREAM, PARTITION, SELF);
            registry.registerReplica(STREAM, PARTITION, PEER);
            registry.registerReplica(STREAM, PARTITION, peer2);
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replicationManager(SELF, registry));
            createStream(manager, 3, 3);
            var offset = publish(manager, "e0");

            registry.updateWatermark(STREAM, PARTITION, PEER, offset);
            registry.updateWatermark(STREAM, PARTITION, peer2, offset);

            var seen = manager.awaitReplication(STREAM, PARTITION, offset, 2)
                              .map(_ -> readAll(manager))
                              .await();

            assertThat(seen.isSuccess()).as("the await resolved from the registry snapshot").isTrue();
            assertThat(seen.or(List.of())).as("publish acknowledged => continuation reads its own write").containsExactly("e0");
        }

        /// A peer that acknowledges only the first of two events makes exactly that prefix visible.
        @Test
        void ack_exposesOnlyTheAcknowledgedPrefix() {
            var replication = replicationWithPeer();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 2, 2);

            var first = publish(manager, "e0");
            publish(manager, "e1");
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, first));

            assertThat(readAll(manager)).containsExactly("e0");
        }

        /// Replication reads (replica catch-up, survivor pulls) must see the pending event: a lagging peer
        /// that could fetch only visible events could never supply the ack that makes them visible.
        @Test
        void pendingEvent_isServedToReplicationReads() {
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replicationWithPeer());
            createStream(manager, 2, 2);

            publish(manager, "e0");

            assertThat(readAll(manager)).isEmpty();
            assertThat(manager.readAppended(STREAM, PARTITION, 0L, 100).or(List.of())).hasSize(1);
        }
    }

    /// Replica side: a replicated record is visible to reads served by this node once its own WAL write
    /// is durable — at once without a WAL, never after a failed WAL write.
    @Nested
    class Replica {

        @Test
        void appendRecovered_withoutWal_isVisibleAtOnce() {
            manager = streamPartitionManager(Long.MAX_VALUE);
            createStream(manager, 2, 2);
            var notifications = listen(manager);

            manager.appendRecovered(STREAM, PARTITION, "r0".getBytes(UTF_8), 1L);

            assertThat(readAll(manager)).containsExactly("r0");
            assertThat(awaitNotifications(notifications, 1)).isEqualTo(1);
        }

        @Test
        void appendRecovered_withWal_isVisibleOnceItsWalWriteIsDurable() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 2, 2);

            manager.appendRecovered(STREAM, PARTITION, "r0".getBytes(UTF_8), 1L);

            assertThat(manager.syncReplicated(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(readAll(manager)).as("visible once the replica's WAL write is durable — by the time the barrier resolves")
                                        .containsExactly("r0");
        }

        /// #1235 × #1244: a WAL-backed batch requests ONE group commit, at the barrier — never one per
        /// record. The request count is the pin because coalescing under the WAL's sync lock hides
        /// per-record requests behind an fsync count that is "however many ran between writes" (1..N).
        @Test
        void appendRecovered_withWal_requestsNoCommitPerRecord_theBarrierRequestsOne() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 2, 2);
            var wal = walOf(manager);

            appendRecovered(manager, RECORDS);

            assertThat(wal.commitRequests()).as("no record requested its own commit").isZero();
            assertThat(visibleOffset(manager)).as("nothing is visible before the barrier").isEqualTo(-1L);
            assertThat(manager.syncReplicated(STREAM, PARTITION).await().isSuccess()).isTrue();
            assertThat(wal.commitRequests()).as("the barrier requested exactly one commit for %d records", RECORDS)
                                            .isEqualTo(1L);
            assertThat(visibleOffset(manager)).isEqualTo(RECORDS - 1);
            assertThat(readAll(manager)).hasSize(RECORDS);
        }

        /// #1235: the replica's visible offset moves at the barrier's fsync and not before it — while the
        /// barrier's `force` is parked, every record is appended, WAL-written and still invisible; the
        /// moment the barrier resolves they are visible, with no completion handler left to wait for.
        @Test
        void appendRecovered_withWal_visibleOffsetAdvancesOnlyWhenTheBarrierResolves() throws Exception {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 2, 2);
            var gate = GatedWalFsync.inject(walOf(manager));

            appendRecovered(manager, RECORDS);
            var barrier = manager.syncReplicated(STREAM, PARTITION);

            assertThat(gate.forceEntered.await(10, TimeUnit.SECONDS)).as("the barrier reached the fsync").isTrue();
            assertThat(visibleOffset(manager)).as("nothing is visible while the barrier's fsync is parked").isEqualTo(-1L);
            gate.forceProceed.countDown();
            assertThat(barrier.await().isSuccess()).isTrue();
            assertThat(visibleOffset(manager)).as("visible as soon as the barrier resolved").isEqualTo(RECORDS - 1);
        }

        @Test
        void appendRecovered_failedWalWrite_neverBecomesVisible() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 2, 2);
            var notifications = listen(manager);
            var channel = FailingChannel.inject(walOf(manager));

            channel.failWrites = true;
            manager.appendRecovered(STREAM, PARTITION, "lost".getBytes(UTF_8), 1L);

            assertThat(manager.syncReplicated(STREAM, PARTITION).await().isFailure()).as("the chain is poisoned")
                                                                                   .isTrue();
            assertThat(readAll(manager)).isEmpty();
            assertThat(visibleOffset(manager)).isEqualTo(-1L);
            assertThat(settledNotifications(notifications)).isZero();
        }
    }

    /// An owner WAL failure AFTER the ring append (ruling 801a8b54e routes it here): the append is in the
    /// ring, the publish failed, and the event must never be offered to a consumer.
    @Nested
    class OwnerWalFailure {

        @Test
        void failedFrameWrite_eventNeverBecomesVisible() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 1, 0);
            var notifications = listen(manager);
            var channel = FailingChannel.inject(walOf(manager));

            channel.failWrites = true;
            publishExpectingFailure(manager, "lost");
            channel.failWrites = false;
            publishExpectingFailure(manager, "after-fail-stop");

            assertThat(readAll(manager)).as("an event whose WAL frame never landed is never readable").isEmpty();
            assertThat(visibleOffset(manager)).as("nothing queued for the notifier").isEqualTo(-1L);
            assertThat(settledNotifications(notifications)).as("and never announced").isZero();
        }

        @Test
        void failedFsync_eventNeverBecomesVisible() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 1, 0);
            var notifications = listen(manager);
            var channel = FailingChannel.inject(walOf(manager));

            channel.failForce = true;
            publishExpectingFailure(manager, "lost");

            assertThat(readAll(manager)).as("an event whose fsync failed is never readable").isEmpty();
            assertThat(visibleOffset(manager)).as("nothing queued for the notifier").isEqualTo(-1L);
            assertThat(settledNotifications(notifications)).as("and never announced").isZero();
        }

        /// The #1258 case: the event is replicated inside the ordered section, then the OWNER's fsync
        /// fails. A peer ack arriving afterwards must not make it visible — the owner never durably held it.
        @Test
        void failedFsyncAfterReplication_peerAckDoesNotExposeIt() {
            var replication = replicationWithPeer();
            manager = replicatingWalManager(replication, walDir);
            createStream(manager, 2, 2);
            var notifications = listen(manager);
            var channel = FailingChannel.inject(walOf(manager));

            channel.failForce = true;
            publishExpectingFailure(manager, "lost");
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L));

            assertThat(readAll(manager)).isEmpty();
            assertThat(visibleOffset(manager)).isEqualTo(-1L);
            assertThat(settledNotifications(notifications)).isZero();
        }
    }

    /// The lost-advance race the post-update observer call closes (CTO ruling on #1309). Each path writes
    /// its own input and then reads the other's: the owner writes `durable` and reads the registry, and the
    /// ack path's pre-update call reads `durable` before the registry records the ack. Interleaved as
    /// below, neither read sees both inputs, and without the second call the event would stay invisible
    /// until an unrelated later advance.
    @Nested
    class AckRacingOwnerFsync {

        @Test
        void ackReadBeforeDurable_fsyncReadBeforeRegistry_eventStillBecomesVisible() throws InterruptedException {
            var gated = new GatedAckReplication(replicationWithPeer());
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, gated);
            createStream(manager, 2, 2);

            var acker = Thread.ofVirtual().start(() -> gated.handleAck(replicateAck(PEER, STREAM, PARTITION, 0L)));

            assertThat(gated.preUpdateCallDone.await(5, TimeUnit.SECONDS)).as("ack path read durable = -1 and parked")
                                                                           .isTrue();
            publish(manager, "e0");
            assertThat(readAll(manager)).as("the owner read a registry the ack has not reached").isEmpty();

            gated.release.countDown();
            acker.join(5_000);

            assertThat(readAll(manager)).as("the post-update observer call sees both inputs").containsExactly("e0");
        }
    }

    /// CTO lock rule (#1235, #1258 R2-1): a visible advance only queues for the ring's serial notifier.
    /// Neither the publisher that saw its fsync nor the thread delivering a replica ack runs a listener.
    @Nested
    class ListenerThread {

        @Test
        void publishLocal_neverRunsTheListenerOnThePublishingThread() {
            manager = streamPartitionManager(Long.MAX_VALUE);
            createStream(manager, 1, 1);
            var listenerThreads = listenThreads(manager);

            publish(manager, "e0");

            assertThat(awaitThreads(listenerThreads, 1)).as("the publisher thread executes no listener")
                                                         .hasSize(1)
                                                         .doesNotContain(Thread.currentThread());
        }

        @Test
        void replicaAck_neverRunsTheListenerOnTheAckingThread() {
            var replication = replicationWithPeer();
            manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);
            createStream(manager, 2, 2);
            var listenerThreads = listenThreads(manager);
            var offset = publish(manager, "e0");

            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, offset));

            assertThat(awaitThreads(listenerThreads, 1)).as("the ack-handling thread executes no listener")
                                                         .hasSize(1)
                                                         .doesNotContain(Thread.currentThread());
        }
    }

    /// Owner-only durability (minSync <= 1) with no WAL: visible aliases appended. This is the control
    /// that shows a visible event IS read and announced by the same instruments.
    @Nested
    class OwnerOnly {

        @Test
        void publish_isVisibleAndAnnouncedOnce_whenPublishReturns() {
            manager = streamPartitionManager(Long.MAX_VALUE);
            createStream(manager, 1, 1);
            var notifications = listen(manager);

            publish(manager, "e0");

            assertThat(readAll(manager)).containsExactly("e0");
            assertThat(awaitNotifications(notifications, 1)).isEqualTo(1);
        }

        @Test
        void walPublish_isVisibleWhenPublishReturns() {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            createStream(manager, 1, 0);

            publish(manager, "e0");

            assertThat(readAll(manager)).containsExactly("e0");
        }
    }

    /// #1387: `visible = min(durable, acked)` must survive a restart. Nothing about acks is persisted on
    /// the owner (the production `ReplicaRegistry` writes through `WatermarkStore.NOOP`), so the honest
    /// post-restart position is the sealed bound recovery seeds from: the replayed WAL tail is durable but
    /// NOT visible until a peer ack covers it — exactly its state before the restart. Before the fix
    /// recovery placed the tail with the plain `append`, which is visible at once. A "restart" is a second
    /// manager on the same `walDir` with a fresh (blind) replica registry.
    @Nested
    class Restart {

        /// The ticket's P5 scenario: minSync 2, offsets 0..4 published, 0..2 sealed, the peer acked through
        /// 2. After the restart `visible` is 2 and a read above it sees nothing until the peer re-acks.
        @Test
        void walReplay_leavesUnacknowledgedTailInvisible_untilThePeerAcksIt() {
            var replication = replicationWithPeer();
            manager = replicatingWalManager(replication, walDir);
            createStream(manager, 2, 2);
            publishMany(manager, 5);
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 2L));
            assertThat(visibleOffset(manager)).as("before the restart").isEqualTo(2L);
            manager.close();

            var restarted = replicationWithPeer();
            manager = replicatingWalManager(restarted, walDir, sealedUpTo(2L));
            createStream(manager, 2, 2);

            assertThat(durableOffset(manager)).as("the replayed tail is durable").isEqualTo(4L);
            assertThat(visibleOffset(manager)).as("after the restart: no ack is persisted, so visible is the sealed bound")
                                              .isEqualTo(2L);
            assertThat(readFrom(manager, 3)).as("read(3) must not expose the unacknowledged tail").isEmpty();

            restarted.handleAck(replicateAck(PEER, STREAM, PARTITION, 4L));

            assertThat(readFrom(manager, 3)).as("re-acknowledged ⇒ visible").containsExactly("e3", "e4");
        }

        /// Nothing sealed and nothing persisted about acks: the whole replayed log is invisible after the
        /// restart, and only what the peer confirms again becomes visible.
        @Test
        void walReplay_withNothingSealed_exposesNothing_untilThePeerAcks() {
            var replication = replicationWithPeer();
            manager = replicatingWalManager(replication, walDir);
            createStream(manager, 2, 2);
            publishMany(manager, 3);
            replication.handleAck(replicateAck(PEER, STREAM, PARTITION, 1L));
            assertThat(readAll(manager)).as("before the restart").containsExactly("e0", "e1");
            manager.close();

            var restarted = replicationWithPeer();
            manager = replicatingWalManager(restarted, walDir);
            createStream(manager, 2, 2);

            assertThat(visibleOffset(manager)).isEqualTo(-1L);
            assertThat(readAll(manager)).as("read(0) after the restart").isEmpty();

            restarted.handleAck(replicateAck(PEER, STREAM, PARTITION, 1L));

            assertThat(readAll(manager)).as("what the peer confirms again").containsExactly("e0", "e1");
        }

        /// Owner-only control: with no peer barrier `visible = durable`, so the replayed tail is visible
        /// as soon as the partition is rebuilt — the pre-#1387 behaviour, unchanged.
        @Test
        void walReplay_ownerOnly_isVisibleAtOnce() {
            manager = replicatingWalManager(ReplicationManager.NONE, walDir);
            createStream(manager, 1, 1);
            publishMany(manager, 3);
            manager.close();

            manager = replicatingWalManager(ReplicationManager.NONE, walDir);
            createStream(manager, 1, 1);

            assertThat(readAll(manager)).containsExactly("e0", "e1", "e2");
        }

        /// The committed-config hydration path (`onStreamConfigPut`) rebuilds the ring the same way a create
        /// does, so it restores visibility the same way — with no peer barrier, the whole tail.
        @Test
        void walReplay_onHydration_isVisibleAtOnce() {
            manager = replicatingWalManager(ReplicationManager.NONE, walDir);
            createStream(manager, 1, 1);
            publishMany(manager, 3);
            manager.close();

            manager = replicatingWalManager(ReplicationManager.NONE, walDir);
            manager.onStreamConfigPut(streamConfigPut(streamConfig(1, 1)));

            assertThat(readAll(manager)).containsExactly("e0", "e1", "e2");
        }

        /// The lazy per-partition path (a `NONE` role resolving to OWNER after a metadata-only hydration,
        /// then the reconcile hook) rebuilds the ring the same way too.
        @Test
        void walReplay_onLazyMaterialize_isVisibleAtOnce() {
            manager = replicatingWalManager(ReplicationManager.NONE, walDir);
            createStream(manager, 1, 1);
            publishMany(manager, 3);
            manager.close();

            var role = new AtomicReference<>(ReplicaSetController.Role.NONE);
            manager = replicatingWalManager(ReplicationManager.NONE, walDir);
            manager.placementRoleSupplier((_, _) -> role.get());
            manager.onStreamConfigPut(streamConfigPut(streamConfig(1, 1)));
            assertThat(manager.partitionBuffer(STREAM, PARTITION).isPresent()).as("metadata-only until the role resolves")
                                                                              .isFalse();
            role.set(ReplicaSetController.Role.OWNER);
            manager.materializePartition(STREAM, PARTITION).onFailure(cause -> fail(cause.message()));

            assertThat(readAll(manager)).containsExactly("e0", "e1", "e2");
        }

        /// Replica control: a replica's visible position is its OWN durability (#1235 replica side), never
        /// the owner's acks — a blind registry must not hold its replayed tail back.
        @Test
        void walReplay_onAReplica_isVisibleAtOnce() {
            manager = replicatingWalManager(replicationWithPeer(), walDir);
            manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
            createStream(manager, 2, 2);
            appendRecovered(manager, 3);
            manager.syncReplicated(STREAM, PARTITION).await().onFailure(cause -> fail(cause.message()));
            manager.close();

            manager = replicatingWalManager(replicationWithPeer(), walDir);
            manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
            createStream(manager, 2, 2);

            assertThat(readAll(manager)).containsExactly("r0", "r1", "r2");
        }
    }

    // === helpers ===

    private static ReplicationManager replicationWithPeer() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        return replicationManager(SELF, registry);
    }

    /// Delegates to a real replication manager, but parks the observer the partition manager installs on
    /// its FIRST call for an ack (the call before the registry update) until released. Parking there puts
    /// the ack thread exactly between its read of `durable` and its registry write.
    private static final class GatedAckReplication implements ReplicationManager {
        private final ReplicationManager delegate;
        private final CountDownLatch preUpdateCallDone = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger observerCalls = new AtomicInteger();

        private GatedAckReplication(ReplicationManager delegate) {
            this.delegate = delegate;
        }

        @Contract
        @Override
        public void observeAcks(AckObserver observer) {
            delegate.observeAcks(ack -> gate(observer, ack));
        }

        private void gate(AckObserver observer, ReplicationMessage.ReplicateAck ack) {
            observer.acked(ack);
            if (observerCalls.incrementAndGet() == 1) {
                preUpdateCallDone.countDown();
                awaitRelease();
            }
        }

        private void awaitRelease() {
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).as("test released the ack thread").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
            }
        }

        @Contract
        @Override
        public void replicateEvent(String streamName,
                                   int partition,
                                   long offset,
                                   byte[] payload,
                                   long timestamp,
                                   Epoch ownerEpoch) {
            delegate.replicateEvent(streamName, partition, offset, payload, timestamp, ownerEpoch);
        }

        @Contract
        @Override
        public void handleAck(ReplicationMessage.ReplicateAck ack) {
            delegate.handleAck(ack);
        }

        @Override
        public ReplicaRegistry registry() {
            return delegate.registry();
        }

        @Override
        public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
            return delegate.awaitReplication(streamName, partition, offset, minAcks);
        }

        @Override
        public long replicatedThrough(String streamName, int partition, int minAcks) {
            return delegate.replicatedThrough(streamName, partition, minAcks);
        }

        @Override
        public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
            return delegate.replicatedThrough(pending, minAcks);
        }
    }

    /// Replication AND a WAL, without a cluster node or an epoch fence. No public factory combines the two
    /// (production wires both through the fenced factory), so the private constructor is used directly.
    private static StreamPartitionManager replicatingWalManager(ReplicationManager replication, Path walDir) {
        return replicatingWalManager(replication, walDir, LastSealedOffsetSource.none());
    }

    /// As above, with the sealed bound recovery seeds from (#1387): a "restart" is a second manager on the
    /// same `walDir`, and the bound is what its sealed-segment index would report.
    private static StreamPartitionManager replicatingWalManager(ReplicationManager replication,
                                                                Path walDir,
                                                                LastSealedOffsetSource lastSealed) {
        try {
            var constructor = StreamPartitionManager.class.getDeclaredConstructor(long.class,
                                                                                  EvictionListener.class,
                                                                                  ReplicationManager.class,
                                                                                  Option.class,
                                                                                  Option.class,
                                                                                  StreamOwnerEpochSource.class,
                                                                                  Option.class,
                                                                                  LastSealedOffsetSource.class,
                                                                                  DurableSealedOffsetSource.class);

            constructor.setAccessible(true);
            return constructor.newInstance(Long.MAX_VALUE,
                                           EvictionListener.NOOP,
                                           replication,
                                           Option.none(),
                                           Option.none(),
                                           StreamOwnerEpochSource.zero(),
                                           Option.some(walDir),
                                           lastSealed,
                                           DurableSealedOffsetSource.same(lastSealed));
        } catch (ReflectiveOperationException e) {
            return fail("manager construction failed: " + e);
        }
    }

    private static void createStream(StreamPartitionManager manager, int replicas, int minSyncReplicas) {
        manager.createStream(streamConfig(replicas, minSyncReplicas)).onFailure(cause -> fail(cause.message()));
    }

    private static StreamConfig streamConfig(int replicas, int minSyncReplicas) {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         replicas,
                                         minSyncReplicas,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    /// The committed-config notification `AetherNode` routes to `onStreamConfigPut`.
    private static ValuePut<StreamConfigKey, StreamConfigValue> streamConfigPut(StreamConfig config) {
        var put = new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()), StreamConfigValue.streamConfigValue(config));

        return new ValuePut<>(put, Option.none());
    }

    private static AtomicInteger listen(StreamPartitionManager manager) {
        var notifications = new AtomicInteger();

        manager.partitionBuffer(STREAM, PARTITION)
               .onEmpty(() -> fail("partition not materialized"))
               .onPresent(ring -> ring.addAppendListener(_ -> notifications.incrementAndGet()));
        return notifications;
    }

    private static List<Thread> listenThreads(StreamPartitionManager manager) {
        var threads = new CopyOnWriteArrayList<Thread>();

        manager.partitionBuffer(STREAM, PARTITION)
               .onEmpty(() -> fail("partition not materialized"))
               .onPresent(ring -> ring.addAppendListener(_ -> threads.add(Thread.currentThread())));
        return threads;
    }

    private static List<Thread> awaitThreads(List<Thread> threads, int expected) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (threads.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return List.copyOf(threads);
    }

    /// A NEGATIVE listener assertion reads the count only once the ring's notifier is idle — nothing
    /// pending, none running — so a notification that was queued but not yet delivered cannot make "never
    /// announced" pass vacuously (measured: read at once, two such assertions stayed green under a probe
    /// that queued on append). The wait is for a condition, not a duration.
    private int settledNotifications(AtomicInteger notifications) {
        var ring = manager.partitionBuffer(STREAM, PARTITION).unwrap();
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (!ring.notifierIdle() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(ring.notifierIdle()).as("the ring notifier drained").isTrue();
        return notifications.get();
    }

    /// Listeners run on the ring's serial notifier (#1258 R2-1), so a positive count is awaited.
    private static int awaitNotifications(AtomicInteger notifications, int expected) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (notifications.get() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return notifications.get();
    }

    private static LastSealedOffsetSource sealedUpTo(long offset) {
        return (_, _) -> offset;
    }

    private static void publishMany(StreamPartitionManager manager, int count) {
        for (var i = 0; i < count; i++) {
            publish(manager, "e" + i);
        }
    }

    private static long durableOffset(StreamPartitionManager manager) {
        return manager.partitionBuffer(STREAM, PARTITION)
                      .map(OffHeapRingBuffer::durableOffset)
                      .or(Long.MIN_VALUE);
    }

    private static List<String> readFrom(StreamPartitionManager manager, long fromOffset) {
        return manager.readLocal(STREAM, PARTITION, fromOffset, 100)
                      .map(events -> events.stream()
                                           .map(event -> new String(event.data(), UTF_8))
                                           .toList())
                      .onFailure(cause -> fail("read failed: " + cause.message()))
                      .or(List.of());
    }

    private static long visibleOffset(StreamPartitionManager manager) {
        return manager.partitionBuffer(STREAM, PARTITION)
                      .map(OffHeapRingBuffer::visibleOffset)
                      .or(Long.MIN_VALUE);
    }

    private static long publish(StreamPartitionManager manager, String payload) {
        return manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
                      .onFailure(cause -> fail("publish failed: " + cause.message()))
                      .or(-1L);
    }

    private static void publishExpectingFailure(StreamPartitionManager manager, String payload) {
        manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), 1L)
               .onSuccess(offset -> fail("the WAL was injected to fail, yet offset " + offset + " was acked"));
    }

    private static List<String> readAll(StreamPartitionManager manager) {
        return manager.readLocal(STREAM, PARTITION, 0L, 100)
                      .map(events -> events.stream()
                                           .map(event -> new String(event.data(), UTF_8))
                                           .toList())
                      .onFailure(cause -> fail("read failed: " + cause.message()))
                      .or(List.of());
    }

    private static void appendRecovered(StreamPartitionManager manager, int count) {
        for (var i = 0; i < count; i++) {
            manager.appendRecovered(STREAM, PARTITION, ("r" + i).getBytes(UTF_8), 1L + i)
                   .onFailure(cause -> fail("replica append failed: " + cause.message()));
        }
    }

    /// The partition's WAL, reached through the manager's private stream map — the WAL's channel is the
    /// only I/O seam, and the manager exposes neither.
    @SuppressWarnings("unchecked")
    private static PartitionWal walOf(StreamPartitionManager manager) {
        try {
            var field = StreamPartitionManager.class.getDeclaredField("streams");

            field.setAccessible(true);
            var streams = (Map<String, StreamPartitionManager.StreamEntry>) field.get(manager);

            return streams.get(STREAM)
                          .materialized()
                          .get(PARTITION)
                          .wal()
                          .fold(() -> fail("no WAL configured"), wal -> wal);
        } catch (ReflectiveOperationException e) {
            return fail("stream map unreachable: " + e);
        }
    }

    /// Delegates to the real channel; `failWrites` makes positional writes throw (a failed frame write)
    /// and `failForce` makes `force` throw (a failed fsync).
    private static final class FailingChannel extends FileChannel {
        private final FileChannel delegate;
        private volatile boolean failWrites;
        private volatile boolean failForce;

        private FailingChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        static FailingChannel inject(PartitionWal wal) {
            try {
                var field = channelField();
                var wrapper = new FailingChannel((FileChannel) field.get(wal));

                field.set(wal, wrapper);
                return wrapper;
            } catch (ReflectiveOperationException e) {
                return fail("channel injection failed: " + e);
            }
        }

        private static Field channelField() throws NoSuchFieldException {
            var field = PartitionWal.class.getDeclaredField("channel");

            field.setAccessible(true);
            return field;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            if (failForce) {
                throw new IOException("injected fsync failure");
            }
            delegate.force(metaData);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            if (failWrites) {
                throw new IOException("injected write failure");
            }
            return delegate.write(src, position);
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            return delegate.position(newPosition);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
