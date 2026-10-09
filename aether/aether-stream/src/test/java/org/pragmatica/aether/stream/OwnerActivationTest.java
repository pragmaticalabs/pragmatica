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
    /// The first offset at which a node in [#divergent] differs; records below it are the shared lineage.
    private final AtomicLong divergentFrom = new AtomicLong(0L);
    private final OwnerActivation activation = gate(PromotionTestRanges.NEVER_ALARM);
    /// Where a peer's RING begins (the gate relaxes only for a divergence at or above it); none = not known.
    private final java.util.concurrent.atomic.AtomicReference<Option<Long>> peerTail = new java.util.concurrent.atomic.AtomicReference<>(Option.some(0L));

    {
        activation.peerRingTail((_, _, _) -> Promise.success(peerTail.get()));
    }

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
        return Promise.success(LongStream.rangeClosed(from, to)
                                         .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset,
                                                                                                 ((divergent.contains(node) && offset >= divergentFrom.get() ? "div-" : "rec-") + offset).getBytes(StandardCharsets.UTF_8),
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

    /// #1730 phase 2 (KIP-101): a candidate that was ELECTED from the committed ISR holds every acknowledged record
    /// (an acknowledgement needs every in-sync member), so a peer whose records differ from its own within the overlap
    /// is carrying a tail nobody acknowledged. The peer is left out of the catch-up and truncates itself when it
    /// backfills from the new owner; the activation proceeds. Before, the first returning ex-owner with an unacknowledged
    /// tail blocked the partition for good (`DivergentPeer`), while its own repair waited for the owner it was blocking.
    @Test
    void activate_isrElectedCandidate_peerDivergentInTheOverlap_isExcluded_andActivates() {
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);

        assertThat(activate()).as("the divergent peer must not block an ISR-elected candidate").isTrue();
        assertThat(alarms).isEmpty();
        assertThat(catchUps).isEmpty();
    }

    /// B8 (v1890 M21): the relaxation needs the candidate to be ELECTED FROM the committed ISR, i.e. the ISR names it. A record with
    /// a committed ISR (isrVersion > 0) that does not name this node is a candidate nobody guarantees holds every acknowledged
    /// record: a divergent peer keeps blocking.
    @Test
    void activate_candidateNotInTheCommittedIsr_peerDivergentInTheOverlap_stillRefuses() {
        record.set(Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF,
                                                                                           Epoch.epoch(0L, 3L, 0),
                                                                                           3L,
                                                                                           HlcTimestamp.ZERO,
                                                                                           List.of(PEER_A, PEER_B),
                                                                                           5L)));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);

        assertThat(activate()).as("a candidate outside the committed ISR does not relax the gate").isFalse();
        assertThat(alarms).singleElement().isInstanceOf(OwnerActivation.ActivationBlock.DivergentPeer.class);
    }

    /// A refusal WAITS, it does not block for good: a refused activation is re-driven with backoff (`OwnerActivation#redrive`) while the
    /// node still claims the partition, and re-evaluates the peers each time. Here the first attempt is refused because the divergent
    /// peer is in its sealed range (below its ring tail); when the peer's range is then repaired (it no longer differs) the re-drive
    /// activates with no further demand. A divergence that persists in the sealed range stays refused and reported until an operator
    /// picks a source (the block alarm).
    @Test
    void admit_refusedForASealedRangeDivergence_waits_thenActivatesOnceThePeerIsRepaired() {
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);
        divergentFrom.set(10L);
        peerTail.set(Option.some(11L));

        assertThat(activation.admit(STREAM, PARTITION).isFailure()).as("refused at first").isTrue();
        LockSupport.parkNanos(50_000_000L);
        assertThat(alarms).as("the refusal is reported").isNotEmpty();
        divergent.remove(PEER_A);

        assertThat(eventuallyActivatedWithin(3_000L)).as("re-driven once the peer no longer diverges, with no further demand").isTrue();
    }

    /// B6 (v1890 probe F): the compare reads the peer's range through its tier, so a difference can be found in data the peer has
    /// SEALED, where its own repair refuses to cut. That is not a relaxation case: the relaxation applies only to a divergence at
    /// an offset the peer still holds in its ring, and a peer whose ring tail is unknown is not relaxed for (fail safe).
    @Test
    void activate_isrElectedCandidate_peerDivergentOnlyBelowItsRingTail_stillRefuses() {
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);
        divergentFrom.set(10L);
        peerTail.set(Option.some(11L));

        assertThat(activate()).as("first divergence 10 is below the peer's ring tail 11: sealed data").isFalse();
        assertThat(alarms).singleElement().isInstanceOf(OwnerActivation.ActivationBlock.DivergentPeer.class);
    }

    @Test
    void activate_isrElectedCandidate_peerRingTailUnknown_isNotRelaxedFor() {
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);
        peerTail.set(Option.none());

        assertThat(activate()).as("unknown ring tail fails safe").isFalse();
    }

    /// Control: the divergence at or above the peer's ring tail is relaxed.
    @Test
    void activate_isrElectedCandidate_peerDivergentAtItsRingTail_isRelaxed() {
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);
        divergentFrom.set(10L);
        peerTail.set(Option.some(10L));

        assertThat(activate()).isTrue();
    }

    /// B6 (the design's Q6): the gate relaxes ONLY for a divergence ABOVE the candidate's durable sealed floor. A peer that
    /// differs from the candidate at or below the floor holds, in segments already sealed, something no truncation removes: the
    /// relaxation does not apply and the activation stays refused as before.
    @Test
    void activate_isrElectedCandidate_peerDivergentAtOrBelowTheSealedFloor_stillRefuses() {
        activation.sealedFloor((_, _) -> 12L);
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);
        divergentFrom.set(10L);

        assertThat(activate()).as("first divergence 10 is at or below the floor 12").isFalse();
        assertThat(alarms).singleElement().isInstanceOf(OwnerActivation.ActivationBlock.DivergentPeer.class);
    }

    /// Control: the same peer, the divergence ABOVE the floor: relaxed, and the left-out peer's registry row is forgotten.
    @Test
    void activate_isrElectedCandidate_peerDivergentAboveTheSealedFloor_isRelaxed_andItsRowIsForgotten() {
        var forgotten = new java.util.concurrent.CopyOnWriteArrayList<NodeId>();

        activation.sealedFloor((_, _) -> 5L);
        activation.peerRows((_, _, peer) -> forgotten.add(peer));
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);
        divergentFrom.set(10L);

        assertThat(activate()).as("first divergence 10 is above the floor 5").isTrue();
        assertThat(forgotten).containsExactly(PEER_A);
    }

    @Test
    void activate_isrElectedCandidate_divergentHighestHolder_isNotTheCatchUpSource() {
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A, PEER_B));
        peerWatermarks.put(PEER_A, 25L);
        peerWatermarks.put(PEER_B, 22L);
        divergent.add(PEER_A);

        assertThat(activate()).isTrue();
        assertThat(catchUps).as("the divergent ex-owner's longer tail is never pulled").containsExactly("peer-b@22");
    }

    /// Control: a record minted before #1730 carries no committed ISR, so the candidate was not elected from one and
    /// nothing outranks the divergence: the refusal stands.
    @Test
    void activate_candidateWithoutACommittedIsr_peerDivergentInTheOverlap_stillRefuses() {
        record.set(Option.some(ownedBy(SELF, 1)));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 15L);
        divergent.add(PEER_A);

        assertThat(activate()).isFalse();
        assertThat(alarms).singleElement().isInstanceOf(OwnerActivation.ActivationBlock.DivergentPeer.class);
    }

    /// Control: every peer agrees, so the ISR-elected candidate pulls from the highest as before (the exclusion is not a
    /// way of skipping the catch-up).
    @Test
    void activate_isrElectedCandidate_agreeingHigherPeer_isStillTheCatchUpSource() {
        record.set(Option.some(isrElected()));
        members.set(List.of(SELF, PEER_A));
        peerWatermarks.put(PEER_A, 25L);

        assertThat(activate()).isTrue();
        assertThat(catchUps).containsExactly("peer-a@25");
    }

    private static StreamPartitionOwnershipValue isrElected() {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF,
                                                                           Epoch.epoch(0L, 3L, 0),
                                                                           3L,
                                                                           HlcTimestamp.ZERO,
                                                                           List.of(SELF, PEER_A, PEER_B),
                                                                           5L);
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

    private final List<OwnerActivation.PromotionEscape> escapes = new CopyOnWriteArrayList<>();

    /// A gate whose alarm records the escapes as well as the blocks, with `bound` as the unreachable bound.
    private OwnerActivation gateReportingEscapes(TimeSpan bound) {
        // the alarm is far off: observed continuity (#2084 R2) restarts a member's clock after a gap longer than the alarm bound
        return gateReportingEscapes(TimeSpan.timeSpan(1).hours(), bound);
    }

    /// `alarmAfter`: when the unreachable-members block is reported; `escapeAfter`: when an ISR-named candidate goes ahead (#2080).
    private OwnerActivation gateReportingEscapes(TimeSpan alarmAfter, TimeSpan escapeAfter) {
        var alarm = recordingAlarm();

        var gate = OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> placementOwner.get(),
                                               Option.some(this::round),
                                               members::get,
                                               this::probe,
                                               (_, _) -> localWatermark.get(),
                                               this::catchUp,
                                               consensusActive::get,
                                               this::range,
                                               alarm,
                                               alarmAfter);

        gate.promotionEscapeAfter(escapeAfter);

        return gate;
    }

    private final List<OwnerActivation.ActivationBlock> resolvedBlocks = new CopyOnWriteArrayList<>();

    private OwnerActivation.BlockAlarm recordingAlarm() {
        return new OwnerActivation.BlockAlarm() {
            @Override
            public Unit raise(OwnerActivation.ActivationBlock block) {
                return OwnerActivationTest.this.raise(block);
            }

            @Override
            public Unit escaped(OwnerActivation.PromotionEscape escape) {
                escapes.add(escape);

                return Unit.unit();
            }

            @Override
            public Unit resolved(OwnerActivation.ActivationBlock block) {
                resolvedBlocks.add(block);

                return Unit.unit();
            }
        };
    }

    private static StreamPartitionOwnershipValue ownedWithIsr(NodeId owner, List<NodeId> isr, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                           Epoch.epoch(0L, 3L, 0),
                                                                           3L,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           isrVersion);
    }

    /// Runs the gate until it succeeds, at most three times, each after the bound (0 ms or longer) has certainly elapsed.
    private boolean activateAfterBound(OwnerActivation gate) {
        for (var attempt = 0; attempt < 3; attempt++) {
            LockSupport.parkNanos(2_000_000L);
            if (gate.activate(STREAM, PARTITION).await().isSuccess()) {
                return true;
            }
        }

        return false;
    }

    /// #2080 (T1a): a candidate named in the committed ISR (isrVersion > 0) does not wait for a member that stayed silent past the
    /// bound: it activates, catches up from the responder that is ahead, and reports ONE escape naming the partition, the candidate
    /// and the silent member. Red on rc4: the gate stays blocked (`activate_unreachablePastAlarmWindow_staysBlockedAndReportsOnce` is
    /// the same input without the ISR) and reports a block, never an escape.
    @Test
    void activate_isrCandidate_silentMemberPastTheBound_activatesAndReportsTheEscape() {
        // the alarm is far off, so a block here would mean the gate waited and then escaped without ending it
        var gate = gateReportingEscapes(TimeSpan.timeSpan(10).seconds(), TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        peerWatermarks.put(PEER_B, 24L);

        assertThat(activateAfterBound(gate)).as("activated past the silent member").isTrue();
        assertThat(gate.isActivated(STREAM, PARTITION)).isTrue();
        assertThat(catchUps).as("still caught up from the responder that is ahead").containsExactly("peer-b@24");
        assertThat(escapes).singleElement().satisfies(escape -> {
            assertThat(escape.streamName()).isEqualTo(STREAM);
            assertThat(escape.partition()).isEqualTo(PARTITION);
            assertThat(escape.gate()).isEqualTo(OwnerActivation.EscapeGate.OWNER_ACTIVATION);
            assertThat(escape.candidate()).isEqualTo(SELF);
            assertThat(escape.skipped()).containsExactly(PEER_A);
        });
        assertThat(alarms).as("the partition is not waiting any more: no unreachable-members block").isEmpty();
        assertThat(gate.blockOf(STREAM, PARTITION)).isEqualTo(Option.none());
    }

    /// #2080 (T1a): the escape is reported when the activation COMPLETES, not when the gate merely stopped waiting. While the catch-up
    /// from the responder fails nothing proceeded and nothing is reported; the attempt that lands reports ONE escape.
    @Test
    void activate_isrCandidate_escapeReportedWhenTheActivationCompletes() {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        peerWatermarks.put(PEER_B, 24L);
        catchUpSucceeds.set(false);

        for (var attempt = 0; attempt < 4; attempt++) {
            LockSupport.parkNanos(2_000_000L);
            assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).isFalse();
        }

        assertThat(escapes).as("the gate did not go ahead: nothing is reported").isEmpty();

        catchUpSucceeds.set(true);

        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).isTrue();
        assertThat(escapes).as("one escape").hasSize(1);
    }

    /// The epoch-start commit of the fixtures below: refused until `lineageAccepts`, then recorded the way the production writer does.
    private final java.util.concurrent.atomic.AtomicBoolean lineageAccepts = new java.util.concurrent.atomic.AtomicBoolean(false);

    private Promise<Unit> lineageCommit(String stream, int partition, StreamPartitionOwnershipValue current, long start, boolean restarted) {
        if (!lineageAccepts.get()) {
            return OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED.promise();
        }

        record.set(Option.some(current.withEpochStart(start)));

        return Promise.success(Unit.unit());
    }

    private OwnerActivation lineageGate(TimeSpan bound) {
        return lineageGate(TimeSpan.timeSpan(1).hours(), bound);
    }

    private OwnerActivation lineageGate(TimeSpan alarmAfter, TimeSpan bound) {
        var gate = OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> placementOwner.get(),
                                               Option.some(this::round),
                                               members::get,
                                               this::probe,
                                               (_, _) -> localWatermark.get(),
                                               this::catchUp,
                                               consensusActive::get,
                                               this::range,
                                               recordingAlarm(),
                                               alarmAfter,
                                               (_, _) -> 1L,
                                               this::lineageCommit);

        gate.promotionEscapeAfter(bound);

        return gate;
    }

    /// #2080: the gate going ahead is not the activation. The responders were caught up from and the silent member skipped, but the
    /// epoch-start commit is refused four times: the partition is not activated, so NO escape is reported. When the commit is accepted
    /// the activation completes and exactly one is. Mutation: reporting when the gate passes turns this red (four events).
    @Test
    void activate_isrCandidate_gateWentAheadButTheEpochStartIsRefused_reportsNoEscapeUntilTheActivationCompletes() {
        var gate = lineageGate(TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        for (var attempt = 0; attempt < 4; attempt++) {
            LockSupport.parkNanos(2_000_000L);
            assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).isFalse();
        }

        assertThat(escapes).as("four runs past the gate, none activated").isEmpty();

        lineageAccepts.set(true);

        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).isTrue();
        assertThat(escapes).as("the activation completed").hasSize(1);
    }

    /// #2080: an escape the member's return made moot is not reported. The gate went ahead once (the epoch-start commit refused), then
    /// the silent member answered and the activation completed WITH it: nothing was skipped, so nothing is reported. Mutation: not
    /// dropping the pending escape when the member answers turns this red.
    @Test
    void activate_isrCandidate_memberAnswersBeforeTheActivationCompletes_reportsNoEscape() {
        var gate = lineageGate(TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        LockSupport.parkNanos(2_000_000L);
        gate.activate(STREAM, PARTITION).await();
        LockSupport.parkNanos(2_000_000L);
        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("went ahead, epoch start refused").isFalse();

        unreachable.remove(PEER_A);
        lineageAccepts.set(true);

        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("activated with every member answering").isTrue();
        assertThat(escapes).isEmpty();
    }

    /// #2080: a pending escape belongs to the tenure that made it. Ownership left this node before the activation completed, and a
    /// later tenure activated with every member answering: the first tenure's escape must not be reported for it. Mutation: not
    /// dropping the pending escape when ownership leaves turns this red.
    @Test
    void activate_pendingEscapeOfAnEndedTenure_isNotReportedForTheNextOne() {
        var gate = lineageGate(TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        LockSupport.parkNanos(2_000_000L);
        gate.activate(STREAM, PARTITION).await();
        LockSupport.parkNanos(2_000_000L);
        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("went ahead, epoch start refused").isFalse();

        record.set(Option.some(ownedBy(PEER_B, 9)));
        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("ownership left").isFalse();

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 10L)));
        members.set(List.of(SELF));
        lineageAccepts.set(true);

        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("a new tenure with no other member to probe").isTrue();
        assertThat(escapes).isEmpty();
    }

    /// #2080: a block that was standing is told as resolved the moment the gate stops waiting, even when the activation then fails
    /// at the epoch-start commit: the partition no longer waits for the members the block names.
    @Test
    void activate_blockedThenEscapes_theBlockIsResolvedAtOnce_evenIfTheEpochStartIsStillRefused() throws Exception {
        var gate = lineageGate(TimeSpan.timeSpan(50).millis(), TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        roundsUntil(gate, () -> !alarms.isEmpty());
        assertThat(alarms).singleElement().isInstanceOf(OwnerActivation.ActivationBlock.HoldersUnreachable.class);

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 6L)));
        LockSupport.parkNanos(2_000_000L);

        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("epoch start refused").isFalse();
        assertThat(resolvedBlocks).containsExactly(alarms.getFirst());
        assertThat(escapes).isEmpty();
    }

    /// #2080: the alarm and the escape are different bounds. Between them an ISR-named candidate reports the unreachable-members block
    /// and still waits (a slow boot is not escaped past); at the escape bound it goes ahead, the block is told as resolved, and the
    /// escape names the configured bound and the time elapsed. Mutation: escaping at the alarm bound turns the first assertion red.
    @Test
    void activate_isrCandidate_betweenTheAlarmAndTheEscapeBound_reportsTheBlockAndWaits_thenEscapes() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(100).millis(), TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        assertThat(roundsUntil(gate, () -> !alarms.isEmpty())).as("past the alarm, before the escape bound: waits").isFalse();
        assertThat(alarms).singleElement().isInstanceOf(OwnerActivation.ActivationBlock.HoldersUnreachable.class);
        assertThat(escapes).isEmpty();

        assertThat(roundsUntil(gate, () -> false)).as("past the escape bound").isTrue();
        assertThat(resolvedBlocks).containsExactly(alarms.getFirst());
        assertThat(escapes).singleElement().satisfies(escape -> {
            assertThat(escape.bound()).isEqualTo(TimeSpan.timeSpan(300).millis());
            assertThat(escape.elapsed().millis()).isGreaterThanOrEqualTo(300L);
            assertThat(escape.message()).contains("promotion_escape_after").contains(escape.bound().toString());
        });
    }

    /// Runs the gate every 25 ms until `done` holds or two seconds pass: dense rounds, closer together than any alarm bound used here.
    private boolean roundsUntil(OwnerActivation gate, java.util.function.BooleanSupplier done) throws Exception {
        var deadline = System.nanoTime() + 2_000_000_000L;
        var activated = false;

        while (!done.getAsBoolean() && !activated && System.nanoTime() < deadline) {
            activated = gate.activate(STREAM, PARTITION).await().isSuccess();
            Thread.sleep(25);
        }

        return activated;
    }

    private boolean runGate(OwnerActivation gate) {
        LockSupport.parkNanos(2_000_000L);

        return gate.activate(STREAM, PARTITION).await().isSuccess();
    }

    /// #2080, per-member bound (v-2084 F1): the silence of one member is not inherited by another. PEER_A is silent past the bound, then
    /// answers while PEER_B goes silent for the first time: PEER_B has been silent for ~0 ms, so the gate keeps waiting for it.
    /// Mutation: one clock per partition instead of per member turns this red.
    @Test
    void activate_isrCandidate_aMemberThatJustWentSilent_isNotEscapedBecauseAnotherWasSilentLong() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        assertThat(runGate(gate)).isFalse();
        Thread.sleep(400);

        unreachable.remove(PEER_A);
        unreachable.add(PEER_B);

        assertThat(runGate(gate)).as("PEER_B has been silent for ~0 ms").isFalse();
        assertThat(escapes).isEmpty();
        Thread.sleep(400);

        assertThat(runGate(gate)).as("PEER_B past its own bound").isTrue();
        assertThat(escapes).singleElement().satisfies(escape -> assertThat(escape.skipped()).containsExactly(PEER_B));
    }

    /// #2080: with one member past the bound and another at 0 ms, the gate waits until BOTH are past; the escape then skips both, and
    /// the elapsed time it names is the SHORTEST silence.
    @Test
    void activate_isrCandidate_oneMemberPastTheBoundAndOneFresh_waitsForBoth() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        assertThat(runGate(gate)).isFalse();
        Thread.sleep(400);
        unreachable.add(PEER_B);

        assertThat(runGate(gate)).as("PEER_A past the bound, PEER_B fresh").isFalse();
        Thread.sleep(400);

        assertThat(runGate(gate)).as("both past").isTrue();
        assertThat(escapes).singleElement().satisfies(escape -> {
            assertThat(escape.skipped()).containsExactlyInAnyOrder(PEER_A, PEER_B);
            assertThat(escape.elapsed().millis()).as("the shortest silence").isBetween(300L, 700L);
        });
    }

    /// #2080: a flapping member resets only its own clock. PEER_B stays silent past the bound while PEER_A answers once and goes silent
    /// again: PEER_A's clock restarted, so the gate waits for it.
    @Test
    void activate_isrCandidate_aFlappingMemberResetsOnlyItsOwnClock() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        unreachable.add(PEER_B);
        assertThat(runGate(gate)).isFalse();
        Thread.sleep(200);
        unreachable.remove(PEER_A);
        assertThat(runGate(gate)).as("PEER_A answered, PEER_B still silent").isFalse();
        Thread.sleep(200);
        unreachable.add(PEER_A);

        assertThat(runGate(gate)).as("PEER_B ~400 ms silent, PEER_A restarted").isFalse();
        assertThat(escapes).isEmpty();
        Thread.sleep(400);

        assertThat(runGate(gate)).as("both past their own bounds").isTrue();
    }

    /// #2080 (v-2084 O9): a gate that was never told its escape bound never escapes, however long a member stays silent and whatever the
    /// record says. Production always wires it; this pins the default. Mutation: a finite default turns this red.
    @Test
    void activate_gateWithoutAnEscapeBound_neverEscapes() {
        var gate = OwnerActivation.ownerActivation(SELF,
                                                   (_, _) -> record.get(),
                                                   (_, _) -> placementOwner.get(),
                                                   Option.some(this::round),
                                                   members::get,
                                                   this::probe,
                                                   (_, _) -> localWatermark.get(),
                                                   this::catchUp,
                                                   consensusActive::get,
                                                   this::range,
                                                   recordingAlarm(),
                                                   TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        for (var attempt = 0; attempt < 4; attempt++) {
            assertThat(runGate(gate)).isFalse();
        }

        assertThat(escapes).isEmpty();
    }

    private final Set<NodeId> oversizedPeers = ConcurrentHashMap.newKeySet();

    private Promise<Long> probeOrOversized(NodeId target, String stream, int partition) {
        return oversizedPeers.contains(target)
               ? new OwnerPeerReads.EventExceedsReadCap(7L).<Long> promise()
               : probe(target, stream, partition);
    }

    private OwnerActivation gateWithOversizablePeers(TimeSpan alarmAfter, TimeSpan escapeAfter) {
        var gate = OwnerActivation.ownerActivation(SELF,
                                                   (_, _) -> record.get(),
                                                   (_, _) -> placementOwner.get(),
                                                   Option.some(this::round),
                                                   members::get,
                                                   this::probeOrOversized,
                                                   (_, _) -> localWatermark.get(),
                                                   this::catchUp,
                                                   consensusActive::get,
                                                   this::range,
                                                   recordingAlarm(),
                                                   alarmAfter);

        gate.promotionEscapeAfter(escapeAfter);

        return gate;
    }

    /// #2084 R1: a round refused because a peer's event is oversized still tells the clocks who answered. PEER_A is silent at 0 ms, answers at
    /// 150 ms (in a round refused for PEER_B's oversized event, PEER_C silent) and is silent again at 400 ms: its clock restarted at 150 ms,
    /// so after 250 ms of silence the gate keeps waiting. Mutation: not updating the clocks on the oversized path turns this red.
    @Test
    void activate_answerDuringAnOversizedRound_resetsTheMembersClock() throws Exception {
        var peerC = new NodeId("peer-c");
        var gate = gateWithOversizablePeers(TimeSpan.timeSpan(1).hours(), TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B, peerC), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B, peerC));
        unreachable.add(PEER_A);
        assertThat(runGate(gate)).as("t=0: A silent").isFalse();
        Thread.sleep(150);
        unreachable.remove(PEER_A);
        unreachable.add(peerC);
        oversizedPeers.add(PEER_B);
        assertThat(runGate(gate)).as("t=150: A answers, B oversized, C silent").isFalse();
        Thread.sleep(250);
        oversizedPeers.clear();
        unreachable.clear();
        unreachable.add(PEER_A);

        assertThat(runGate(gate)).as("t=400: A silent for 250 ms since it last answered").isFalse();
        assertThat(escapes).isEmpty();
    }

    /// #2084 R2, observed continuity: an unobserved gap is not silence. PEER_A is silent at 0 ms, no round looks at it for 400 ms (longer
    /// than the 150 ms alarm bound), and the next round sees it silent again: its run restarts there, so the 300 ms bound is not met.
    /// Mutation: dropping the gap rule turns this red; the dense-rounds control below must still escape at the bound.
    @Test
    void activate_unobservedGap_isNotCountedAsSilence() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(150).millis(), TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        assertThat(runGate(gate)).isFalse();
        Thread.sleep(400);

        assertThat(runGate(gate)).as("silent again after an unobserved gap").isFalse();
        assertThat(escapes).isEmpty();
    }

    @Test
    void activate_denseRounds_stillEscapeAtTheBound() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(150).millis(), TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        assertThat(roundsUntil(gate, () -> false)).as("rounds 25 ms apart, silent throughout").isTrue();
        assertThat(escapes).singleElement().satisfies(escape -> assertThat(escape.elapsed().millis()).isGreaterThanOrEqualTo(300L));
    }

    /// #2084 F9 (E4): a clock does not survive the tenure. PEER_A's silence is 400 ms old when ownership leaves this node; when ownership
    /// returns and PEER_A is silent for the first time of the new tenure, the gate waits. Mutation: not dropping the clocks when ownership
    /// leaves turns this red.
    @Test
    void activate_silenceClockDoesNotSurviveTheTenure() throws Exception {
        var gate = lineageGate(TimeSpan.timeSpan(1).hours(), TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        assertThat(runGate(gate)).isFalse();
        Thread.sleep(400);
        record.set(Option.some(ownedBy(PEER_B, 9)));
        assertThat(runGate(gate)).as("ownership left").isFalse();
        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 10L)));
        lineageAccepts.set(true);

        assertThat(runGate(gate)).as("PEER_A silent for ~0 ms in the new tenure").isFalse();
    }

    /// #2084 F9 (E5): a member that answered and goes silent again within the bound starts from zero. Mutation: not dropping the clocks when
    /// every member answers lets it inherit the old silence and turns this red.
    @Test
    void activate_silentThenAnswersThenSilentAgain_startsFromZero() throws Exception {
        var gate = lineageGate(TimeSpan.timeSpan(1).hours(), TimeSpan.timeSpan(300).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        assertThat(runGate(gate)).isFalse();
        Thread.sleep(400);
        unreachable.clear();
        assertThat(runGate(gate)).as("everyone answers (the epoch start is refused)").isFalse();
        unreachable.add(PEER_A);
        lineageAccepts.set(true);

        assertThat(runGate(gate)).as("silent again: zero, not 400 ms, so the gate waits (and does not activate)").isFalse();
        assertThat(escapes).isEmpty();
    }

    /// #2080: the bound is CONTINUOUS unreachability. With a bound of 150 ms the first run (silence just started) waits, and a run after
    /// the bound has passed goes ahead. Mutation: restarting the clock on every run means the bound never elapses.
    @Test
    void activate_isrCandidate_boundElapsesAcrossRuns_notWithinOne() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(150).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("silence just started").isFalse();
        Thread.sleep(250);

        assertThat(gate.activate(STREAM, PARTITION).await().isSuccess()).as("silent for longer than the bound").isTrue();
        assertThat(escapes).hasSize(1);
    }

    /// #2080: an activation ends the escape's episode, so a later tenure that has to go ahead again reports again.
    @Test
    void activate_isrCandidate_secondTenureEscapesAgain_reportsAgain() {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        assertThat(activateAfterBound(gate)).isTrue();
        assertThat(escapes).hasSize(1);

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 6L)));

        assertThat(activateAfterBound(gate)).isTrue();
        assertThat(escapes).as("a new tenure, a new escape").hasSize(2);
    }

    /// #2080: a candidate that was first blocked (not yet named in the committed set) and reported the block gets that block RESOLVED
    /// when the set comes to name it and it goes ahead: the partition no longer waits for the members the block names.
    @Test
    void activate_blockedThenNamedInTheIsr_theBlockIsResolved_andTheEscapeReported() throws Exception {
        var gate = gateReportingEscapes(TimeSpan.timeSpan(50).millis(), TimeSpan.timeSpan(0).millis());

        record.set(Option.some(ownedWithIsr(SELF, List.of(PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);
        assertThat(roundsUntil(gate, () -> !alarms.isEmpty())).isFalse();
        assertThat(alarms).singleElement().isInstanceOf(OwnerActivation.ActivationBlock.HoldersUnreachable.class);

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 6L)));

        assertThat(roundsUntil(gate, () -> false)).isTrue();
        assertThat(resolvedBlocks).as("the unreachable-members block is told as resolved").containsExactly(alarms.getFirst());
        assertThat(escapes).hasSize(1);
    }

    /// #2080 (T1b): the escape needs the ISR conjunct. A candidate NOT named in a committed ISR, a record whose ISR was never committed
    /// (isrVersion 0), and a first owner with no record at all stay blocked past the bound and report the block, never an escape.
    /// Mutation: deleting the ISR conjunct from the gate turns each of the three red.
    @Test
    void activate_silentMemberPastTheBound_withoutAnIsrNamingTheCandidate_staysBlocked() {
        var outsideIsr = ownedWithIsr(SELF, List.of(PEER_A, PEER_B), 5L);
        var neverCommitted = ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 0L);
        var cases = List.<Option<StreamPartitionOwnershipValue>> of(Option.some(outsideIsr), Option.some(neverCommitted), Option.none());

        for (var candidate : cases) {
            alarms.clear();
            var gate = gateReportingEscapes(TimeSpan.timeSpan(0).millis(), TimeSpan.timeSpan(0).millis());

            record.set(candidate);
            members.set(List.of(SELF, PEER_A, PEER_B));
            unreachable.add(PEER_A);

            assertThat(activateAfterBound(gate)).as("blocked: " + candidate).isFalse();
            assertThat(gate.isActivated(STREAM, PARTITION)).isFalse();
            assertThat(escapes).as("no escape: " + candidate).isEmpty();
            assertThat(alarms).as("the block is reported instead: " + candidate)
                              .singleElement()
                              .isInstanceOf(OwnerActivation.ActivationBlock.HoldersUnreachable.class);
        }
    }

    /// #2080: within the bound even an ISR candidate waits -- the escape is bounded, not immediate.
    @Test
    void activate_isrCandidate_silentMemberWithinTheBound_stillWaits() {
        var gate = gateReportingEscapes(PromotionTestRanges.NEVER_ALARM);

        record.set(Option.some(ownedWithIsr(SELF, List.of(SELF, PEER_A, PEER_B), 5L)));
        members.set(List.of(SELF, PEER_A, PEER_B));
        unreachable.add(PEER_A);

        assertThat(activateAfterBound(gate)).isFalse();
        assertThat(escapes).isEmpty();
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

    /// #1730: an ISR change moves no ownership. The owner stays activated across it, instead of re-running the
    /// whole gate (and refusing its writes meanwhile) every time a replica joins or leaves the ISR.
    @Test
    void isActivated_isrChangesOnly_staysActivated() {
        var owned = ownedBy(SELF, 1);

        record.set(Option.some(owned));
        assertThat(activate()).isTrue();

        record.set(Option.some(owned.withIsr(java.util.List.of(SELF, PEER_A))));

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
