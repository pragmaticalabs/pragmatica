// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1873 round 2 (v1873 B4): the node's owner-side lineage commit, run through the REAL applier. `AetherNode.streamLineageCommit` is
/// where the owner mints an epoch, so its two properties are pinned here and not only on the writer helper it calls: it BUMPS the
/// epoch when the ring was rebuilt, and it is a compare-and-set on the exact record the owner read.
class StreamLineageCommitTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);
    private static final LeaderValue LEADER = new LeaderValue(B, 1L);

    private final KVStore<AetherKey, AetherValue> store = store();

    @Test
    void aRestartedCommit_bumpsTheEpoch_andRecordsTheStartAtTheResumeOffset() {
        var held = record(A, 3L).withEpochStart(5L);

        seedRecord(held);
        commit(held, 2L, true);

        var after = committed().unwrap();

        assertThat(after.ownerEpoch()).as("the ring was rebuilt: a new epoch, not the old one").isNotEqualTo(held.ownerEpoch());
        assertThat(after.lastEpochStart().unwrap().startOffset()).isEqualTo(2L);
        assertThat(after.lastEpochStart().unwrap().epoch()).isEqualTo(after.ownerEpoch());
    }

    @Test
    void aFirstStartCommit_keepsTheEpoch_andRecordsTheStart() {
        var held = record(A, 3L);

        seedRecord(held);
        commit(held, 7L, false);

        var after = committed().unwrap();

        assertThat(after.ownerEpoch()).isEqualTo(held.ownerEpoch());
        assertThat(after.lastEpochStart().unwrap().startOffset()).isEqualTo(7L);
    }

    /// The deposed owner commits against the record it last held, after a failover: refused, the new owner's record stands.
    @Test
    void aCommitAgainstARecordThatMoved_isRefused() {
        var heldByA = record(A, 3L).withEpochStart(5L);
        var failedOverToB = record(B, 4L).withEpochStart(9L);

        seedRecord(failedOverToB);
        commit(heldByA, 0L, true);

        assertThat(committed()).as("the CAS on the exact record refuses the stale owner").isEqualTo(Option.some(failedOverToB));
    }

    @Test
    void withoutACommittedLeader_theCommitFails_andWritesNothing() {
        var held = record(A, 3L);
        var result = AetherNode.streamLineageCommit(Option::none, _ -> Promise.success(List.of()), HlcClock.hlcClock(A))
                               .commit(STREAM, PARTITION, held, 0L, true)
                               .await();

        assertThat(result.isFailure()).isTrue();
    }

    private void commit(StreamPartitionOwnershipValue current, long start, boolean restarted) {
        var result = AetherNode.streamLineageCommit(() -> Option.some(LEADER), commands -> applied(commands), HlcClock.hlcClock(A))
                               .commit(STREAM, PARTITION, current, start, restarted)
                               .await();

        assertThat(result.isSuccess()).isTrue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Promise<List<Object>> applied(List<KVCommand<AetherKey>> commands) {
        store.process(store.createBatch((List) commands));

        return Promise.success(List.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seedRecord(StreamPartitionOwnershipValue record) {
        store.process(store.createBatch(List.of((KVCommand) new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER),
                                                (KVCommand) new KVCommand.Put<>(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION), record))));
    }

    private Option<StreamPartitionOwnershipValue> committed() {
        return store.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION), StreamPartitionOwnershipValue.class);
    }

    private static StreamPartitionOwnershipValue record(NodeId owner, long term) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, GENERATION.withCounter(term), term, HlcTimestamp.ZERO, List.of(owner), 1L);
    }

    private static KVStore<AetherKey, AetherValue> store() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
    }
}
