// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter.CommittedOwnership;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter.HrwOwner;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// #265 increment 6: the reshuffle-ownership BATCH driver. One [ReplicaSetController] reconcile pass
/// flushes its reconciled `(stream, partition)` list to the driver, which decides each pair through the
/// leader-only [StreamPartitionOwnershipWriter] and applies the moved partitions' Puts as ONE consensus
/// batch — bounding the reshuffle fan-out (the promise #265 makes good). This test proves, without a live
/// cluster:
///   1. the moved partitions are applied as a SINGLE apply call carrying one Put per moved partition
///      (observed via a recording applier — the same `Function` seam `AetherNode.driveStreamOwnership` uses);
///   2. that one batch, applied through a REAL [KVStore], delivers a per-entry `ValuePut` notification for
///      every command, so every moved partition's [OwnershipEpochHighWater] domain advances (Q4: a batch is
///      NOT collapsed into a single notification — [KVStore#process] routes one `ValuePut` per `Put`);
///   3. an unchanged partition contributes nothing to the batch and never advances a high-water.
class StreamOwnershipBatchDriverTest {
    private static final NodeId OWNER_A = NodeId.nodeId("core-a").unwrap();
    private static final NodeId OWNER_B = NodeId.nodeId("core-b").unwrap();
    private static final String STREAM = "orders";
    private static final long COMMITTED_TERM = 9L;
    private static final Epoch COMMITTED_EPOCH = Epoch.epoch(COMMITTED_TERM, 1L);
    private static final Epoch TAKEOVER_EPOCH = Epoch.epoch(COMMITTED_TERM, 2L);
    private static final HlcClock CLOCK = HlcClock.hlcClock(new NodeId("core-a"));

    private static StreamPartitionOwnershipValue ownership(NodeId owner, Epoch epoch, long ownershipTerm) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, epoch, ownershipTerm, HlcTimestamp.ZERO);
    }

    /// Committed = OWNER_A at (term, 1) for every partition, so a partition whose HRW owner is OWNER_B is a
    /// genuine move and a partition whose HRW owner is OWNER_A is unchanged.
    private static CommittedOwnership committedAllA() {
        return (stream, partition) -> Option.some(ownership(OWNER_A, COMMITTED_EPOCH, 1L));
    }

    private static HrwOwner hrwByPartition(Map<Integer, NodeId> owners) {
        return (stream, partition) -> Option.option(owners.get(partition));
    }

    private static StreamPartitionOwnershipWriter leaderWriter(CommittedOwnership committed, HrwOwner hrw) {
        return StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> true, () -> COMMITTED_TERM, CLOCK, committed, hrw);
    }

    private static List<PartitionKey> keys(int... partitions) {
        return IntStream.of(partitions)
                        .mapToObj(partition -> PartitionKey.partitionKey(STREAM, partition))
                        .toList();
    }

    private static Promise<List<Object>> record(List<List<KVCommand<AetherKey>>> sink, List<KVCommand<AetherKey>> commands) {
        sink.add(commands);
        return Promise.success(List.<Object>of());
    }

    @Test
    void driver_movedPartitions_areAppliedAsOneBatchOfThreePuts() {
        // Partitions 0,1,2 move A→B; partition 3 is unchanged. The driver (mirrored here) writes the whole
        // pass through the writer and applies the emitted Puts as ONE batch — the recording applier must see
        // exactly ONE apply call carrying the three moved Puts, not one apply per partition.
        var writer = leaderWriter(committedAllA(),
                                  hrwByPartition(Map.of(0, OWNER_B, 1, OWNER_B, 2, OWNER_B, 3, OWNER_A)));
        var applies = new ArrayList<List<KVCommand<AetherKey>>>();
        Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier = commands -> record(applies, commands);

        applier.apply(writer.writeOwnershipChanges(keys(0, 1, 2, 3)));

        assertThat(applies)
            .as("the moved partitions of one reconcile pass are applied as a SINGLE consensus batch")
            .hasSize(1);
        assertThat(applies.getFirst())
            .as("the single batch holds one Put per moved partition (3); the unchanged partition adds nothing")
            .hasSize(3);
    }

    @Test
    void driver_committedBatch_advancesHighWaterForEveryMovedPartition() {
        var writer = leaderWriter(committedAllA(), hrwByPartition(Map.of(0, OWNER_B, 1, OWNER_B, 2, OWNER_B)));
        var batch = writer.writeOwnershipChanges(keys(0, 1, 2));

        var router = MessageRouter.mutable();
        var store = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(store);

        router.addRoute(ValuePut.class, highWater::onStreamPartitionOwnershipPut);

        // Apply the WHOLE moved batch as ONE consensus batch — the exact path clusterNode.apply(list) takes.
        // KVStore.process maps over every command and routes one ValuePut per Put, so all three partitions'
        // high-water domains advance from a single batch (Q4: per-entry notifications, not one-for-all).
        store.process(store.createBatch(batch));

        assertThat(highWater.highWater(OwnershipDomain.streamPartition(STREAM, 0))).isEqualTo(Option.some(TAKEOVER_EPOCH));
        assertThat(highWater.highWater(OwnershipDomain.streamPartition(STREAM, 1))).isEqualTo(Option.some(TAKEOVER_EPOCH));
        assertThat(highWater.highWater(OwnershipDomain.streamPartition(STREAM, 2))).isEqualTo(Option.some(TAKEOVER_EPOCH));
        assertThat(highWater.highWater(OwnershipDomain.streamPartition(STREAM, 9)))
            .as("a partition absent from the batch never advances")
            .isEqualTo(Option.none());
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
                return null;
            }
        };
    }
}
