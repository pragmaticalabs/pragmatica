// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
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

/// Streams audit A-09 (#2201), fixed by overhaul PR-5a (#1961): `StreamCursorCheckpointValue` is fenced by the assignment token, by the
/// rewind epoch, and now by the scoped advance-only arm (`ScopedMonotonic`), so a checkpoint at a LOWER offset, under the same token and the
/// same rewind epoch, no longer replaces a higher one: a timed-out periodic commit that lands late (#1961), or a write from a stale tenure at
/// the same epoch, cannot step the cluster cursor back.
///
/// What must stay true: the rewind still works, so a regression at the SAME rewind epoch is the defect and a regression carrying a STRICTLY
/// NEWER rewind epoch is the feature (the control, GREEN before and after the fix). The real applier (`KVStore`) over the real keys and values.
/// The A-09 tripwire was deleted when the real assertion flipped; NT-81 and NT-113 live in `CursorCheckpointAdvanceOnlyTest`.
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

    private KVStore<AetherKey, AetherValue> kv;

    @BeforeEach
    void setUp() {
        kv = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        put(ASSIGNMENT_KEY, ConsumerAssignmentValue.consumerAssignmentValue(SELF, EPOCH, EPOCH.localCounter(), HlcTimestamp.ZERO));
    }

    @Test
    void checkpoint_atALowerOffset_underTheSameTokenAndRewindEpoch_isRefused() {
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(100L, TOKEN));
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(40L, TOKEN));

        assertThat(committedOffset()).isEqualTo(Option.some(100L));
    }

    /// Control, GREEN before and after the fix: an operator rewind (a rewind record at a strictly newer rewind epoch) still moves the cursor back, and the
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
