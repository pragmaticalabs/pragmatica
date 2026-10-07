// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

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
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1873 (design Q4, load-bearing): the owner mints an epoch itself when its ring was rebuilt. That is safe only because the
/// write is a compare-and-set on the EXACT record the owner read. A deposed owner bumping the term it last saw lands on the SAME
/// epoch the new owner already holds (`term + 1` of the same generation), which the applier's strictly-older fence does not
/// refuse: only the exact-record witness does.
class OwnerBumpGuardedByWitnessTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);
    private static final LeaderValue LEADER = new LeaderValue(B, 1L);

    @Test
    void aDeposedOwnersBump_ofTheRecordItLastHeld_isRefusedByTheApplier() {
        var store = store();
        var heldByA = record(A, 3L).withEpochStart(5L);
        var failedOverToB = record(B, 4L).withEpochStart(9L);
        var deposedBump = heldByA.restarted(0L, HlcTimestamp.ZERO);

        assertThat(deposedBump.ownerEpoch()).as("fixture: the deposed bump lands on the new owner's epoch, which the strictly-older fence admits")
                                            .isEqualTo(failedOverToB.ownerEpoch());

        seed(store, new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(store, StreamPartitionOwnershipWriter.guardedOwnershipWrite(LEADER, STREAM, PARTITION, Option.none(), heldByA));
        seed(store, StreamPartitionOwnershipWriter.guardedOwnershipWrite(LEADER, STREAM, PARTITION, Option.some(heldByA), failedOverToB));
        assertThat(committed(store)).as("control: the failover committed").isEqualTo(Option.some(failedOverToB));

        seed(store, StreamPartitionOwnershipWriter.guardedOwnershipWrite(LEADER, STREAM, PARTITION, Option.some(heldByA), deposedBump));

        assertThat(committed(store)).as("the deposed owner's bump is refused: B still owns and its start stands")
                                    .isEqualTo(Option.some(failedOverToB));
    }

    /// Control, so the refusal above is the witness and not a store that refuses everything: the CURRENT owner's bump of the
    /// record it read applies.
    @Test
    void theCurrentOwnersBump_ofTheRecordItReads_isApplied() {
        var store = store();
        var heldByB = record(B, 4L).withEpochStart(9L);
        var bumped = heldByB.restarted(7L, HlcTimestamp.ZERO);

        seed(store, new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(store, StreamPartitionOwnershipWriter.guardedOwnershipWrite(LEADER, STREAM, PARTITION, Option.none(), heldByB));
        seed(store, StreamPartitionOwnershipWriter.guardedOwnershipWrite(LEADER, STREAM, PARTITION, Option.some(heldByB), bumped));

        assertThat(committed(store)).isEqualTo(Option.some(bumped));
        assertThat(committed(store).unwrap().lastEpochStart().unwrap().startOffset()).isEqualTo(7L);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void seed(KVStore<AetherKey, AetherValue> store, KVCommand command) {
        store.process(store.createBatch(List.of(command)));
    }

    private static Option<StreamPartitionOwnershipValue> committed(KVStore<AetherKey, AetherValue> store) {
        return store.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION),
                              StreamPartitionOwnershipValue.class);
    }

    private static StreamPartitionOwnershipValue record(NodeId owner, long term) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                           GENERATION.withCounter(term),
                                                                           term,
                                                                           HlcTimestamp.ZERO,
                                                                           List.of(owner),
                                                                           1L);
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
