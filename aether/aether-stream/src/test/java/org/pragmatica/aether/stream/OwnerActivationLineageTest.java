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
                                               _ -> Unit.unit(),
                                               TimeSpan.timeSpan(1).hours(),
                                               (_, _) -> incarnation.get(),
                                               this::commit);
    }

    private Promise<Unit> commit(String stream, int partition, StreamPartitionOwnershipValue current, long start, boolean restarted) {
        commits.add((restarted ? "restart@" : "start@") + start);

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

    /// A first owner (no committed record yet) has nothing to commit: the leader mints the first record and the
    /// activation re-runs against it.
    @Test
    void noCommittedRecord_commitsNothing() {
        assertThat(activate()).isTrue();
        assertThat(commits).isEmpty();
    }

    private boolean activate() {
        return activation.activate(STREAM, PARTITION).await().isSuccess();
    }

    private static StreamPartitionOwnershipValue committed(List<EpochStart> starts) {
        return new StreamPartitionOwnershipValue(SELF, EPOCH, 3L, HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), starts);
    }
}
