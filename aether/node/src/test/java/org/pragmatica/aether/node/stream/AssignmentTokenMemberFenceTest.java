// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
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
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3334 S1a-1a, T-47 value arm: the committed assignment's `member` is part of the token a checkpoint must carry. The real
/// applier (`KVStore`) over the real keys and values, as `CursorCheckpointAdvanceOnlyTest` does.
///
/// An assignment granted to member `m-a` admits a checkpoint under `m-a` and refuses one under `m-b`, one with no member, and
/// (the mirror) a member token against a no-member assignment. A refused put leaves the committed checkpoint untouched. The
/// no-member pair is the control: it lands, so the refusals above come from `member`, not from the fixture.
class AssignmentTokenMemberFenceTest {
    private static final String GROUP = "orders-onOrderEvent";
    private static final String STREAM = "orders";
    private static final int PARTITION = 2;
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final Epoch EPOCH = Epoch.epoch(0L, 1L, 1L);
    private static final ConsumerAssignmentKey ASSIGNMENT_KEY = ConsumerAssignmentKey.consumerAssignmentKey(STREAM, PARTITION, GROUP);
    private static final StreamCursorCheckpointKey CHECKPOINT_KEY = StreamCursorCheckpointKey.streamCursorCheckpointKey(STREAM, PARTITION, GROUP);

    @Test
    void checkpoint_underOtherMember_isRefused_andTheCursorIsUnchanged() {
        var kv = storeAssignedTo(some("m-a"));

        put(kv, tokenOf(some("m-a")), 10L);
        put(kv, tokenOf(some("m-b")), 20L);

        assertThat(committedOffset(kv)).isEqualTo(some(10L));
    }

    @Test
    void checkpoint_underOtherMember_onAnEmptyKey_isRefused() {
        var kv = storeAssignedTo(some("m-a"));

        put(kv, tokenOf(some("m-b")), 20L);

        assertThat(committedOffset(kv)).isEqualTo(none());
    }

    @Test
    void checkpoint_underTheAssignedMember_lands() {
        var kv = storeAssignedTo(some("m-a"));

        put(kv, tokenOf(some("m-a")), 10L);
        put(kv, tokenOf(some("m-a")), 20L);

        assertThat(committedOffset(kv)).isEqualTo(some(20L));
    }

    @Test
    void checkpoint_withNoMember_againstAMemberAssignment_isRefused() {
        var kv = storeAssignedTo(some("m-a"));

        put(kv, tokenOf(none()), 10L);

        assertThat(committedOffset(kv)).isEqualTo(none());
    }

    @Test
    void checkpoint_withAMember_againstANoMemberAssignment_isRefused() {
        var kv = storeAssignedTo(none());

        put(kv, tokenOf(some("m-a")), 10L);

        assertThat(committedOffset(kv)).isEqualTo(none());
    }

    /// Control: the no-member pair lands, as it did before `member` existed.
    @Test
    void checkpoint_noMemberPair_lands() {
        var kv = storeAssignedTo(none());

        put(kv, tokenOf(none()), 10L);

        assertThat(committedOffset(kv)).isEqualTo(some(10L));
    }

    private static KVStore<AetherKey, AetherValue> storeAssignedTo(Option<String> member) {
        var kv = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        apply(kv, ASSIGNMENT_KEY, ConsumerAssignmentValue.consumerAssignmentValue(SELF, EPOCH, EPOCH.localCounter(), HlcTimestamp.ZERO, member));
        return kv;
    }

    private static AssignmentToken tokenOf(Option<String> member) {
        return AssignmentToken.assignmentToken(SELF, EPOCH, member);
    }

    private static void put(KVStore<AetherKey, AetherValue> kv, AssignmentToken token, long offset) {
        apply(kv, CHECKPOINT_KEY, StreamCursorCheckpointValue.streamCursorCheckpointValue(offset, token));
    }

    private static void apply(KVStore<AetherKey, AetherValue> kv, AetherKey key, AetherValue value) {
        kv.process(kv.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));
    }

    private static Option<Long> committedOffset(KVStore<AetherKey, AetherValue> kv) {
        return kv.getTyped(CHECKPOINT_KEY, StreamCursorCheckpointValue.class).map(StreamCursorCheckpointValue::committedOffset);
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
