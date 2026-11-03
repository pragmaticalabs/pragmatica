// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.node.stream.ConsensusBatchCounters.KeyClass;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2590 behaviour 20 (the P0.4 instrument, moved to P1): the cursor command writer's batches, counted by key class.
class ConsensusBatchCountersTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final AssignmentToken TOKEN = AssignmentToken.assignmentToken(SELF, Epoch.epoch(0L, 1L, 1L));
    private static final StreamCursorCheckpointKey KEY = StreamCursorCheckpointKey.streamCursorCheckpointKey("orders", 0, "g");

    /// A cursor checkpoint increments the cursor class only, and the batch passes to the writer unchanged.
    /// Mutation: classify by value type instead of by key (the rewind control below then goes red).
    @Test
    void batchCounters_cursorPut_incrementsCursorClassOnly() {
        var counters = ConsensusBatchCounters.consensusBatchCounters();
        var written = new ArrayList<List<KVCommand<AetherKey>>>();
        var writer = counters.counting(batch -> record(written, batch));
        List<KVCommand<AetherKey>> batch = List.of(new KVCommand.Put<>(KEY,
                                                                       StreamCursorCheckpointValue.streamCursorCheckpointValue(70L,
                                                                                                                               TOKEN)));

        writer.apply(batch)
              .await();

        assertThat(written).containsExactly(batch);
        assertThat(counters.batches()).isEqualTo(1L);
        assertThat(counters.commands(KeyClass.CURSOR_PUT)).isEqualTo(1L);
        assertOnlyOne(counters, KeyClass.CURSOR_PUT);
    }

    /// The positive control: a rewind record is a cursor-key put whose value is the rewind, and it increments the rewind
    /// class only.
    @Test
    void batchCounters_rewindRecord_incrementsRewindClassOnly() {
        var counters = ConsensusBatchCounters.consensusBatchCounters();
        var writer = counters.counting(_ -> Promise.unitPromise());

        writer.apply(List.of(new KVCommand.Put<>(KEY,
                                                 StreamCursorCheckpointValue.rewindRecord(5L,
                                                                                          TOKEN,
                                                                                          RewindEpoch.rewindEpoch(0L, 1L, 1L)))))
              .await();
        writer.apply(List.of(new KVCommand.Noop<>(KEY)))
              .await();

        assertThat(counters.batches()).isEqualTo(2L);
        assertThat(counters.commands(KeyClass.REWIND)).isEqualTo(1L);
        assertThat(counters.commands(KeyClass.CURSOR_NOOP)).isEqualTo(1L);
        assertThat(counters.commands(KeyClass.CURSOR_PUT)).as("a rewind is not a checkpoint").isZero();
    }

    private static void assertOnlyOne(ConsensusBatchCounters counters, KeyClass expected) {
        for (var keyClass : KeyClass.values()) {
            if (keyClass != expected) {
                assertThat(counters.commands(keyClass)).as(keyClass.name()).isZero();
            }
        }
    }

    private static Promise<Unit> record(List<List<KVCommand<AetherKey>>> written, List<KVCommand<AetherKey>> batch) {
        written.add(batch);

        return Promise.unitPromise();
    }
}
