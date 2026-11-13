// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
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
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
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
    private static final long BOOT_TOKEN = 7L;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);
    private static final Epoch E3 = Epoch.epoch(1L, 1L, 3L);
    private static final long E2_START = 3L;
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();
    private static final List<NodeId> MEMBERS = List.of(NODE_A, NODE_B);

    private final NodeId owner = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
    private final NodeId self = owner.equals(NODE_A) ? NODE_B : NODE_A;
    private final AtomicReference<Epoch> committed = new AtomicReference<>(E1);
    private final ConcurrentLinkedQueue<ReplicationMessage.ReplicateAck> acks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger gapRepairs = new AtomicInteger();
    /// The (stream, partition) every gap repair was asked for, as `stream[partition]`: the count alone cannot tell a repair for
    /// the wrong key from the right one.
    private final ConcurrentLinkedQueue<String> gapTargets = new ConcurrentLinkedQueue<>();
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
        // the committed record lists every epoch's start, the current one included (#1873: committed before activation)
        replica.epochStarts((_, _) -> List.of(new EpochStart(E1, 0L), new EpochStart(E2, E2_START)));
        handler = replicationReceiveHandler(self,
                                            replica::appendRecovered,
                                            (stream, partition) -> Math.min(replica.nextExpectedOffset(stream, partition),
                                                                            cutNext.get()),
                                            AcceptingReplicationTransport.accepting((_, message) -> acks.add((ReplicationMessage.ReplicateAck) message)),
                                            this::recordGapRepair,
                                            this::sync,
                                            committedOwners(),
                                            OperatorWarningSink.handingOffTo(warnings::add),
                                            () -> BOOT_TOKEN);
        // The production wiring (StreamAssembly).
        handler.ackGate(replica::replicaVerified);
        replica.onEpochVerified(handler::releaseDeferredAck);
        replica.onStreamRemoved(handler::forgetDeferredAcks);
    }

    private void recordGapRepair(String stream, int partition) {
        gapTargets.add(stream + "[" + partition + "]");
        gapRepairs.incrementAndGet();
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
        assertThat(gapTargets).as("the compare is asked for this partition").containsExactly(STREAM + "[" + PARTITION + "]");
    }

    /// A genuinely unverified copy (head at or above the start before the batch): the ack is deferred, not dropped, and sent
    /// once a compare verifies the copy, only after the durability barrier.
    @Test
    void liveBatch_onUnverifiedCopy_isDeferred_andReleasedDurablyOnceVerified() {
        unverifiedCopyUnderE2();

        handler.onReplicateEvents(liveBatch(5, 1, E2));

        assertThat(acks).as("the gate holds the ack").isEmpty();
        assertThat(gapRepairs.get()).as("the compare is asked for").isEqualTo(1);
        assertThat(gapTargets).as("the compare is asked for this partition").containsExactly(STREAM + "[" + PARTITION + "]");

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

    /// G4 at the release's head reading: a repair cut the copy below the deferred offset before the release read the head, and
    /// the cut offsets were written again before the send. Those were appended after the barrier was requested, so the ack stops
    /// at the head the release read.
    @Test
    void deferredAck_cutBeforeTheHeadReadAndRewrittenBeforeTheSend_acksOnlyWhatTheBarrierCovers() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        cutNext.set(6L);
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        cutNext.set(Long.MAX_VALUE);

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

        assertThat(reads.get()).as("read before and after the apply, then on the release and again in its send section").isEqualTo(4);
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

    /// v2735 F1 (G1/G4): a release whose durability barrier is in flight when the committed epoch advances, and a batch of the
    /// new epoch is deferred on a copy not verified for it, does not send that newer deferral when its barrier resolves. The newer
    /// one waits for its own verification, and is then sent under its epoch.
    @Test
    void releaseInFlight_deferralOfANewEpochOnAnUnverifiedCopy_isNotSentUntilVerified() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        committed.set(E3);
        holdSyncs.set(false);
        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("E3 starts at 0; head 5: unverified").isFalse();
        handler.onReplicateEvents(liveBatch(6, 1, E3));
        assertThat(acks).as("the E3 batch is deferred").isEmpty();

        drain(heldSyncs.poll());

        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("still unverified for E3").isFalse();
        assertThat(acks).as("no ack may leave a copy unverified for its epoch").isEmpty();

        replica.markVerifiedForEpoch(STREAM, PARTITION, E3);

        assertThat(ackOffsets(E3)).as("the E3 deferral is kept for its own release").containsExactly(6L);
    }

    /// v2735 F1, the identity half: the copy is verified for the new epoch before the first release's barrier resolves. That
    /// release still does not send the deferral that replaced the one it gated: the replacement is sent by its own release,
    /// after its own barrier.
    @Test
    void releaseInFlight_replacedDeferral_isSentOnlyAfterItsOwnBarrier() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        committed.set(E3);
        handler.onReplicateEvents(liveBatch(6, 1, E3));
        replica.markVerifiedForEpoch(STREAM, PARTITION, E3);

        var gatedSync = heldSyncs.poll();
        var replacementSync = heldSyncs.poll();

        assertThat(replacementSync).as("the replacement's own release is waiting on its barrier").isNotNull();

        drain(gatedSync);

        assertThat(acks).as("the first release sends nothing it did not gate").isEmpty();

        drain(replacementSync);

        assertThat(ackOffsets(E3)).containsExactly(6L);
    }

    /// v2735 F1, the gate half: the gate is read again in the section that sends. A gate that closes while the release's
    /// barrier is in flight keeps the deferral, and a later release sends it once the gate is open again.
    @Test
    void releaseInFlight_gateClosedBeforeTheSend_keepsTheDeferral() {
        var gateOpen = new AtomicBoolean(true);

        unverifiedCopyUnderE2();
        handler.ackGate((stream, partition) -> gateOpen.get() && replica.replicaVerified(stream, partition));
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        holdSyncs.set(true);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        gateOpen.set(false);

        releaseHeldSyncs();

        assertThat(acks).as("the gate closed before the send").isEmpty();

        holdSyncs.set(false);
        gateOpen.set(true);
        handler.releaseDeferredAck(STREAM, PARTITION);

        assertThat(ackOffsets(E2)).containsExactly(5L);
    }

    /// v2735 F2 (G3): the backfill acks its watermark BEFORE the compare verifies the copy, and that ack goes out in the
    /// receive handler's section (the production sink), where it settles the lower deferral of its epoch. The release that the
    /// verification then runs finds nothing to send, so the owner's view of this replica does not go from 10 back to 5.
    @Test
    void backfillAckThenRelease_neverLowersTheOwnersView() {
        var ownerLog = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        var releases = new AtomicInteger();

        ownerLog.createStream(StreamConfig.streamConfig(STREAM));
        unverifiedCopyUnderE2();
        IntStream.range(0, 11).forEach(i -> ownerLog.appendRecovered(STREAM, PARTITION, payload(i), 1000L + i).unwrap());
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        assertThat(acks).as("deferred").isEmpty();
        replica.onEpochVerified((stream, partition) -> releaseCounted(releases, stream, partition));
        holdSyncs.set(true);

        backfill((_, request) -> Promise.success(ownerResponse(ownerLog, request.fromOffset())),
                 AcceptingReplicationTransport.accepting((_, message) -> recordAck(message)),
                 (_, _, _) -> Promise.promise()).backfill(STREAM, PARTITION)
                                                .await();

        assertThat(eventually(() -> releases.get() > 0)).as("the compare verified the copy and ran the release").isTrue();
        releaseHeldSyncs();
        assertThat(ackOffsets(E2)).as("the backfill's ack settled the deferral; a release after it would send 5").containsExactly(10L);
        ownerLog.close();
    }

    /// G3 at its boundary: a backfill ack at exactly the deferred offset, in the deferral's epoch, settles it.
    @Test
    void backfillAck_atTheDeferredOffset_settlesTheDeferral() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        handler.ackBackfill(owner, STREAM, PARTITION, 5L, E2);

        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(ackOffsets(E2)).as("the backfill's ack only").containsExactly(5L);
    }

    /// G3 below its boundary: a backfill ack of the deferral's epoch below the deferred offset leaves it to its release.
    @Test
    void backfillAck_belowTheDeferredOffset_leavesTheDeferralToItsRelease() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        handler.ackBackfill(owner, STREAM, PARTITION, 6L, E2);

        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(ackOffsets(E2)).containsExactly(6L, 7L);
    }

    /// G3 across epochs: a backfill ack of another epoch settles nothing of this one; the deferral waits for its own release.
    @Test
    void backfillAck_ofAnotherEpoch_leavesTheDeferralToItsRelease() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        handler.ackBackfill(owner, STREAM, PARTITION, 10L, E1);

        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(ackOffsetsUnder(E1)).containsExactly(10L);
        assertThat(ackOffsetsUnder(E2)).as("released under its own epoch").containsExactly(5L);
    }

    /// v2735d probe (a), F2: this replica's committed view is still E2 and its copy is verified for E2; a batch of a NEWER epoch
    /// (E3, from its new owner, accepted because this view lags) lands at the head between the release's gate read and its head
    /// read. The release acks, under E2 to E2's owner, its deferred offset and never that head, which holds E3's records. The
    /// rule that acked the head read before the barrier sent 7 here.
    @Test
    void probeV2735d_releaseUnderE2_neverAcksANewerEpochsRecords() {
        var injected = new AtomicBoolean();

        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        assertThat(acks).as("deferred").isEmpty();
        holdSyncs.set(true);
        handler.ackGate((stream, partition) -> injectE3OnFirstVerifiedRead(injected, stream, partition));
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(injected.get()).as("the E3 batch was injected").isTrue();
        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).as("the E3 batch landed at the head").isEqualTo(8L);

        var releaseSync = heldSyncs.poll();

        assertThat(releaseSync).as("the E3 batch is not acked (#2840), so only the release's barrier is in flight").isNotNull();
        assertThat(heldSyncs).as("no barrier for an E3 ack").isEmpty();
        drain(releaseSync);

        assertThat(ackOffsetsUnder(E2)).as("acks under E2 cover only E2's records (0..5); all acks = %s", acks).containsExactly(5L);
        assertThat(ackOffsetsUnder(E3)).as("no ack under E3").isEmpty();
    }

    /// #2840: the copy is verified for E2 and acked 7 under E2; a batch of E3 (from another node) lands at offset 8 while this
    /// replica's view still says E2. The gate reads the view's epoch, so it would open: ack 8 under E3, counting offsets 6..7
    /// (E2 lineage) as E3's. No ack under E3 until the copy is verified for E3.
    @Test
    void liveBatch_ofANewerEpochThanTheLaggingView_isNotAckedUnderTheNewerEpoch() {
        unverifiedCopyUnderE2();
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        assertThat(ackOffsets(E2)).as("fixture: acked under E2").containsExactly(7L);

        handler.onReplicateEvents(newerEpochBatch(8, E3));

        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).as("the E3 batch landed").isEqualTo(9L);
        assertThat(ackOffsetsUnder(E3)).as("no ack under E3 on a lagging view; all acks = %s", acks).isEmpty();
        assertThat(gapRepairs.get()).as("the compare is asked for").isEqualTo(1);
        assertThat(gapTargets).as("the compare is asked for this partition").containsExactly(STREAM + "[" + PARTITION + "]");
    }

    /// #2753 (the v2735d probe): the gate opens only on a verification for the committed epoch, never on the OWNER placement
    /// role (`ReplicaSetController::roleFor`, from HRW placement, not the committed record). While the committed owner is still
    /// the batch's sender at E2, a stale compare's verification (an epoch captured before E2) runs the release, and the deferral
    /// of a copy never verified for E2 stays held. With the role bypass it was sent (ack 5 under E2).
    @Test
    void ownerRole_staleEpochVerification_doesNotReleaseTheDeferral() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        assertThat(acks).as("deferred").isEmpty();
        replica.placementRoleSupplier((_, _) -> Role.OWNER);

        replica.markVerifiedForEpoch(STREAM, PARTITION, E1);

        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("verified for E1, not E2, whatever the role").isFalse();
        assertThat(acks).as("#2753: no compare verified this copy for E2, so its deferral is not sent").isEmpty();
    }

    /// #2753, the live path: a batch of the committed epoch on a copy placed OWNER but never verified for that epoch is deferred,
    /// not acked. With the role bypass it was acked at once (5 under E2).
    @Test
    void ownerRole_liveBatchOnUnverifiedCopy_isDeferred() {
        unverifiedCopyUnderE2();
        replica.placementRoleSupplier((_, _) -> Role.OWNER);

        handler.onReplicateEvents(liveBatch(5, 1, E2));

        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).as("the batch was applied").isEqualTo(6L);
        assertThat(acks).as("#2753: the placement role does not open the gate").isEmpty();
    }

    /// #2753 liveness: a copy placed OWNER and not yet verified is not stalled. Its redrive input reads it unverified (the
    /// backfill re-drives such a copy at once), and its backfill compares it with the committed owner (the backfill's source),
    /// which verifies it for E2 and releases the held ack.
    @Test
    void ownerRole_unverifiedCopy_isVerifiedByTheCommittedOwnersCompare_andThenAcks() {
        var ownerLog = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        ownerLog.createStream(StreamConfig.streamConfig(STREAM));
        IntStream.range(0, 6).forEach(i -> ownerLog.appendRecovered(STREAM, PARTITION, payload(i), 1000L + i).unwrap());
        unverifiedCopyUnderE2();
        replica.placementRoleSupplier((_, _) -> Role.OWNER);
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        assertThat(acks).as("deferred").isEmpty();
        assertThat(replica.quarantineView().verifiedForCurrentEpoch(STREAM, PARTITION)).as("the redrive sees it unverified")
                                                                                      .isFalse();

        backfill(ownerLog).backfill(STREAM, PARTITION).await();

        assertThat(eventually(() -> replica.replicaVerified(STREAM, PARTITION))).as("the compare verified the copy for E2").isTrue();
        assertThat(eventually(() -> ackOffsets(E2).contains(5L))).as("the held ack is sent; acks = %s", acks).isTrue();
        ownerLog.close();
    }

    /// R1 TRIPWIRE (pins current behaviour): a compare whose response carries no records (an empty owner) never verifies the
    /// copy, so the deferred ack stays held, not dropped, until a carrying compare verifies it. When an empty compare comes to
    /// release the deferral, this pin turns red: replace it with a test of that release.
    @Test
    void tripwire_R1_emptyCompare_leavesTheDeferralHeld() {
        var requests = new AtomicInteger();

        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));

        var outcome = backfill((_, request) -> emptyResponse(requests, request.fromOffset()),
                               AcceptingReplicationTransport.accepting((_, message) -> recordAck(message)),
                               (_, _, _) -> Promise.success(-1L)).backfill(STREAM, PARTITION)
                                                                 .await(TimeSpan.timeSpan(5).seconds());

        assertThat(requests.get()).as("the compare asked the owner").isPositive();
        assertThat(outcome.isFailure()).as("an empty owner is no source: %s", outcome).isTrue();
        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("R1: the empty compare did not verify the copy").isFalse();
        assertThat(acks).as("R1: the deferral is held").isEmpty();

        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(ackOffsets(E2)).as("held, not dropped: a later verification releases it").containsExactly(5L);
    }

    /// m10: a deferral of the committed epoch is not displaced by a higher one of an older epoch. Deferred under E2 at 7, then
    /// E3 is committed (the copy unverified for it) and a lower E3 batch is deferred; once E3 is verified its ack is sent. Kept
    /// as the higher E2 one instead, the release dropped it (E2 is no longer committed) and the E3 batch had no ack.
    @Test
    void deferredAck_ofTheCommittedEpoch_isNotDisplacedByAHigherOlderEpochOne() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        committed.set(E3);
        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("unverified for E3").isFalse();
        handler.onReplicateEvents(liveBatch(6, 1, E3));
        assertThat(acks).as("both deferred").isEmpty();

        replica.markVerifiedForEpoch(STREAM, PARTITION, E3);

        assertThat(ackOffsets(E3)).as("the E3 ack, at its deferred offset: 7 is E2's record, not the E3 batch's")
                                  .containsExactly(6L);
    }

    /// m10, the other order: a deferral of a newer epoch is not displaced by a later-deferred lower one of an older epoch, which
    /// a release would drop.
    @Test
    void deferredAck_ofANewerEpoch_isNotDisplacedByALaterOlderEpochOne() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 2, E3));
        handler.onReplicateEvents(liveBatch(5, 1, E2));
        assertThat(acks).as("both deferred").isEmpty();
        committed.set(E3);

        replica.markVerifiedForEpoch(STREAM, PARTITION, E3);

        assertThat(ackOffsets(E3)).containsExactly(6L);
    }

    /// Cleanup: a removed stream's deferred acks are dropped with it, so a later verification releases nothing. (The release
    /// of the same deferral without the removal is `liveBatch_onUnverifiedCopy_isDeferred_andReleasedDurablyOnceVerified`.)
    @Test
    void deferredAck_ofARemovedStream_isForgotten() {
        unverifiedCopyUnderE2();
        handler.onReplicateEvents(liveBatch(5, 1, E2));

        handler.forgetDeferredAcks(STREAM);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(replica.replicaVerified(STREAM, PARTITION)).isTrue();
        assertThat(acks).isEmpty();
    }

    /// Cleanup wiring: the partition manager tells its listener the name of a stream it removed.
    @Test
    void streamRemoval_tellsTheRemovedListener() {
        var removed = new ConcurrentLinkedQueue<String>();

        replica.onStreamRemoved(removed::add);
        replica.onStreamConfigRemove(removal());

        assertThat(removed).containsExactly(STREAM);
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

    /// The production listener, counted: the release has run (and requested its barrier, if it had anything to send) when
    /// the count moves.
    private void releaseCounted(AtomicInteger releases, String stream, int partition) {
        handler.releaseDeferredAck(stream, partition);
        releases.incrementAndGet();
    }

    /// The real gate; at its first open reading, a batch of E3 from E3's owner lands at the head (6, 7).
    private boolean injectE3OnFirstVerifiedRead(AtomicBoolean injected, String stream, int partition) {
        var verified = replica.replicaVerified(stream, partition);

        if (verified && injected.compareAndSet(false, true)) {
            handler.onReplicateEvents(replicateEvents(NODE_C,
                                                      STREAM,
                                                      PARTITION,
                                                      6,
                                                      List.of("e3-6".getBytes(UTF_8), "e3-7".getBytes(UTF_8)),
                                                      List.of(2006L, 2007L),
                                                      E3));
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
        return backfill((_, request) -> Promise.success(ownerResponse(ownerLog, request.fromOffset())),
                        AcceptingReplicationTransport.accepting((_, _) -> {}),
                        (_, _, _) -> Promise.promise());
    }

    private PartitionBackfill backfill(CatchupTransport catchup, ReplicationTransport replication, ReplicaWatermarkProbe probe) {
        var registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, owner);
        registry.registerReplica(STREAM, PARTITION, self);

        var backfill = partitionBackfill(registry,
                                         replica.alignedRecovery(),
                                         catchup,
                                         replication,
                                         probe,
                                         (stream, partition) -> replica.partitionInfo(stream, partition)
                                                                       .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                                       .or(-1L),
                                         self,
                                         TimeSpan.timeSpan(3600).seconds(),
                                         () -> MEMBERS,
                                         committedOwners(),
                                         replica::syncReplicated,
                                         replica.quarantineView());

        // The production wiring (StreamAssembly).
        backfill.backfillAckSink(handler::ackBackfill);

        return backfill;
    }

    private void recordAck(ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateAck ack) {
            acks.add(ack);
        }
    }

    private Promise<ReplicationMessage.CatchupResponse> emptyResponse(AtomicInteger requests, long fromOffset) {
        requests.incrementAndGet();

        return Promise.success(catchupResponse(owner, STREAM, PARTITION, fromOffset, fromOffset - 1, List.of(), List.of()));
    }

    private static ValueRemove<StreamConfigKey, StreamConfigValue> removal() {
        return new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty());
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

    /// #2840 (race): the copy is verified for E2 and the view advances to E3 right after the gate's reading. The gate judged the
    /// copy for E2, so the ack must not go out under E3 on a copy not verified for E3: "ahead" is derived from the one reading
    /// of the view taken before the gate, not from a second one after it.
    @Test
    void liveBatch_viewAdvancesBetweenTheGateReadingAndTheAheadReading_isNotAckedUnderTheNewerEpoch() {
        unverifiedCopyUnderE2();
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        var first = new AtomicBoolean(true);

        handler.ackGate((stream, partition) -> verifiedThenAdvanceView(first, stream, partition));
        handler.onReplicateEvents(newerEpochBatch(8, E3));

        assertThat(replica.replicaVerified(STREAM, PARTITION)).as("fixture: copy NOT verified for E3").isFalse();
        assertThat(ackOffsetsUnder(E3)).as("no ack under E3 on an E3-unverified copy; all acks = %s", acks).isEmpty();
    }

    /// #2840 (liveness): the withheld ack is not in the deferral map, so catch-up and verification release nothing; the owner's
    /// next batch, once the view and the copy are at E3, is acked.
    @Test
    void liveBatch_afterAWithheldNewerEpochAck_catchUpReleasesNothing_andTheNextBatchIsAcked() {
        unverifiedCopyUnderE2();
        replica.markVerifiedForEpoch(STREAM, PARTITION, E2);
        handler.onReplicateEvents(liveBatch(5, 3, E2));
        handler.onReplicateEvents(newerEpochBatch(8, E3));
        assertThat(ackOffsetsUnder(E3)).as("fixture: the E3 batch is withheld").isEmpty();

        committed.set(E3);
        replica.markVerifiedForEpoch(STREAM, PARTITION, E3);
        handler.releaseDeferredAck(STREAM, PARTITION);
        assertThat(ackOffsetsUnder(E3)).as("catch-up and verification release nothing: withheld, not deferred").isEmpty();

        handler.onReplicateEvents(liveBatch(9, 1, E3));

        assertThat(ackOffsetsUnder(E3)).as("the next batch is acked after catch-up").containsExactly(9L);
    }

    private boolean verifiedThenAdvanceView(AtomicBoolean first, String stream, int partition) {
        var verified = replica.replicaVerified(stream, partition);

        if (first.getAndSet(false)) {
            committed.set(E3);
        }
        return verified;
    }

    private ReplicationMessage.ReplicateEvents newerEpochBatch(int fromOffset, Epoch epoch) {
        return replicateEvents(NODE_C,
                               STREAM,
                               PARTITION,
                               fromOffset,
                               List.of(payload(fromOffset)),
                               List.of(1000L + fromOffset),
                               epoch);
    }

    private List<Long> ackOffsets(Epoch epoch) {
        assertThat(acks).as("every ack carries %s", epoch).allSatisfy(ack -> assertThat(ack.ownerEpoch()).isEqualTo(epoch));

        return acks.stream().map(ReplicationMessage.ReplicateAck::confirmedOffset).toList();
    }

    private List<Long> ackOffsetsUnder(Epoch epoch) {
        return acks.stream()
                   .filter(ack -> ack.ownerEpoch().equals(epoch))
                   .map(ReplicationMessage.ReplicateAck::confirmedOffset)
                   .toList();
    }

    private static byte[] payload(int i) {
        return ("event-" + i).getBytes(UTF_8);
    }
}
