// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

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

/// #1883: the leader's writer (shrink) and the owner's monitor (expand) must decide membership from ONE liveness input.
/// With an asymmetric view of X (the leader's FSM does not count it, the owner still has it registered and acking) each
/// commit used to reverse the other, and every commit is a StreamPartitionOwnershipKey Put, which #1874 turns into a
/// reconcile on every node (10 commits in 5 rounds, found by v1877 F2). Pins the fixed point: the leader records the
/// member it removed for liveness (`fenced`) in the shrink commit, and the owner never expands a fenced member.
class IsrWriterMonitorFixedPointTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId X = new NodeId("node-x");
    private static final String STREAM = "orders";
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);

    @Test
    void leaderShrinkAndOwnerExpand_reachAFixedPoint_underAnAsymmetricViewOfOneReplica() {
        var registry = replicaRegistry();
        List.of(OWNER, B, X).forEach(node -> registry.registerReplica(STREAM, 0, node));
        registry.updateWatermark(STREAM, 0, B, 10L);
        registry.updateWatermark(STREAM, 0, X, 10L);
        var leaderLive = List.of(OWNER, B);
        var writer = new IsrOwnershipWriter(() -> true, () -> GENERATION, HlcClock.hlcClock(OWNER), (_, _) -> Option.none(),
                                            (_, _) -> Option.none(), new StreamPartitionOwnershipWriter.IsrInputs() {
            @Override
            public List<NodeId> liveMembers() {
                return leaderLive;
            }

            @Override
            public List<NodeId> initialIsr(String stream, int partition, NodeId owner) {
                return List.of(owner);
            }
        }, () -> Option.some(new LeaderValue(B, 1L)));
        var monitor = IsrMonitor.isrMonitor(OWNER, List::of, registry, () -> Option.some(new LeaderValue(B, 1L)),
                                            _ -> Promise.success(List.of()), TimeSpan.timeSpan(30).seconds(), () -> 0L);
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER, GENERATION.withCounter(1L), 1L,
                                                                                 HlcTimestamp.ZERO, List.of(OWNER, B, X), 1L);
        var commits = 0;

        for (var round = 0; round < 5; round++) {
            var current = record;
            var leaderNext = writer.next(STREAM, 0, Option.some(current), OWNER, GENERATION, leaderLive);
            if (leaderNext.isPresent()) {
                record = leaderNext.unwrap();
                commits++;
            }
            var ownerNext = monitor.nextIsr(new IsrMonitor.Owned(STREAM, 0, record, 10L), 0L);
            if (ownerNext.isPresent()) {
                record = record.withIsr(ownerNext.unwrap());
                commits++;
            }
        }

        assertThat(commits).as("ISR commits in 5 rounds (isrVersion now %d, ISR %s)", record.isrVersion(), record.isr())
                           .isLessThanOrEqualTo(1);
    }
}
