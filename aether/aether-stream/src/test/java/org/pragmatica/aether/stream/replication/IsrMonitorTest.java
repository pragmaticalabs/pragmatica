// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #1730 owner-side ISR maintenance: a member lagging longer than `lagMax` is proposed out, a replica that reached the
/// high-water mark is proposed in, and the proposal is a guarded write of the committed record. The clock is a counter
/// the test advances, so "longer than lagMax" is exact.
class IsrMonitorTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final TimeSpan LAG_MAX = TimeSpan.timeSpan(30).seconds();
    private static final long HEAD = 10L;

    private final AtomicLong clock = new AtomicLong();
    private final List<List<KVCommand<AetherKey>>> applied = new ArrayList<>();
    private ReplicaRegistry registry;
    private StreamPartitionOwnershipValue record;
    private IsrMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        List.of(OWNER, B, C)
            .forEach(node -> registry.registerReplica(STREAM, PARTITION, node));
        record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER,
                                                                             Epoch.epoch(1L, 1L, 1L),
                                                                             1L,
                                                                             HlcTimestamp.ZERO,
                                                                             List.of(OWNER, B),
                                                                             1L);
        monitor = IsrMonitor.isrMonitor(OWNER,
                                        () -> List.of(new IsrMonitor.Owned(STREAM, PARTITION, record, HEAD)),
                                        registry,
                                        () -> Option.some(new LeaderValue(OWNER, 1L)),
                                        commands -> {
                                            applied.add(commands);

                                            return Promise.success(List.of());
                                        },
                                        LAG_MAX,
                                        clock::get);
    }

    @Test
    void nextIsr_memberBehindForLongerThanLagMax_leaves_notBefore() {
        confirm(B, HEAD);
        assertThat(monitor.nextIsr(owned(HEAD), clock.get()).isEmpty()).as("caught up").isTrue();

        clock.addAndGet(LAG_MAX.nanos());
        assertThat(monitor.nextIsr(owned(HEAD + 5), clock.get()).isEmpty()).as("behind for exactly lagMax: not yet")
                                                                           .isTrue();

        clock.addAndGet(1L);
        assertThat(monitor.nextIsr(owned(HEAD + 5), clock.get()).unwrap()).as("the owner is never dropped")
                                                                           .containsExactly(OWNER);
    }

    @Test
    void nextIsr_replicaThatReachedTheHighWaterMark_joins_notBelowIt() {
        confirm(B, 7L);
        confirm(C, 6L);
        assertThat(monitor.nextIsr(owned(HEAD), clock.get()).isEmpty()).as("C below the high-water mark 7").isTrue();

        confirm(C, 7L);
        assertThat(monitor.nextIsr(owned(HEAD), clock.get()).unwrap()).containsExactly(OWNER, B, C);
    }

    @Test
    void tick_proposesAGuardedWriteOfTheCommittedRecord() {
        confirm(B, HEAD);
        confirm(C, HEAD);

        var proposals = monitor.tick();

        assertThat(proposals).singleElement()
                             .isInstanceOfSatisfying(KVCommand.LeaderTransaction.class,
                                                     transaction -> assertThat(((KVCommand.Mutation<?, ?>) transaction.mutations()
                                                                                                                  .getFirst()).expected()).isEqualTo(Option.some(record)));
        assertThat(applied).hasSize(1);
    }

    /// #1883: a member the leader fenced is never expanded, however caught up the owner's registry shows it, and is
    /// never counted toward an acknowledgement (it would never join, so an ack would wait on it forever).
    @Test
    void nextIsr_fencedReplicaCaughtUp_isNeverAdmitted_andNeverCounted() {
        record = record.withIsrAndFenced(List.of(OWNER, B), List.of(C));
        confirm(B, HEAD);
        confirm(C, HEAD);

        assertThat(monitor.nextIsr(owned(HEAD), clock.get()).isEmpty()).as("C is fenced: no expansion").isTrue();
        assertThat(monitor.maximalIsr(STREAM, PARTITION, record)).as("nothing pending for a fenced member")
                                                                  .containsExactly(OWNER, B);
    }

    @Test
    void nextIsr_unfencedReplicaCaughtUp_isAdmitted_control() {
        record = record.withIsrAndFenced(List.of(OWNER, B), List.of());
        confirm(B, HEAD);
        confirm(C, HEAD);

        assertThat(monitor.nextIsr(owned(HEAD), clock.get()).unwrap()).containsExactly(OWNER, B, C);
    }

    private IsrMonitor.Owned owned(long head) {
        return new IsrMonitor.Owned(STREAM, PARTITION, record, head);
    }

    private void confirm(NodeId replica, long offset) {
        registry.updateWatermark(STREAM, PARTITION, replica, offset);
    }
}
