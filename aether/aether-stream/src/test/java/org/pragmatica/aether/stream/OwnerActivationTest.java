// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

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
    private final OwnerActivation activation = gate(PromotionTestRanges.NEVER_ALARM);

    /// Every range reads empty, so no overlap is compared: divergence is pinned on real rings in
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
                                               (_, _, _, _, _) -> Promise.success(List.of()),
                                               this::raise,
                                               unreachableAlarmAfter);
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
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.epoch(term, 0), term, HlcTimestamp.ZERO);
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
