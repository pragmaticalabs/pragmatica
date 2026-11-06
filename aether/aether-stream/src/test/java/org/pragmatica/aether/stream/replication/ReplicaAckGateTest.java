// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;
import static org.pragmatica.aether.stream.replication.ReplicationReceiveHandler.replicationReceiveHandler;

/// #2599: a replica's ack gate (B7, #1730 phase 2) and the acks it holds back, over the REAL gate:
/// `StreamPartitionManager::replicaVerified`, whose trust for a committed epoch is fixed at its first reading from whether
/// the copy's head is still below the epoch's start, and the production listener wiring
/// (`onEpochVerified` → `releaseDeferredAck`). Real: the replica's [StreamPartitionManager] (append, head, sync, gate), the
/// [ReplicationReceiveHandler], and, in the window test, [PartitionBackfill]. Stubbed: the network, the committed ownership
/// record (one owner, the epoch the test sets), and, where a test orders an ack against a release, the durability barrier,
/// which then resolves when the test says.
class ReplicaAckGateTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);
    private static final Epoch E3 = Epoch.epoch(1L, 1L, 3L);
    private static final long E2_START = 3L;
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final List<NodeId> MEMBERS = List.of(NODE_A, NODE_B);

    private final NodeId owner = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
    private final NodeId self = owner.equals(NODE_A) ? NODE_B : NODE_A;
    private final AtomicReference<Epoch> committed = new AtomicReference<>(E1);
    private final ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> acks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger gapRepairs = new AtomicInteger();
    private final AtomicBoolean holdSyncs = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Promise<Unit>> heldSyncs = new ConcurrentLinkedQueue<>();
    /// The next-expected offset the handler reads, lowered by a test to stand for a repair that cut the tail.
    private final AtomicLong cutNext = new AtomicLong(Long.MAX_VALUE);
    private final ConcurrentLinkedQueue<OperatorWarning> warnings = new ConcurrentLinkedQueue<>();

    private StreamPartitionManager replica;
    private ReplicationReceiveHandler handler;

    @BeforeEach
    void setUp() {
        replica = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        replica.createStream(StreamConfig.streamConfig(STREAM));
        replica.ownerEpochSource((_, _) -> committed.get());
        replica.placementRoleSupplier((_, _) -> Role.REPLICA);
        replica.epochStarts((_, _, epoch) -> epoch.equals(E2)
                                             ? Option.some(E2_START)
                                             : Option.none());
        handler = replicationReceiveHandler(self,
                                            replica::appendRecovered,
                                            (stream, partition) -> Math.min(replica.nextExpectedOffset(stream, partition),
                                                                            cutNext.get()),
                                            AcceptingReplicationTransport.accepting((_, message) -> acks.add((ReplicationMessage.ReplicateAck) message)),
                                            (_, _) -> gapRepairs.incrementAndGet(),
                                            this::sync,
                                            committedOwners(),
                                            OperatorWarningSink.handingOffTo(warnings::add));
        // The production wiring (StreamAssembly).
        handler.ackGate(replica::replicaVerified);
        replica.onEpochVerified(handler::releaseDeferredAck);
    }

    @AfterEach
    void tearDown() {
        replica.close();
    }

    /// C1: the copy's trust for the epoch is read on the head the first batch found (empty, below the start 0), so the owner's
    /// own first records are acknowledged live. Read after the apply, the head (0) had reached the start and the replica
    /// doubted them, leaving the ack to a backfill.
    @Test
    void liveBatch_freshReplicaFirstBatchAtOffsetZero_isAckedWithoutBackfill() {
        handler.onReplicateEvents(liveBatch(0, 1, E1));
        handler.onReplicateEvents(liveBatch(1, 1, E1));

        assertThat(ackOffsets(E1)).as("acks; gap repairs = %s", gapRepairs.get()).containsExactly(0L, 1L);
        assertThat(gapRepairs.get()).as("no backfill was asked for").isZero();
        assertThat(replica.replicaVerified(STREAM, PARTITION)).isTrue();
    }

    /// The pre-apply reading decides: a gate that trusts the copy on that reading and doubts it afterwards (the committed
    /// epoch moved between the reading and the apply) still lets the batch's ack go, and the gate is read once.
    @Test
    void liveBatch_trustedOnThePreApplyReading_isAckedWithoutASecondReading() {
        var readings = new AtomicInteger();

        handler.ackGate((_, _) -> readings.incrementAndGet() == 1);
        handler.onReplicateEvents(liveBatch(0, 1, E1));

        assertThat(ackOffsets(E1)).containsExactly(0L);
        assertThat(readings.get()).as("gate readings").isEqualTo(1);
    }

    /// The snapshot is taken only for a batch of the committed epoch. A batch of a newer epoch (this replica has not yet seen
    /// its commit) reads the gate after the apply, as before: the fresh copy's head then reached the committed epoch's start.
    @Test
    void liveBatch_ofAnEpochNewerThanTheCommittedOne_isNotAckedOnTheSnapshot() {
        handler.onReplicateEvents(liveBatch(0, 1, E2));

        assertThat(acks).as("no ack on a pre-apply reading for another epoch").isEmpty();
        assertThat(gapRepairs.get()).isEqualTo(1);
    }

    /// A genuinely unverified copy (head at or above the start before the batch): the ack is deferred, not dropped, and sent
    /// once a compare verifies the copy, only after the durability barrier.
    @Test
    void liveBatch_onUnverifiedCopy_isDeferred_andReleasedDurablyOnceVerified() {
        unverifiedCopyUnderE2();

        handler.onReplicateEvents(liveBatch(5, 1, E2));

        assertThat(acks).as("the gate holds the ack").isEmpty();
        assertThat(gapRepairs.get()).as("the compare is asked for").isEqualTo(1);

        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(acks).as("not sent before the durability barrier").isEmpty();

        releaseHeldSyncs();

        assertThat(ackOffsets(E2)).containsExactly(5L);

        holdSyncs.set(false);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        handler.releaseDeferredAck(STREAM, PARTITION);

        assertThat(ackOffsets(E2)).as("sent once; a released ack is consumed").containsExactly(5L);
    }

    /// G2: the committed epoch advanced between the deferral and the release. The deferred ack carries the old epoch and is
    /// dropped, never sent; the next live batch acks under the new one.
    @Test
    void deferredAck_epochAdvancedBeforeRelease_isNotSentUnderTheOldEpoch() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        committed.set(E3);

        replica.markVerifiedForEpoch(STREAM, PARTITION, E3);

        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("the gate is open for E3").isTrue();
        assertThat(acks).as("nothing is acked under E2 once E3 is committed").isEmpty();

        handler.onReplicateEvents(liveBatch(6, 1, E3));

        assertThat(ackOffsets(E3)).containsExactly(6L);
    }

    /// G3: a live ack sent after verification covers the deferred one, and the release that was waiting on its durability
    /// barrier does not then send the older, lower offset after it.
    @Test
    void deferredAck_coveredByALaterLiveAck_isNotReSentLower() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        handler.onReplicateEvents(liveBatch(6, 2, E2));

        var releaseSync = heldSyncs.poll();
        var liveSync = heldSyncs.poll();

        drain(liveSync);
        drain(releaseSync);

        assertThat(ackOffsets(E2)).as("the owner's last ack is the highest").containsExactly(7L);
    }

    /// The other side of G3: a live ack LOWER than the deferred one (an old duplicate re-delivered after verification) does not
    /// cover it, so the release still sends the higher offset, after the live one.
    @Test
    void deferredAck_higherThanALaterLiveDuplicateAck_isStillReleased() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        handler.onReplicateEvents(liveBatch(5, 1, E2));

        var releaseSync = heldSyncs.poll();
        var liveSync = heldSyncs.poll();

        drain(liveSync);
        drain(releaseSync);

        assertThat(ackOffsets(E2)).containsExactly(5L, 7L);
    }

    /// The release's durability barrier failed: the ack is withheld, as a live one is, and the operator is told.
    @Test
    void deferredAck_releaseSyncFails_isWithheldAndWarned() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        var held = heldSyncs.poll();
        var drained = new CountDownLatch(1);

        held.onResult(_ -> drained.countDown());
        held.resolve(Causes.cause("fsync failed").result());

        assertThat(awaitLatch(drained)).isTrue();
        assertThat(acks).isEmpty();
        assertThat(eventually(() -> warnings.stream().anyMatch(warning -> warning.code() == OperatorWarningCode.REPLICA_FSYNC_FAILED)))
                  .as("warnings = %s", warnings)
                  .isTrue();
    }

    /// G4: a repair cut the copy below the deferred offset between the deferral and the release. The ack is capped at the head
    /// the copy holds when it is sent.
    @Test
    void deferredAck_tailCutBeforeRelease_isCappedAtTheHead() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        cutNext.set(6L);

        releaseHeldSyncs();

        assertThat(ackOffsets(E2)).containsExactly(5L);
    }

    /// A lower re-delivery deferred after a higher batch does not lower what is released.
    @Test
    void deferredAck_lowerReDeliveryWhileDeferred_releasesTheHigher() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        handler.onReplicateEvents(liveBatch(5, 1, E2));

        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(ackOffsets(E2)).containsExactly(7L);
    }

    /// A compare verified the copy after the gate was read and before the ack was kept: its release found nothing then, so
    /// the keep tries the release again and the ack is sent without waiting for another verification.
    @Test
    void deferredAck_verifiedBetweenTheGateReadAndTheKeep_isStillSent() {
        unverifiedCopyUnderE2();
        var reads = new AtomicInteger();

        handler.ackGate((stream, partition) -> readThenVerifyOnSecond(reads, stream, partition));
        handler.onReplicateEvents(liveBatch(5, 1, E2));

        assertThat(reads.get()).as("read before and after the apply, then on the release").isEqualTo(3);
        assertThat(ackOffsets(E2)).containsExactly(5L);
    }

    /// G4 at its floor: a repair cut every record the copy held. Nothing is acknowledged, not an offset below the log.
    @Test
    void deferredAck_tailCutToNothing_sendsNoAck() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        cutNext.set(0L);

        releaseHeldSyncs();

        assertThat(acks).isEmpty();
    }

    /// The flake's window, placed deterministically through the backfill's seam: a compare has acked its own watermark and
    /// not yet marked the copy verified when the owner's next live batch lands. The gate holds that batch's ack; the compare's
    /// verification then sends it. Dropped instead, nothing acked the batch until a further backfill run, and the owner's
    /// 5 s await failed when none came.
    @Test
    void backfillCompare_liveBatchInTheVerifyWindow_isAckedWhenTheCompareVerifies() {
        var ownerLog = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        ownerLog.createStream(StreamConfig.streamConfig(STREAM));
        unverifiedCopyUnderE2();
        IntStream.range(0, 6).forEach(i -> ownerLog.appendRecovered(STREAM, PARTITION, payload(i), 1000L + i).unwrap());

        var inWindow = new AtomicBoolean();
        var backfill = backfill(ownerLog);

        backfill.verifyWindowHook(() -> deliverOnce(inWindow, liveBatch(6, 1, E2)));
        backfill.backfill(STREAM, PARTITION).await();

        // The release's ack is sent from the durability barrier's callback, which may run on another thread.
        assertThat(eventually(() -> replica.replicaVerified(STREAM, PARTITION))).as("the compare verified the copy").isTrue();
        assertThat(inWindow.get()).as("the live batch landed in the window").isTrue();
        assertThat(eventually(() -> ackOffsets(E2).contains(6L))).as("the window batch's own ack; acks = %s", acks).isTrue();
        ownerLog.close();
    }

    /// The gate as wired, except that its second reading (after the apply) is followed by the compare's verification, before
    /// the handler keeps the ack the reading held back.
    private boolean readThenVerifyOnSecond(AtomicInteger reads, String stream, int partition) {
        var verified = replica.replicaVerified(stream, partition);

        if (reads.incrementAndGet() == 2) {
            replica.markVerifiedForEpoch(stream, partition, E2);
        }

        return verified;
    }

    private void deliverOnce(AtomicBoolean delivered, ReplicationMessage.ReplicateEvents batch) {
        if (delivered.compareAndSet(false, true)) {
            assertThat(replica.replicaVerified(STREAM, PARTITION)).as("the window is before the verification").isFalse();
            handler.onReplicateEvents(batch);
        }
    }

    /// The copy holds 0..4 written under E1; E2 (started at 3) is committed, so it holds records at and above E2's start that
    /// it has not compared with E2's owner.
    private void unverifiedCopyUnderE2() {
        IntStream.range(0, 5)
                 .forEach(i -> replica.appendRecovered(STREAM, PARTITION, i, payload(i), 1000L + i, E1).unwrap());
        replica.syncReplicated(STREAM, PARTITION).await();
        committed.set(E2);
        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("head 4 is at or above E2's start 3").isFalse();
    }

    private Promise<Unit> sync(String stream, int partition) {
        if (!holdSyncs.get()) {
            return replica.syncReplicated(stream, partition);
        }

        var held = Promise.<Unit>promise();

        heldSyncs.add(held);

        return held;
    }

    private void releaseHeldSyncs() {
        heldSyncs.forEach(ReplicaAckGateTest::drain);
        heldSyncs.clear();
    }

    /// Resolves a held durability barrier and returns once the handler's own callback on it has run: a promise runs its
    /// event handlers in one task, in the order they were attached, and the handler attached its callback first.
    private static void drain(Promise<Unit> held) {
        var drained = new CountDownLatch(1);

        held.onResult(_ -> drained.countDown());
        held.resolve(Result.unitResult());
        assertThat(awaitLatch(drained)).as("the barrier's callbacks ran").isTrue();
    }

    private static boolean eventually(BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private CommittedStreamOwnerSource committedOwners() {
        return (_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(owner, committed.get()));
    }

    private PartitionBackfill backfill(StreamPartitionManager ownerLog) {
        var registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, owner);
        registry.registerReplica(STREAM, PARTITION, self);

        return partitionBackfill(registry,
                                 replica.alignedRecovery(),
                                 (_, request) -> Promise.success(ownerResponse(ownerLog, request.fromOffset())),
                                 AcceptingReplicationTransport.accepting((_, _) -> {}),
                                 (_, _, _) -> Promise.promise(),
                                 (stream, partition) -> replica.partitionInfo(stream, partition)
                                                               .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                               .or(-1L),
                                 self,
                                 TimeSpan.timeSpan(3600).seconds(),
                                 () -> MEMBERS,
                                 committedOwners(),
                                 replica::syncReplicated,
                                 replica.quarantineView());
    }

    private ReplicationMessage.CatchupResponse ownerResponse(StreamPartitionManager ownerLog, long fromOffset) {
        var events = ownerLog.readAppended(STREAM, PARTITION, fromOffset, 100).unwrap();

        return catchupResponse(owner,
                               STREAM,
                               PARTITION,
                               fromOffset,
                               events.getLast().offset(),
                               events.stream().map(OffHeapRingBuffer.RawEvent::data).toList(),
                               events.stream().map(OffHeapRingBuffer.RawEvent::timestamp).toList());
    }

    private ReplicationMessage.ReplicateEvents liveBatch(int fromOffset, int count, Epoch epoch) {
        return replicateEvents(owner,
                               STREAM,
                               PARTITION,
                               fromOffset,
                               IntStream.range(fromOffset, fromOffset + count).mapToObj(ReplicaAckGateTest::payload).toList(),
                               IntStream.range(fromOffset, fromOffset + count).mapToObj(i -> 1000L + i).toList(),
                               epoch);
    }

    private List<Long> ackOffsets(Epoch epoch) {
        assertThat(acks).as("every ack carries %s", epoch).allSatisfy(ack -> assertThat(ack.ownerEpoch()).isEqualTo(epoch));

        return acks.stream().map(ReplicationMessage.ReplicateAck::confirmedOffset).toList();
    }

    private static byte[] payload(int i) {
        return ("event-" + i).getBytes(UTF_8);
    }
}
