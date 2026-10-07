// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1730 phase 2 / #1873: an owner commits where its epoch begins before it is activated. The epoch is bumped by the
/// owner itself only when its ring was rebuilt since it last activated this partition, because only then can it assign
/// again offsets a consumer may already have read under the same epoch.
class OwnerActivationLineageTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch EPOCH = Epoch.epoch(1L, 2L, 3L);

    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private final AtomicLong watermark = new AtomicLong(9L);
    private final AtomicLong incarnation = new AtomicLong(1L);
    private final AtomicReference<Option<org.pragmatica.lang.Cause>> refusal = new AtomicReference<>(Option.none());
    private final List<String> commits = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean silentRefusal = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<OwnerActivation.ActivationBlock> raised = new ArrayList<>();
    private final List<OwnerActivation.ActivationBlock> cleared = new ArrayList<>();
    private final OwnerActivation activation = activation();

    private OwnerActivation activation() {
        return OwnerActivation.ownerActivation(SELF,
                                               (_, _) -> record.get(),
                                               (_, _) -> true,
                                               Option.none(),
                                               () -> List.of(SELF),
                                               (_, _, _) -> Promise.success(-1L),
                                               (_, _) -> watermark.get(),
                                               (_, _, _, _) -> Promise.success(0L),
                                               () -> true,
                                               (_, _, _, _, _) -> Promise.success(List.of()),
                                               new OwnerActivation.BlockAlarm() {
                                                   @Override
                                                   public Unit raise(OwnerActivation.ActivationBlock block) {
                                                       raised.add(block);

                                                       return Unit.unit();
                                                   }

                                                   @Override
                                                   public Unit cleared(OwnerActivation.ActivationBlock block) {
                                                       cleared.add(block);

                                                       return Unit.unit();
                                                   }
                                               },
                                               TimeSpan.timeSpan(1).hours(),
                                               (_, _) -> incarnation.get(),
                                               this::commit);
    }

    private Promise<Unit> commit(String stream, int partition, StreamPartitionOwnershipValue current, long start, boolean restarted) {
        commits.add((restarted ? "restart@" : "start@") + start);

        if (silentRefusal.get()) {
            return Promise.success(Unit.unit());
        }

        return refusal.get().fold(() -> applied(current, start, restarted), cause -> cause.promise());
    }

    private Promise<Unit> applied(StreamPartitionOwnershipValue current, long start, boolean restarted) {
        record.set(Option.some(restarted
                               ? current.restarted(start, HlcTimestamp.ZERO)
                               : current.withEpochStart(start)));

        return Promise.success(Unit.unit());
    }

    /// A failover (the leader bumped the epoch, nobody recorded its start): the new owner records the start at its next
    /// offset and keeps the epoch.
    @Test
    void firstActivationOfAnEpoch_commitsItsStartAtTheNextOffset_withoutBumping() {
        record.set(Option.some(committed(List.of(new EpochStart(Epoch.epoch(1L, 2L, 2L), 0L)))));

        assertThat(activate()).isTrue();

        assertThat(commits).containsExactly("start@10");
        assertThat(record.get().unwrap().ownerEpoch()).as("the leader's epoch stands").isEqualTo(EPOCH);
        assertThat(record.get().unwrap().lastEpochStart().unwrap()).isEqualTo(new EpochStart(EPOCH, 10L));
        assertThat(activation.isActivated(STREAM, PARTITION)).as("activated for the record it committed").isTrue();
    }

    /// The same process re-activates the ring it activated (the quorum came back): the offsets were kept.
    @Test
    void reactivationOfTheSameRing_commitsNothing() {
        record.set(Option.some(committed(List.of())));
        activate();
        commits.clear();

        assertThat(activate()).isTrue();

        assertThat(commits).isEmpty();
    }

    /// A restarted node: the record already names the start of its epoch (written by the previous process), this process
    /// has no memory of the ring, so the offsets above the resume point may be assigned again. A new epoch begins there.
    @Test
    void firstActivationInThisProcess_ofAnEpochThatAlreadyHasAStart_bumpsTheEpoch() {
        record.set(Option.some(committed(List.of(new EpochStart(EPOCH, 5L)))));
        watermark.set(2L);

        assertThat(activate()).isTrue();

        assertThat(commits).containsExactly("restart@3");
        var after = record.get().unwrap();

        assertThat(after.ownerEpoch()).isEqualTo(EPOCH.withCounter(after.ownershipTerm())).isNotEqualTo(EPOCH);
        assertThat(after.lastEpochStart().unwrap()).isEqualTo(new EpochStart(after.ownerEpoch(), 3L));
        assertThat(activation.isActivated(STREAM, PARTITION)).as("activated for the NEW ownership").isTrue();
    }

    /// The same process, but the ring is another one (a re-created stream, a re-materialization).
    @Test
    void aRebuiltRing_bumpsTheEpoch_evenInTheSameProcess() {
        record.set(Option.some(committed(List.of())));
        activate();
        commits.clear();
        incarnation.incrementAndGet();
        watermark.set(0L);

        assertThat(activate()).isTrue();

        assertThat(commits).containsExactly("restart@1");
    }

    /// The guarded write was refused (the record moved on): the activation fails and the node is not activated; a later
    /// run decides against the new record.
    @Test
    void aRefusedCommit_failsTheActivation() {
        record.set(Option.some(committed(List.of())));
        refusal.set(Option.some(Causes.cause("record moved on")));

        assertThat(activate()).isFalse();
        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();

        refusal.set(Option.none());

        assertThat(activate()).as("re-run against the record as it is now").isTrue();
    }

    /// #1976 owner rule: refusals that persist are told to the operator ONCE, at the Nth consecutive refusal, and the
    /// recovery is told once when the commit finally lands; a refused restart never latches, so each re-run commits again.
    @Test
    void persistentRefusals_raiseOneBlockAtTheThreshold_andClearItOnRecovery() {
        record.set(Option.some(committed(List.of(new EpochStart(EPOCH, 5L)))));
        refusal.set(Option.some(OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED));

        for (var attempt = 1; attempt < OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER; attempt++) {
            assertThat(activate()).isFalse();
        }

        assertThat(raised).as("below the threshold nothing is raised").isEmpty();
        assertThat(activate()).isFalse();
        assertThat(raised).as("the Nth refusal raises once").hasSize(1);
        assertThat(raised.getFirst()).isInstanceOf(OwnerActivation.ActivationBlock.LineageRefused.class);
        assertThat(activation.blockOf(STREAM, PARTITION).isPresent()).isTrue();

        assertThat(activate()).isFalse();
        assertThat(activate()).isFalse();
        assertThat(raised).as("flood-guarded: further refusals raise nothing").hasSize(1);
        assertThat(commits).as("every refusal retried the restart commit").hasSize(OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER + 2);

        refusal.set(Option.none());

        assertThat(activate()).as("the restart finally lands").isTrue();
        assertThat(cleared).as("the recovery is told once").hasSize(1);
        assertThat(activation.blockOf(STREAM, PARTITION).isEmpty()).isTrue();
    }

    /// v-1979 R4: the count and the reported block belong to one tenure. Stuck, deposed (the record names another owner),
    /// then owner again and stuck again: the second episode raises its own block, and the first was told as cleared. Red
    /// with the count surviving NOT_OWNER: the second episode starts past the threshold and raises nothing.
    @Test
    void aSecondStuckTenure_afterOwnershipLeft_raisesAgain() {
        var other = new NodeId("other");
        var mine = committed(List.of(new EpochStart(EPOCH, 5L)));

        record.set(Option.some(mine));
        refusal.set(Option.some(OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED));
        stickFor(OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER);
        assertThat(raised).hasSize(1);

        record.set(Option.some(new StreamPartitionOwnershipValue(other,
                                                                 EPOCH,
                                                                 4L,
                                                                 HlcTimestamp.ZERO,
                                                                 List.of(other),
                                                                 1L,
                                                                 false,
                                                                 List.of(),
                                                                 List.of(new EpochStart(EPOCH, 5L)))));
        assertThat(activate()).as("ownership left this node").isFalse();
        assertThat(cleared).as("the first episode is over").hasSize(1);

        record.set(Option.some(mine));
        stickFor(OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER);

        assertThat(raised).as("the second stuck tenure raises its own block").hasSize(2);
    }

    /// Quorum loss ends the tenure the same way.
    @Test
    void aSecondStuckTenure_afterQuorumLoss_raisesAgain() {
        record.set(Option.some(committed(List.of(new EpochStart(EPOCH, 5L)))));
        refusal.set(Option.some(OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED));
        stickFor(OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER);
        assertThat(raised).hasSize(1);

        activation.onQuorumStateChange(org.pragmatica.consensus.topology.ClusterStateNotification.passive());
        assertThat(cleared).as("the first episode is over").hasSize(1);

        stickFor(OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER);

        assertThat(raised).as("the second stuck tenure raises its own block").hasSize(2);
    }

    /// Refusals below the threshold do not carry across a quorum loss: the count belongs to the tenure that ended.
    @Test
    void refusalsBelowTheThreshold_doNotCarryAcrossQuorumLoss() {
        record.set(Option.some(committed(List.of(new EpochStart(EPOCH, 5L)))));
        refusal.set(Option.some(OwnerActivation.ActivationError.LINEAGE_NOT_COMMITTED));
        stickFor(OwnerActivation.LINEAGE_REFUSAL_ALARM_AFTER - 2);
        activation.onQuorumStateChange(org.pragmatica.consensus.topology.ClusterStateNotification.passive());
        stickFor(2);

        assertThat(raised).as("2 refusals in the new tenure, not 2 + the old tenure's").isEmpty();
    }

    private void stickFor(int attempts) {
        for (var attempt = 0; attempt < attempts; attempt++) {
            activate();
        }
    }

    /// The applier answers a refused guarded write with a result, not a failed promise: the commit "succeeds" and the record is
    /// unchanged. The activation must not take that for a committed start.
    @Test
    void aCommitThatSucceedsButChangesNothing_failsTheActivation() {
        record.set(Option.some(committed(List.of())));
        silentRefusal.set(true);

        assertThat(activate()).isFalse();
        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
    }

    /// A first owner (no committed record yet) has nothing to commit: the leader mints the first record and the
    /// activation re-runs against it.
    @Test
    void noCommittedRecord_commitsNothing() {
        assertThat(activate()).isTrue();
        assertThat(commits).isEmpty();
    }

    /// C4: no ring means no offset an epoch could begin at. The activation fails, commits nothing, and leaves the node
    /// not activated (the re-drive runs it again once a ring exists).
    @Test
    void noRing_commitsNothing_failsWithNoRing_andIsNotActivated() {
        record.set(Option.some(committed(List.of())));
        incarnation.set(-1L);

        var result = activation.activate(STREAM, PARTITION).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isEqualTo(OwnerActivation.ActivationError.NO_RING));
        assertThat(commits).isEmpty();
        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();

        incarnation.set(1L);

        assertThat(activate()).as("once the ring exists the same record activates").isTrue();
        assertThat(commits).containsExactly("start@10");
    }

    private boolean activate() {
        return activation.activate(STREAM, PARTITION).await().isSuccess();
    }

    private static StreamPartitionOwnershipValue committed(List<EpochStart> starts) {
        return new StreamPartitionOwnershipValue(SELF, EPOCH, 3L, HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), starts);
    }
}
