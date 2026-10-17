// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// Pins for the committed fenced set (#1883): each names the hunk it holds.
class IsrFencedPinsTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final NodeId X = new NodeId("node-x");
    private static final String STREAM = "orders";
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);

    private final AtomicReference<List<NodeId>> leaderLive = new AtomicReference<>(List.of(OWNER, B));
    private final ReplicaRegistry registry = replicaRegistry();
    private final IsrOwnershipWriter writer = writer();
    private final IsrMonitor monitor = IsrMonitor.isrMonitor(OWNER, List::of, registry, () -> Option.some(new LeaderValue(B, 1L)),
                                                             _ -> Promise.success(List.of()), TimeSpan.timeSpan(30).seconds(), () -> 0L);

    /// The asymmetric member starts OUTSIDE the ISR (the owner's stale
    /// registry re-admits it; the leader does not list it). Expected: one expand, one shrink+fence, then a fixed point.
    @Test
    void asymmetricMemberOutsideTheIsr_expandThenFence_thenSettled() {
        List.of(OWNER, B, X).forEach(node -> registry.registerReplica(STREAM, 0, node));
        registry.updateWatermark(STREAM, 0, B, 10L);
        registry.updateWatermark(STREAM, 0, X, 10L);
        var record = new AtomicReference<>(value(List.of(OWNER, B), List.of(), 1L));

        var commits = rounds(record, 6);

        assertThat(commits).as("expand X, then shrink+fence X, then nothing (ISR %s fenced %s)", record.get().isr(), record.get().fenced())
                           .isEqualTo(2);
        assertThat(record.get().isr()).containsExactly(OWNER, B);
        assertThat(record.get().fenced()).containsExactly(X);
    }

    /// Maximal ISR: a pending addition counts only while its CAS base is the committed record. A leader
    /// commit (here: B leaves the leader's view and is fenced) changes the record, so the pending X stops counting AND
    /// its CAS (expected = the old record) can no longer apply. The fenced set cannot hold the pending member, because
    /// the leader fences only members of the ISR or of the old fenced set.
    @Test
    void pendingAddition_thenLeaderFencesAnotherMember_pendingStopsCounting_andIsNeverFenced() {
        List.of(OWNER, B, X).forEach(node -> registry.registerReplica(STREAM, 0, node));
        registry.updateWatermark(STREAM, 0, B, 10L);
        registry.updateWatermark(STREAM, 0, X, 10L);
        var base = value(List.of(OWNER, B), List.of(), 1L);
        var proposed = monitor.nextIsr(new IsrMonitor.Owned(STREAM, 0, base, 10L), 0L).unwrap();

        assertThat(proposed).containsExactly(OWNER, B, X);
        assertThat(monitor.maximalIsr(STREAM, 0, base)).as("pending X counts on its base").containsExactly(OWNER, B, X);

        leaderLive.set(List.of(OWNER));
        var leaderCommit = writer.next(STREAM, 0, Option.some(base), OWNER, GENERATION, leaderLive.get()).unwrap();

        assertThat(leaderCommit.fenced()).as("only ISR members are fenced; the pending X is not").containsExactly(B);
        assertThat(leaderCommit.isr()).containsExactly(OWNER);
        assertThat(leaderCommit).as("the owner's CAS expected the base; it cannot apply").isNotEqualTo(base);
        assertThat(monitor.maximalIsr(STREAM, 0, leaderCommit)).as("pending X stops counting on a new record")
                                                                .containsExactly(OWNER);
    }

    /// The failover refusal (owner dead, no live ISR) must not drop the committed fenced set, and the
    /// resolution must fence the ISR members still not live.
    @Test
    void refusalKeepsTheFencedSet_andResolutionFencesTheStillDeadIsrMembers() {
        var current = value(List.of(OWNER, B), List.of(X), 4L);
        var refused = writer.next(STREAM, 0, Option.some(current), OWNER, GENERATION, List.of(C)).unwrap();

        assertThat(refused.failoverRefused()).isTrue();
        assertThat(refused.fenced()).as("a refusal must keep the fenced set").containsExactly(X);

        var resolved = writer.next(STREAM, 0, Option.some(refused), OWNER, GENERATION, List.of(OWNER, C)).unwrap();

        assertThat(resolved.failoverRefused()).isFalse();
        assertThat(resolved.isr()).containsExactly(OWNER);
        assertThat(resolved.fenced()).containsExactlyInAnyOrder(X, B);
    }


    private int rounds(AtomicReference<StreamPartitionOwnershipValue> record, int count) {
        var commits = 0;

        for (var round = 0; round < count; round++) {
            var leaderNext = writer.next(STREAM, 0, Option.some(record.get()), OWNER, GENERATION, leaderLive.get());

            if (leaderNext.isPresent()) {
                record.set(leaderNext.unwrap());
                commits++;
            }

            var ownerNext = monitor.nextIsr(new IsrMonitor.Owned(STREAM, 0, record.get(), 10L), 0L);

            if (ownerNext.isPresent()) {
                record.set(record.get().withIsr(ownerNext.unwrap()));
                commits++;
            }
        }

        return commits;
    }

    private static StreamPartitionOwnershipValue value(List<NodeId> isr, List<NodeId> fenced, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER,
                                                                           GENERATION.withCounter(1L),
                                                                           1L,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           isrVersion,
                                                                           fenced);
    }

    private IsrOwnershipWriter writer() {
        return new IsrOwnershipWriter(() -> true, () -> GENERATION, HlcClock.hlcClock(OWNER), (_, _) -> Option.none(),
                                      (_, _) -> Option.none(), new StreamPartitionOwnershipWriter.IsrInputs() {
            @Override
            public List<NodeId> liveMembers() {
                return leaderLive.get();
            }

            @Override
            public List<NodeId> initialIsr(String stream, int partition, NodeId owner) {
                return List.of(owner);
            }
        }, () -> Option.some(new LeaderValue(B, 1L)));
    }
}
