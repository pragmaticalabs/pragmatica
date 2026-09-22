// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationError;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;


/// #1245 (rev1287 M1): the batch write path — `StreamWriteRouter.publishBatch` → `publishLocalBatchAtFloor` — runs
/// the SAME pre-append guards the single write path runs, before the ordered section takes an offset. Two of them
/// were unpinned on the batch path (every other batch test stayed green with either removed): the epoch fence,
/// and the replica-floor VALUE the router passes (`min-sync - 1`, not 0). Each test here reddens under exactly
/// that mutation and asserts the ring is untouched, so a refused run can never be a torn group.
class BatchPublishPreAppendGuardsTest {
    private static final String STREAM = "guarded-batch";
    private static final int PARTITIONS = 2;
    private static final int P = 0;
    private static final long TS = 1L;

    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    /// Replica floor (rev1287 mutation C7: the router passing 0 instead of `min-sync - 1` turns this green-to-red):
    /// `min-sync = 2`, the replication manager refuses any floor of one or more peers. The whole group is refused
    /// BEFORE any append — every outcome is unknown with the floor's own cause, and nothing landed.
    @Test
    void floorRefusal_refusesTheWholeGroupBeforeAppend_everyOutcomeCarriesTheFloorCause() {
        manager = streamPartitionManager(Long.MAX_VALUE, (_, _, _) -> Result.unitResult(), floorRefusing());
        createStream(2);
        var outcomes = StreamWriteRouter.localOnly(manager).publishBatch(STREAM, P, payloads(3), TS).await().unwrap();

        assertThat(outcomes).hasSize(3);
        outcomes.forEach(outcome -> assertThat(outcome).isInstanceOf(PublishOutcome.OutcomeUnknown.class));
        outcomes.forEach(outcome -> assertThat(((PublishOutcome.OutcomeUnknown) outcome).cause()).isEqualTo(ReplicationError.General.NOT_ENOUGH_REPLICAS));
        assertThat(manager.nextExpectedOffset(STREAM, P)).as("a floor refusal leaves nothing in the ring").isZero();
    }

    /// Epoch fence (rev1287 mutation C5: dropping `ensureNotStale` from the batch's admission turns this red): the
    /// partition's ownership high-water is 1:3 and this node stamps ZERO — a deposed writer. The run is fenced
    /// before the section (`StaleEpochAppend`, permanent): every outcome unknown with that cause, nothing
    /// appended, so a deposed owner can never land a torn group.
    @Test
    void staleEpoch_fencesTheWholeRunBeforeAppend() {
        var highWater = OwnershipEpochHighWater.ownershipEpochHighWater(new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                                                                            noopSerializer(),
                                                                                                            noopDeserializer()));

        highWater.advance(OwnershipDomain.streamPartition(STREAM, P), Epoch.epoch(1, 3));
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         EvictionListener.NOOP,
                                         ReplicationManager.NONE,
                                         new StreamPartitionManagerTest.StubClusterNode(StreamPartitionManagerTest.StubApply.SUCCESS),
                                         highWater,
                                         StreamOwnerEpochSource.zero(),
                                         Option.none(),
                                         LastSealedOffsetSource.none(),
                                         DurableSealedOffsetSource.none());
        createStream(1);
        var outcomes = StreamWriteRouter.localOnly(manager).publishBatch(STREAM, P, payloads(3), TS).await().unwrap();

        assertThat(outcomes).hasSize(3);
        outcomes.forEach(outcome -> assertThat(outcome).isInstanceOf(PublishOutcome.OutcomeUnknown.class));
        outcomes.forEach(outcome -> assertThat(((PublishOutcome.OutcomeUnknown) outcome).cause()).isInstanceOf(StreamError.StaleEpochAppend.class));
        assertThat(manager.nextExpectedOffset(STREAM, P)).as("a fenced run leaves nothing in the ring").isZero();
    }

    private void createStream(int minSync) {
        manager.createStream(config(minSync))
               .onFailure(cause -> {
                   throw new AssertionError("createStream: " + cause.message());
               });
    }

    private static List<byte[]> payloads(int count) {
        var list = new ArrayList<byte[]>();

        for (int i = 0; i < count; i++) {
            list.add(("batch-" + i).getBytes(StandardCharsets.UTF_8));
        }

        return list;
    }

    private static StreamConfig config(int minSync) {
        return StreamConfig.streamConfig(STREAM,
                                         PARTITIONS,
                                         RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         3,
                                         minSync,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    /// A replication manager whose pre-append floor refuses any requirement of one or more peers and whose
    /// post-append barrier would accept — so a green here can only come from the floor being skipped or passed as 0.
    private static ReplicationManager floorRefusing() {
        return new ReplicationManager() {
            @Override
            public void replicateEvent(String streamName,
                                       int partition,
                                       long offset,
                                       byte[] payload,
                                       long timestamp,
                                       Epoch ownerEpoch) {}

            @Override
            public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override
            public ReplicaRegistry registry() {
                return ReplicationManager.NONE.registry();
            }

            @Override
            public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
                return Promise.unitPromise();
            }

            @Override
            public long replicatedThrough(String streamName, int partition, int minAcks) {
                return Long.MAX_VALUE;
            }

            @Override
            public long replicatedThrough(ReplicationMessage.ReplicateAck pending, int minAcks) {
                return Long.MAX_VALUE;
            }

            @Override
            public void observeAcks(AckObserver observer) {}

            @Override
            public Result<Unit> ensureReplicaFloor(String streamName, int partition, int minAcks) {
                return minAcks >= 1
                       ? ReplicationError.General.NOT_ENOUGH_REPLICAS.result()
                       : Result.unitResult();
            }
        };
    }

    private static Serializer noopSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer noopDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
