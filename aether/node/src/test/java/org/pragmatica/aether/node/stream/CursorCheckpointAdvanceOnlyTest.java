// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.ArrayList;
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
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// Streams overhaul PR-5a (#1961, I-18): the consensus cursor checkpoint is advance-only inside its scope
/// `(rewind epoch, owner epoch)`. The real applier (`KVStore`) over the real keys and values.
///
/// NT-81: a same-scope lower offset is refused (the late periodic commit of #1961); a strictly newer rewind epoch and a
/// strictly newer owner epoch (the KIP-320 re-seek, `ConsumerEpochRewindTest` T-146 at the KV level) still lower the
/// cursor; the zombie checkpoint (an older rewind epoch) is still refused.
///
/// NT-113: a commit stamped `Epoch.ZERO` after a non-ZERO owner epoch inside the same rewind epoch is a lower scope and is
/// refused; the unfenced commit path never reaches the key.
class CursorCheckpointAdvanceOnlyTest {
    private static final String GROUP = "orders-onOrderEvent";
    private static final String STREAM = "orders";
    private static final int PARTITION = 2;
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final Epoch EPOCH = Epoch.epoch(0L, 1L, 1L);
    private static final AssignmentToken TOKEN = AssignmentToken.assignmentToken(SELF, EPOCH);
    private static final RewindEpoch REWOUND = RewindEpoch.rewindEpoch(0L, 1L, 1L);
    private static final Epoch OWNER_EPOCH = Epoch.epoch(1L, 2L, 1L);
    private static final Epoch NEWER_OWNER_EPOCH = Epoch.epoch(1L, 3L, 1L);
    private static final ConsumerAssignmentKey ASSIGNMENT_KEY = ConsumerAssignmentKey.consumerAssignmentKey(STREAM, PARTITION, GROUP);
    private static final StreamCursorCheckpointKey CHECKPOINT_KEY = StreamCursorCheckpointKey.streamCursorCheckpointKey(STREAM, PARTITION, GROUP);

    private KVStore<AetherKey, AetherValue> kv;

    @BeforeEach
    void setUp() {
        kv = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        put(ASSIGNMENT_KEY, ConsumerAssignmentValue.consumerAssignmentValue(SELF, EPOCH, EPOCH.localCounter(), HlcTimestamp.ZERO));
    }

