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

        var result = AetherNode.streamLineageCommit(() -> Option.some(LEADER), commands -> applied(commands), HlcClock.hlcClock(A))
                               .commit(STREAM, PARTITION, heldByA, 0L, true)
                               .await();

        assertThat(result.isFailure()).as("#1976: the refusal is the commit's own result").isTrue();

        assertThat(committed()).as("the CAS on the exact record refuses the stale owner").isEqualTo(Option.some(failedOverToB));
    }

    /// #1976: the applier answers a refused guarded write with a result, and the old record already names a start (a
    /// restart). The commit must FAIL on the write's own result; a read-back would find that start and report it landed.
    @Test
    void aRefusedRestartCommit_failsOnItsOwnResult_evenThoughTheRecordStillNamesAStart() {
        var held = record(A, 3L).withEpochStart(5L);
        var moved = held.withIsr(List.of(A, B));

        seedRecord(moved);

        var result = AetherNode.streamLineageCommit(() -> Option.some(LEADER), commands -> applied(commands), HlcClock.hlcClock(A))
                               .commit(STREAM, PARTITION, held, 2L, true)
                               .await();

        assertThat(result.isFailure()).as("refused: the record moved under the owner").isTrue();
        assertThat(committed()).as("nothing landed").isEqualTo(Option.some(moved));
        assertThat(committed().unwrap().lastEpochStart().isPresent()).as("the read-back that would have fooled the activation").isTrue();
    }

    /// #1976, end to end through the real [org.pragmatica.aether.stream.OwnerActivation]: a competing ownership write (an ISR
    /// change) lands between the owner's read and its restart commit. Red when the refusal is discarded: the activation
    /// succeeds and latches the ring incarnation, so the restart is never retried. Green: it fails, then retries against
    /// the new record and commits the restart.
    @Test
    void aRefusedRestartCommit_doesNotLatchTheActivation_andIsRetried() {
        var held = record(A, 3L).withEpochStart(5L);
        var competing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var activation = org.pragmatica.aether.stream.OwnerActivation.ownerActivation(A,
                                                                                    (_, _) -> committed(),
                                                                                    (_, _) -> true,
                                                                                    Option.none(),
                                                                                    () -> List.of(A),
                                                                                    (_, _, _) -> Promise.success(-1L),
                                                                                    (_, _) -> 2L,
                                                                                    (_, _, _, _) -> Promise.success(0L),
                                                                                    () -> true,
                                                                                    (_, _, _, _, _) -> Promise.success(List.of()),
                                                                                    _ -> org.pragmatica.lang.Unit.unit(),
                                                                                    org.pragmatica.lang.io.TimeSpan.timeSpan(1).hours(),
                                                                                    (_, _) -> 1L,
                                                                                    AetherNode.streamLineageCommit(() -> Option.some(LEADER),
                                                                                                                   commands -> competingThenApplied(competing, commands),
                                                                                                                   HlcClock.hlcClock(A)));

        seedRecord(held);

        assertThat(activation.activate(STREAM, PARTITION).await().isFailure()).as("the refused restart fails the activation").isTrue();
        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
        assertThat(committed().unwrap().ownerEpoch()).as("no restart landed").isEqualTo(held.ownerEpoch());

        assertThat(activation.activate(STREAM, PARTITION).await().isSuccess()).as("the retry commits against the moved record").isTrue();
        assertThat(committed().unwrap().ownerEpoch()).as("the restart landed: a new epoch").isNotEqualTo(held.ownerEpoch());
        assertThat(activation.isActivated(STREAM, PARTITION)).isTrue();
    }

    @Test
    void withoutACommittedLeader_theCommitFails_andWritesNothing() {
        var held = record(A, 3L);
        var result = AetherNode.streamLineageCommit(Option::none, _ -> Promise.success(List.of()), HlcClock.hlcClock(A))
                               .commit(STREAM, PARTITION, held, 0L, true)
                               .await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).as("a quorum condition, not a refusal of this commit")
                                                  .isEqualTo(org.pragmatica.aether.stream.OwnerActivation.ActivationError.NO_COMMITTED_LEADER));
    }

    private void commit(StreamPartitionOwnershipValue current, long start, boolean restarted) {
        var result = AetherNode.streamLineageCommit(() -> Option.some(LEADER), commands -> applied(commands), HlcClock.hlcClock(A))
                               .commit(STREAM, PARTITION, current, start, restarted)
                               .await();

        assertThat(result.isSuccess()).isTrue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Promise<List<Object>> applied(List<KVCommand<AetherKey>> commands) {
        List<Object> results = store.process(store.createBatch((List) commands));

        return Promise.success(results);
    }

    /// The first apply is preceded by a competing ownership write (an ISR change) that moves the record.
    private Promise<List<Object>> competingThenApplied(java.util.concurrent.atomic.AtomicBoolean competing, List<KVCommand<AetherKey>> commands) {
        if (competing.compareAndSet(true, false)) {
            seedRecord(committed().unwrap().withIsr(List.of(A, B)));
        }

        return applied(commands);
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
