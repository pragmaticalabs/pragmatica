// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionAssignment;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1271: the leader-only writer of the committed consumer assignment — idempotent on an unchanged
/// assignee, a term bump on every move, nothing from a follower, and nothing when no node can consume.
class ConsumerAssignmentWriterTest {
    private static final String STREAM = "orders";
    private static final String GROUP = "orders-onOrderEvent";
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final long RABIA_TERM = 7L;

    private Map<ConsumerAssignmentKey, ConsumerAssignmentValue> committed;
    private ConsumerAssignmentWriter leader;

    @BeforeEach
    void setUp() {
        committed = new ConcurrentHashMap<>();
        leader = writer(true);
    }

    private ConsumerAssignmentWriter writer(boolean isLeader) {
        return ConsumerAssignmentWriter.consumerAssignmentWriter(() -> isLeader,
                                                                 () -> RABIA_TERM,
                                                                 HlcClock.hlcClock(NODE_A),
                                                                 (stream, partition, group) -> Option.option(committed.get(ConsumerAssignmentKey.consumerAssignmentKey(stream,
                                                                                                                                                                       partition,
                                                                                                                                                                       group))));
    }

    private static PartitionAssignment assigned(int partition, NodeId consumer) {
        return new PartitionAssignment(partition, Option.some(consumer), Option.some(consumer));
    }

    private void commit(int partition, NodeId assignee, long term) {
        committed.put(ConsumerAssignmentKey.consumerAssignmentKey(STREAM, partition, GROUP),
                      ConsumerAssignmentValue.consumerAssignmentValue(assignee, Epoch.epoch(1L, term), term, HlcTimestamp.ZERO));
    }

    private static ConsumerAssignmentValue valueOf(KVCommand<AetherKey> command) {
        return (ConsumerAssignmentValue) ((KVCommand.Put<?, ?>) command).value();
    }

    @Test
    void writeAssignmentChanges_mintsTermOne_whenNoRecordIsCommitted() {
        var commands = leader.writeAssignmentChanges(STREAM, GROUP, List.of(assigned(0, NODE_A)));

        assertThat(commands).hasSize(1);
        assertThat(valueOf(commands.getFirst())).extracting(ConsumerAssignmentValue::assignee,
                                                            ConsumerAssignmentValue::epoch,
                                                            ConsumerAssignmentValue::assignmentTerm)
                                                .containsExactly(NODE_A, Epoch.epoch(RABIA_TERM, 1L), 1L);
    }

    @Test
    void writeAssignmentChanges_writesNothing_whenTheAssigneeIsUnchanged() {
        commit(0, NODE_A, 3L);

        assertThat(leader.writeAssignmentChanges(STREAM, GROUP, List.of(assigned(0, NODE_A)))).isEmpty();
    }

    /// Every move bumps the term, so the new epoch strictly dominates — including a same-rabia-term move.
    @Test
    void writeAssignmentChanges_bumpsTheTerm_whenTheAssigneeMoves() {
        commit(0, NODE_A, 3L);

        var commands = leader.writeAssignmentChanges(STREAM, GROUP, List.of(assigned(0, NODE_B)));

        assertThat(valueOf(commands.getFirst())).extracting(ConsumerAssignmentValue::assignee,
                                                            ConsumerAssignmentValue::epoch,
                                                            ConsumerAssignmentValue::assignmentTerm)
                                                .containsExactly(NODE_B, Epoch.epoch(RABIA_TERM, 4L), 4L);
    }

    @Test
    void writeAssignmentChanges_writesNothing_onAFollower() {
        assertThat(writer(false).writeAssignmentChanges(STREAM, GROUP, List.of(assigned(0, NODE_A)))).isEmpty();
    }

    /// The slice is ACTIVE nowhere: no assignee is computable, and the standing record is left alone.
    @Test
    void writeAssignmentChanges_writesNothing_whenNoConsumerIsComputable() {
        commit(0, NODE_A, 3L);

        assertThat(leader.writeAssignmentChanges(STREAM,
                                                 GROUP,
                                                 List.of(new PartitionAssignment(0, Option.none(), Option.none())))).isEmpty();
    }
}