    /// NT-81 (#1961): the late periodic commit, under the same token, rewind epoch and owner epoch, does not step the cursor back.
    @Test
    void checkpoint_sameScopeLowerOffset_isRefused() {
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));
        put(CHECKPOINT_KEY, checkpoint(40L, RewindEpoch.NONE, OWNER_EPOCH));

        assertThat(committedOffset()).isEqualTo(Option.some(100L));
    }

    /// NT-81: re-publishing the same position in the same scope still lands, and an advance does.
    @Test
    void checkpoint_sameScopeEqualThenHigherOffset_lands() {
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));

        assertThat(committedOffset()).as("equal offset").isEqualTo(Option.some(100L));

        put(CHECKPOINT_KEY, checkpoint(150L, RewindEpoch.NONE, OWNER_EPOCH));

        assertThat(committedOffset()).as("advance").isEqualTo(Option.some(150L));
    }

    /// NT-81 control (operator rewind): a rewind record at a strictly newer rewind epoch lowers the cursor, even though its owner
    /// epoch is ZERO and the committed one is not, and the rewound consumer advances from there.
    @Test
    void rewindRecord_newerRewindEpoch_lowersTheCursor() {
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.rewindRecord(0L, TOKEN, REWOUND));

        assertThat(committedOffset()).as("the rewind landed").isEqualTo(Option.some(0L));

        put(CHECKPOINT_KEY, checkpoint(7L, REWOUND, Epoch.ZERO));

        assertThat(committedOffset()).as("the rewound consumer advances from the rewind").isEqualTo(Option.some(7L));
    }

    /// NT-81 control (KIP-320 re-seek, T-146 at the KV level): a consumer that adopts a strictly newer owner epoch moves its
    /// cursor back to where that lineage begins, inside the same rewind epoch, and the checkpoint lands.
    @Test
    void checkpoint_newerOwnerEpochLowerOffset_lands() {
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));
        put(CHECKPOINT_KEY, checkpoint(60L, RewindEpoch.NONE, NEWER_OWNER_EPOCH));

        assertThat(committed()).isEqualTo(Option.some(List.<Object>of(60L, NEWER_OWNER_EPOCH)));
    }

    /// NT-81: a late commit from the replaced lineage (older owner epoch) is refused even when its offset is higher.
    @Test
    void checkpoint_olderOwnerEpochHigherOffset_isRefused() {
        put(CHECKPOINT_KEY, checkpoint(60L, RewindEpoch.NONE, NEWER_OWNER_EPOCH));
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));

        assertThat(committed()).isEqualTo(Option.some(List.<Object>of(60L, NEWER_OWNER_EPOCH)));
    }

    /// NT-81: the zombie checkpoint (#1333) is still refused: a pre-rewind consumer cannot move the cursor forward again.
    @Test
    void checkpoint_olderRewindEpochHigherOffset_isStillRefused() {
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));
        put(CHECKPOINT_KEY, StreamCursorCheckpointValue.rewindRecord(0L, TOKEN, REWOUND));
        put(CHECKPOINT_KEY, checkpoint(150L, RewindEpoch.NONE, OWNER_EPOCH));

        assertThat(committedOffset()).isEqualTo(Option.some(0L));
    }

    /// NT-113 (F-27): `Epoch.ZERO` after a non-ZERO owner epoch inside one rewind epoch is a LOWER scope, so it is refused even
    /// at a higher offset. No production flow is known to make this commit (overhaul spec 8.1); if one appears, the fix is in
    /// the consumer state, not in loosening the arm.
    @Test
    void checkpoint_zeroOwnerEpochAfterNonZeroInSameRewindEpoch_isRefused() {
        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));
        put(CHECKPOINT_KEY, checkpoint(150L, RewindEpoch.NONE, Epoch.ZERO));

        assertThat(committed()).isEqualTo(Option.some(List.<Object>of(100L, OWNER_EPOCH)));
    }

    /// NT-113: the unfenced commit (no assignment epoch) never reaches the key: no command is sent, the committed cursor is
    /// untouched, and the outcome is terminal.
    @Test
    void unfencedCommit_neverReachesTheKey() {
        var sent = new ArrayList<KVCommand<AetherKey>>();
        var store = storeFor(sent);

        put(CHECKPOINT_KEY, checkpoint(100L, RewindEpoch.NONE, OWNER_EPOCH));

        assertThat(store.commit(GROUP, STREAM, PARTITION, 40L).await()
                        .map(outcome -> outcome instanceof CommitOutcome.Fenced)).isEqualTo(Result.success(true));
        assertThat(sent).isEmpty();
        assertThat(committed()).isEqualTo(Option.some(List.<Object>of(100L, OWNER_EPOCH)));
    }

    /// #1961 through the real store: the late commit is published, refused by the applier, and the committed re-read tells the
    /// caller it did not land (a `LocalOnly`, not a `Persisted`); the higher checkpoint stands.
    @Test
    void lateCommitThroughTheStore_isRefused_andReportedAsNotLanded() {
        var store = storeFor(new ArrayList<>());

        assertThat(store.commit(GROUP, STREAM, PARTITION, 100L, EPOCH, RewindEpoch.NONE, OWNER_EPOCH).await())
                .isEqualTo(Result.success(CommitOutcome.persisted()));
        assertThat(store.commit(GROUP, STREAM, PARTITION, 40L, EPOCH, RewindEpoch.NONE, OWNER_EPOCH).await()
                        .map(outcome -> outcome instanceof CommitOutcome.LocalOnly)).isEqualTo(Result.success(true));
        assertThat(committedOffset()).isEqualTo(Option.some(100L));
    }

    private ConsumerCursorStore storeFor(List<KVCommand<AetherKey>> sent) {
        return ClusterCursorStore.clusterCursorStore(acceptingLocal(),
                                                     SELF,
                                                     key -> kv.getTyped(key, StreamCursorCheckpointValue.class),
                                                     (stream, partition, group) -> kv.getTyped(ConsumerAssignmentKey.consumerAssignmentKey(stream,
                                                                                                                                           partition,
                                                                                                                                           group),
                                                                                               ConsumerAssignmentValue.class),
                                                     commands -> applyAndRecord(commands, sent),
                                                     () -> false);
    }

    private Promise<Unit> applyAndRecord(List<KVCommand<AetherKey>> commands, List<KVCommand<AetherKey>> sent) {
        sent.addAll(commands);
        kv.process(kv.createBatch(commands));

        return Promise.unitPromise();
    }

    private static StreamCursorCheckpointValue checkpoint(long offset, RewindEpoch rewindEpoch, Epoch ownerEpoch) {
        return StreamCursorCheckpointValue.streamCursorCheckpointValue(offset, TOKEN, rewindEpoch, ownerEpoch);
    }

    private Option<StreamCursorCheckpointValue> checkpointValue() {
        return kv.getTyped(CHECKPOINT_KEY, StreamCursorCheckpointValue.class);
    }

    private Option<Long> committedOffset() {
        return checkpointValue().map(StreamCursorCheckpointValue::committedOffset);
    }

    private Option<List<Object>> committed() {
        return checkpointValue().map(value -> List.<Object>of(value.committedOffset(), value.ownerEpoch()));
    }

    private void put(AetherKey key, AetherValue value) {
        kv.process(kv.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));
    }

    private static ConsumerCursorStore acceptingLocal() {
        record acceptingLocal() implements ConsumerCursorStore {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.none());
            }
        }

        return new acceptingLocal();
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
