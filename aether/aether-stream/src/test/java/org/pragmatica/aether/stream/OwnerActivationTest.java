// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.LongStream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1555 owner promotion gate. Each gate has a test that goes red when that gate is removed: the fresh-view
/// round + re-read, the catch-up to the highest live holder, and the owner-side fence that binds an activation to
/// the exact committed record.
class OwnerActivationTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId PEER_B = new NodeId("peer-b");

    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private final AtomicBoolean placementOwner = new AtomicBoolean(true);
    private final AtomicReference<List<NodeId>> members = new AtomicReference<>(List.of(SELF));
    private final Map<NodeId, Long> peerWatermarks = new ConcurrentHashMap<>();
    private final Set<NodeId> unreachable = ConcurrentHashMap.newKeySet();
    private final AtomicLong localWatermark = new AtomicLong(19);
    private final AtomicInteger rounds = new AtomicInteger();
    private final AtomicReference<Runnable> duringRound = new AtomicReference<>(() -> {});
    private final List<String> catchUps = new ArrayList<>();
    private final AtomicBoolean catchUpSucceeds = new AtomicBoolean(true);
    private final AtomicBoolean consensusActive = new AtomicBoolean(true);

    private final List<OwnerActivation.ActivationBlock> alarms = new CopyOnWriteArrayList<>();
    private final Set<NodeId> divergent = ConcurrentHashMap.newKeySet();
    private final OwnerActivation activation = gate(PromotionTestRanges.NEVER_ALARM);

    /// Every node holds the same lineage over any range the gate compares (`rec-<offset>`), except the nodes in
    /// [#divergent], whose records differ (`div-<offset>`). Divergence on real rings is pinned in
    /// [DivergentTailPromotionTest].
    private OwnerActivation gate(TimeSpan unreachableAlarmAfter) {
        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> placementOwner.get(),
                                               Option.some(this::round),
                                               members::get,
                                               this::probe,
                                               (_, _) -> localWatermark.get(),
                                               this::catchUp,
                                               consensusActive::get,
                                               this::range,
                                               this::raise,
                                               unreachableAlarmAfter);
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> range(NodeId node, String stream, int partition, long from, long to) {
        var tag = divergent.contains(node) ? "div-" : "rec-";

        return Promise.success(LongStream.rangeClosed(from, to)
                                         .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset,
                                                                                                 (tag + offset).getBytes(StandardCharsets.UTF_8),
                                                                                                 1L))
                                         .toList());
    }

    private Unit raise(OwnerActivation.ActivationBlock block) {
        alarms.add(block);

        return Unit.unit();
    }

    private Promise<Unit> round(String stream, int partition) {
        rounds.incrementAndGet();
        duringRound.get().run();

        return Promise.success(Unit.unit());
    }

    private Promise<Long> probe(NodeId target, String stream, int partition) {
        return unreachable.contains(target)
               ? Causes.cause("unreachable " + target).promise()
               : Promise.success(peerWatermarks.getOrDefault(target, -1L));
    }

    private Promise<Long> catchUp(String stream, int partition, NodeId source, long tail) {
        catchUps.add(source.id() + "@" + tail);

        if (!catchUpSucceeds.get()) {
            return Causes.cause("catch-up failed").promise();
        }

        localWatermark.set(tail);

        return Promise.success(tail);
    }

    private static StreamPartitionOwnershipValue ownedBy(NodeId owner, long term) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.epoch(0L, term, 0), term, HlcTimestamp.ZERO);
    }

    /// The refusal starts the activation asynchronously; poll briefly for it to land.
    private boolean eventuallyActivated() {
        for (var attempt = 0; attempt < 100 && !activation.isActivated(STREAM, PARTITION); attempt++) {
            LockSupport.parkNanos(10_000_000L);
        }

        return activation.isActivated(STREAM, PARTITION);
    }

    private boolean activate() {
        return activation.activate(STREAM, PARTITION)
                         .await()
                         .isSuccess();
    }

    @Test
    void activate_firstOwnerAlone_activatesWithoutRoundOrProbe() {
        assertThat(activate()).isTrue();
        assertThat(activation.isActivated(STREAM, PARTITION)).isTrue();
        assertThat(rounds).hasValue(0);
    }

    @Test
    void admit_notActivated_refusesTransientlyAndStartsActivation() {
        record.set(Option.some(ownedBy(SELF, 1)));

        var first = activation.admit(STREAM, PARTITION);

        assertThat(first.isFailure()).isTrue();
        first.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerNotActivated.class));
        assertThat(eventuallyActivated()).as("the refusal started the activation").isTrue();
        assertThat(activation.admit(STREAM, PARTITION).isSuccess()).isTrue();
    }

    /// F1e: a failed attempt is re-driven by the gate itself. The peer fails the probe on the first attempt and then
    /// answers; with NO further demand the partition is activated within the backoff, where before it stayed refused
    /// until something else demanded it (cloud run 1: ~25 s).
    @Test
    void admit_firstAttemptFails_redrivesWithoutAnotherDemand() {
        record.set(Option.some(ownedBy(SELF, 1)));
        members.set(List.of(SELF, PEER_A));
        unreachable.add(PEER_A);

        assertThat(activation.admit(STREAM, PARTITION).isFailure()).isTrue();
        LockSupport.parkNanos(50_000_000L);
        unreachable.remove(PEER_A);

        assertThat(eventuallyActivatedWithin(3_000L)).as("re-driven with no further demand").isTrue();
    }

    /// The re-drive stops once ownership has left the node: after the attempt that finds another node named, no
    /// further consensus round is ordered. (With the stop ignored the loop keeps running rounds every backoff.)
    @Test
    void admit_ownershipLeaves_redriveStops() {
        record.set(Option.some(ownedBy(SELF, 1)));
        members.set(List.of(SELF, PEER_A));
        unreachable.add(PEER_A);

        activation.admit(STREAM, PARTITION);
        record.set(Option.some(ownedBy(PEER_B, 2)));
        LockSupport.parkNanos(1_500_000_000L);

        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
        assertThat(rounds.get()).as("first attempt, then one re-drive that finds ownership gone").isEqualTo(2);
    }

    private boolean eventuallyActivatedWithin(long millis) {
        var deadline = System.nanoTime() + millis * 1_000_000L;

        while (System.nanoTime() < deadline && !activation.isActivated(STREAM, PARTITION)) {
            LockSupport.parkNanos(10_000_000L);
        }

        return activation.isActivated(STREAM, PARTITION);
    }

    /// Fresh-view gate: the round applies a newer committed record naming another node; the re-read after the
    /// round must see it and refuse, so a node whose committed view was stale never activates on it.
    @Test
    void activate_staleViewRefreshedByRound_refusesWhenNewerRecordNamesAnotherNode() {
        record.set(Option.some(ownedBy(SELF, 1)));
        duringRound.set(() -> record.set(Option.some(ownedBy(PEER_A, 2))));

        assertThat(activate()).isFalse();
        assertThat(rounds).hasValue(1);
        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
    }

    /// Observation: the gate's probe and overlap reads go through the real [OwnerPeerReads] against a peer that
    /// HOLDS the partition unmaterialized (paced) with durable data BELOW self. Pre-F1a that peer answered
    /// `PARTITION_NOT_LOCAL`, read as `-1`, and was skipped; now it reports 5, its overlap window is read, the read is
    /// refused (no ring), and activation is refused until the peer materializes.
    @Test
    void gate_pacedLowerPeerWithDurableData_refusesActivation_whileANonHolderDoesNot() {
        var heldAtFive = gateThrough((_, stream, partition, _, _) -> new org.pragmatica.aether.stream.forward.StreamForwardError.ReadForwardFailed(
            new StreamError.PartitionHeldNotMaterialized(stream, partition, 5L).message()).promise());
        var nonHolder = gateThrough((_, _, _, _, _) -> new org.pragmatica.aether.stream.forward.StreamForwardError.ReadForwardFailed(
            StreamError.General.PARTITION_NOT_LOCAL.message()).promise());

        members.set(List.of(SELF, PEER_A));

        assertThat(heldAtFive.activate(STREAM, PARTITION).await().isSuccess()).as("paced lower peer").isFalse();
        assertThat(nonHolder.activate(STREAM, PARTITION).await().isSuccess()).as("genuine non-holder").isTrue();
    }

    /// A peer that holds durable data but is deferred for off-heap BUDGET (no slot will free it) is reported, once, as a
    /// block naming the partition, the peer and both offsets; a merely paced peer is not (its slot will free).
    @Test
    void gate_budgetDeferredPeerWithDurableData_raisesTheBlock_pacedPeerDoesNot() {
        var budgetDeferred = gateThrough((_, stream, partition, _, _) -> new org.pragmatica.aether.stream.forward.StreamForwardError.ReadForwardFailed(
            new StreamError.PartitionHeldNotMaterialized(stream, partition, 5L, true).message()).promise());
        var paced = gateThrough((_, stream, partition, _, _) -> new org.pragmatica.aether.stream.forward.StreamForwardError.ReadForwardFailed(
            new StreamError.PartitionHeldNotMaterialized(stream, partition, 5L, false).message()).promise());

        members.set(List.of(SELF, PEER_A));

        assertThat(paced.activate(STREAM, PARTITION).await().isSuccess()).isFalse();
        assertThat(alarms).as("a paced peer raises no budget block").isEmpty();
        assertThat(budgetDeferred.activate(STREAM, PARTITION).await().isSuccess()).isFalse();
        assertThat(alarms).hasSize(1);
        var block = alarms.getFirst();

        assertThat(block).isInstanceOf(OwnerActivation.ActivationBlock.HolderBudgetDeferred.class);
        assertThat(block.message()).contains(STREAM + "[" + PARTITION + "]", PEER_A.id(), "head 5", "local head 19", "off-heap budget exhausted");
        assertThat(budgetDeferred.blockOf(STREAM, PARTITION)).isEqualTo(Option.some(block));

        budgetDeferred.activate(STREAM, PARTITION).await();
        assertThat(alarms).as("the same block is raised once").hasSize(1);
    }

    private OwnerActivation gateThrough(OwnerPeerReads.PageRead peerRead) {
        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> placementOwner.get(),
                                               Option.some(this::round),
                                               members::get,
                                               (peer, stream, partition) -> OwnerPeerReads.appendedWatermark(peerRead, peer, stream, partition, 16),
                                               (_, _) -> localWatermark.get(),
                                               this::catchUp,
                                               consensusActive::get,
                                               (node, stream, partition, from, to) -> node.equals(SELF)
                                                                                     ? range(node, stream, partition, from, to)
                                                                                     : OwnerPeerReads.appendedRange(peerRead, node, stream, partition, from, to, 16),
                                               this::raise,
                                               PromotionTestRanges.NEVER_ALARM);
    }

    /// Catch-up gate: a live member ahead of self is caught up from — the HIGHEST one — before activation.
    @Test
    void activate_liveMemberAhead_catchesUpFromHighestHolderFirst() {
        members.set(List.of(SELF, PEER_A, PEER_B));
        peerWatermarks.put(PEER_A, 24L);
        peerWatermarks.put(PEER_B, 22L);

        assertThat(activate()).isTrue();
        assertThat(catchUps).containsExactly("peer-a@24");
        assertThat(localWatermark).hasValue(24);
    }

    /// A catch-up that fails leaves the partition un-activated; activation never degrades to the local watermark.
    @Test
    void activate_catchUpFails_staysUnactivated() {
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 24L);
        catchUpSucceeds.set(false);

        assertThat(activate()).isFalse();
        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
    }

    /// Decision (a): an unreachable live member blocks activation; once the membership FSM declares it DEAD it
    /// leaves the live placement set and activation proceeds — but never while a reachable member is ahead.
    @Test
    void activate_unreachableMember_blocksUntilItLeavesTheLiveSet() {
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        assertThat(activate()).as("unreachable live member blocks").isFalse();

        members.set(List.of(SELF, PEER_B));
        peerWatermarks.put(PEER_B, 30L);

        assertThat(activate()).as("proceeds once the member is gone from the live set").isTrue();
        assertThat(catchUps).as("and still catches up to the reachable member ahead").containsExactly("peer-b@30");
    }

    /// #1555 item 8: a member unreachable for longer than the alarm window keeps the partition BLOCKED and is
    /// reported once — naming the partition, the unreachable member and the responders — and on the status read.
    @Test
    void activate_unreachablePastAlarmWindow_staysBlockedAndReportsOnce() {
        var reporting = gate(TimeSpan.timeSpan(0).millis());

        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        for (var attempt = 0; attempt < 3; attempt++) {
            LockSupport.parkNanos(1_000_000L);
            assertThat(reporting.activate(STREAM, PARTITION).await().isSuccess()).as("never bypassed").isFalse();
        }

        assertThat(alarms).hasSize(1);
        assertThat(alarms.getFirst()).isEqualTo(new OwnerActivation.ActivationBlock.HoldersUnreachable(STREAM,
                                                                                                       PARTITION,
                                                                                                       List.of(PEER_A),
                                                                                                       List.of(PEER_B),
                                                                                                       TimeSpan.timeSpan(0).millis()));
        assertThat(reporting.blockOf(STREAM, PARTITION)).isEqualTo(Option.some(alarms.getFirst()));

        members.set(List.of(SELF, PEER_B));

        assertThat(reporting.activate(STREAM, PARTITION).await().isSuccess()).isTrue();
        assertThat(reporting.blockOf(STREAM, PARTITION)).as("an activation clears the block").isEqualTo(Option.none());
    }

    /// A divergence block is cleared by the activation that follows once the divergent member is gone (pins the
    /// activation's own block clearing: nothing else clears a divergence block).
    @Test
    void activate_afterDivergentMemberLeaves_clearsTheDivergenceBlock() {
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 24L);
        divergent.add(PEER_A);

        assertThat(activate()).as("refused while the source disagrees").isFalse();
        assertThat(activation.blockOf(STREAM, PARTITION).isPresent()).isTrue();

        members.set(List.of(SELF));

        assertThat(activate()).isTrue();
        assertThat(activation.blockOf(STREAM, PARTITION)).as("the activation clears the block").isEqualTo(Option.none());
    }

    /// A catch-up source that holds none of the compared window (its ring and tier start above it) is refused as
    /// unverifiable, not trusted (#1555 R1: fail closed).
    @Test
    void activate_sourceHoldsNoneOfTheWindow_refusesAsUnverifiable() {
        var evicted = OwnerActivation.ownerActivation(SELF,
                                                      (_, _) -> record.get(),
                                                      (_, _) -> placementOwner.get(),
                                                      Option.some(this::round),
                                                      members::get,
                                                      this::probe,
                                                      (_, _) -> localWatermark.get(),
                                                      this::catchUp,
                                                      consensusActive::get,
                                                      (node, stream, partition, from, to) -> node.equals(PEER_A)
                                                                                              ? Promise.success(List.of())
                                                                                              : range(node, stream, partition, from, to),
                                                      this::raise,
                                                      PromotionTestRanges.NEVER_ALARM);

        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 24L);

        assertThat(evicted.activate(STREAM, PARTITION).await().isSuccess()).isFalse();
        assertThat(catchUps).as("nothing pulled from an unverifiable source").isEmpty();
        assertThat(evicted.blockOf(STREAM, PARTITION)).isEqualTo(Option.some(new OwnerActivation.ActivationBlock.OverlapUnverifiable(STREAM,
                                                                                                                                     PARTITION,
                                                                                                                                     PEER_A,
                                                                                                                                     24,
                                                                                                                                     SELF,
                                                                                                                                     19)));
    }

    @Test
    void activate_unreachableWithinAlarmWindow_reportsNothing() {
        members.set(List.of(SELF, PEER_A));
        unreachable.add(PEER_A);

        assertThat(activate()).isFalse();
        assertThat(activate()).isFalse();
        assertThat(alarms).isEmpty();
        assertThat(activation.blockOf(STREAM, PARTITION)).isEqualTo(Option.none());
    }

    /// Owner-side fence: an activation is bound to the exact committed record. A newer record — even one naming
    /// self again after a transfer away and back — no longer matches, and the node must re-run the gate.
    @Test
    void isActivated_committedRecordChanges_requiresReactivation() {
        record.set(Option.some(ownedBy(SELF, 1)));
        assertThat(activate()).isTrue();

        record.set(Option.some(ownedBy(SELF, 3)));

        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
        assertThat(activate()).isTrue();
        assertThat(activation.isActivated(STREAM, PARTITION)).isTrue();
    }

    @Test
    void isActivated_recordNamesAnotherNode_isFalse() {
        record.set(Option.some(ownedBy(SELF, 1)));
        assertThat(activate()).isTrue();

        record.set(Option.some(ownedBy(PEER_A, 2)));

        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
    }

    @Test
    void onQuorumStateChange_passive_dropsActivation() {
        record.set(Option.some(ownedBy(SELF, 1)));
        assertThat(activate()).isTrue();

        activation.onQuorumStateChange(ClusterStateNotification.passive());

        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
    }

    @Test
    void isActivated_consensusInactive_isFalse() {
        assertThat(activate()).isTrue();

        consensusActive.set(false);

        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
    }

    /// Decision (b): a first owner (no record) still probes and catches up when other live members exist —
    /// after a cold restart a same-id peer may hold on-disk data ahead of it.
    @Test
    void activate_firstOwnerWithPeers_stillCatchesUp() {
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 40L);

        assertThat(activate()).isTrue();
        assertThat(rounds).hasValue(0);
        assertThat(catchUps).containsExactly("peer-a@40");
    }
}
