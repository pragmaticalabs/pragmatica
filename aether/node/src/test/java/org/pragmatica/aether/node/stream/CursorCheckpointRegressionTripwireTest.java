// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// Streams audit A-09 (#2201): the applier has no monotonic guard on the consensus cursor. `StreamCursorCheckpointValue` is fenced by the
/// assignment token and by the rewind epoch, but it is not `MonotonicFenced`, so a checkpoint at a LOWER offset, under the same token and the same
/// rewind epoch, replaces a higher one: a timed-out periodic commit that lands late (acknowledged in `ConsumerRuntimeState`), or a write from a
/// stale tenure at the same epoch, steps the cluster cursor back. The effect is redelivery (inside at-least-once), accepted so far on purpose so
/// that an operator rewind works.
///
/// What must stay true under any fix: the rewind still works, so a regression at the SAME rewind epoch is the defect and a regression carrying a
/// STRICTLY NEWER rewind epoch is the feature (a control, GREEN now and after the fix). The real applier (`KVStore`) over the real keys and values;
/// TRIPWIRE (enabled, GREEN) beside the REAL assertion (`@Disabled`, RED today).
class CursorCheckpointRegressionTripwireTest {
    private static final String GROUP = "orders-onOrderEvent";
    private static final String STREAM = "orders";
    private static final int PARTITION = 2;
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final Epoch EPOCH = Epoch.epoch(0L, 1L, 1L);
    private static final AssignmentToken TOKEN = AssignmentToken.assignmentToken(SELF, EPOCH);
    private static final RewindEpoch REWOUND = RewindEpoch.rewindEpoch(0L, 1L, 1L);
    private static final ConsumerAssignmentKey ASSIGNMENT_KEY = ConsumerAssignmentKey.consumerAssignmentKey(STREAM, PARTITION, GROUP);
    private static final StreamCursorCheckpointKey CHECKPOINT_KEY = StreamCursorCheckpointKey.streamCursorCheckpointKey(STREAM, PARTITION, GROUP);
    private static final String ENABLE = "A-09 tripwire: the applier now refuses a cursor checkpoint that regresses its offset at the same rewind epoch; delete this tripwire and enable the real assertion beside it (#2201)";

    private KVStore<AetherKey, AetherValue> kv;

    @BeforeEach
    void setUp() {
        kv = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        put(ASSIGNMENT_KEY, ConsumerAssignmentValue.consumerAssignmentValue(SELF, EPOCH, EPOCH.localCounter(), HlcTimestamp.ZERO));
    }

    @Test
    void checkpoint_atALowerOffset_underTheSameTokenAndRewindEpoch_replacesTheHigherOne_tripwire() {
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(100L, TOKEN));
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(40L, TOKEN));

        assertThat(committedOffset()).as(ENABLE).isEqualTo(Option.some(40L));
    }

    @Disabled("A-09 real assertion, RED today: a cursor checkpoint must not regress the committed offset at the same rewind epoch (#2201)")
    @Test
    void checkpoint_atALowerOffset_underTheSameTokenAndRewindEpoch_isRefused() {
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(100L, TOKEN));
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(40L, TOKEN));

        assertThat(committedOffset()).isEqualTo(Option.some(100L));
    }

    /// Control, GREEN now and after any fix: an operator rewind (a rewind record at a strictly newer rewind epoch) still moves the cursor back, and the
    /// checkpoints of the rewound consumer then advance from there.
    @Test
    void rewindRecord_atAStrictlyNewerRewindEpoch_stillMovesTheCursorBack_control() {
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(100L, TOKEN));
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.rewindRecord(0L, TOKEN, REWOUND));

        assertThat(committedOffset()).as("the rewind landed").isEqualTo(Option.some(0L));

        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(7L, TOKEN, REWOUND));

        assertThat(committedOffset()).as("the rewound consumer's checkpoint advances from the rewind").isEqualTo(Option.some(7L));
    }

    private Option<Long> committedOffset() {
        return kv.getTyped(CHECKPOINT_KEY, StreamCursorCheckpointValue.class).map(StreamCursorCheckpointValue::committedOffset);
    }

    private void put(AetherKey key, AetherValue value) {
        kv.process(kv.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                throw new UnsupportedOperationException("never snapshotted in this test");
            }
        };
    }
}
