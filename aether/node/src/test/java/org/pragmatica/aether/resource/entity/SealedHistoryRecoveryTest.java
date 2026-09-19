// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;

/// #1240: a durable entity partition whose checkpoint lags further behind than its ring reaches must be
/// rebuilt from the history this node sealed to storage, not refused. Reads cross from sealed segments
/// into the ring at the ring's earliest offset with no gap and no duplicate; a sealed history that is
/// itself broken — an offset skipped, an offset repeated, a hole between segments — is refused, never
/// folded around.
///
/// Lives in the entity package so it can drive the package-private [EntityFold] against the REAL node
/// substrate, partition manager, sealer and segment storage — the whole recovery path, stubbing nothing
/// but consensus KV (which holds no checkpoint here, deliberately).
class SealedHistoryRecoveryTest {
    private static final String KEYSPACE = "orders";
    private static final String STREAM = EntityPartitionArc.arcName(KEYSPACE);
    private static final int PARTITION = 0;
    /// More than the entity ring's 10,000-record capacity, so the oldest records leave the ring.
    private static final int RECORDS = 12_000;
    private static final int READ_BATCH = 512;
    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final long MANAGER_BUDGET_BYTES = 512L * 1024 * 1024;
    private static final long SEAL_WAIT_MILLIS = 10_000;

    private StorageInstance storage;
    private SegmentIndex index;

    @BeforeEach
    void setUpStorage() {
        storage = StorageInstance.storageInstance("entity-sealed-history", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
    }

    /// The production shape: the global segment sealer takes every record the ring evicts and seals it to
    /// storage; no checkpoint was ever written.
    @Nested
    class RecoveryFromSealedHistory {
        private SegmentSealer sealer;
        private StreamPartitionManager partitionManager;
        private EntityLogSubstrate substrate;

        @BeforeEach
        void setUp() throws InterruptedException {
            sealer = SegmentSealer.segmentSealer(StorageSegmentSink.storageSegmentSink(storage, index));
            partitionManager = StreamPartitionManager.streamPartitionManager(MANAGER_BUDGET_BYTES,
                                                                             sealer,
                                                                             Option.none(),
                                                                             index::lastSealedOffset);
            substrate = substrate(partitionManager);
            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
            awaitAllSealed(sealer);
        }

        @Test
        void ready_rebuildsEveryKeyFromSealedHistory_whenHistoryHasLeftTheRingAndNoCheckpointExists() {
            assertThat(partitionManager.earliestRetainedOffset(STREAM, PARTITION)).as("control: the oldest records must have left the ring, or this test never reaches sealed storage")
                                                                                 .isGreaterThan(0L);

            var fold = EntityFold.entityFold(KEYSPACE, substrate);

            fold.ready(PARTITION)
                .await()
                .onFailure(cause -> fail("rebuild must succeed from sealed history: " + cause.message()));

            var missing = LongStream.range(0, RECORDS)
                                    .filter(offset -> !fold.get(PARTITION, key(offset))
                                                           .map(state -> new String(state, StandardCharsets.UTF_8))
                                                           .map(value(offset)::equals)
                                                           .or(false))
                                    .count();

            assertThat(missing).as("every key written must be in the rebuilt fold with its value").isZero();
        }

        @Test
        void read_returnsEveryOffsetExactlyOnce_acrossTheSealedToRingBoundary() {
            var ringEarliest = partitionManager.earliestRetainedOffset(STREAM, PARTITION);
            var keys = readAllKeys(substrate, substrate.headOffset(KEYSPACE, PARTITION));

            assertThat(ringEarliest).as("control: the read must actually cross a boundary").isGreaterThan(0L);
            assertThat(keys).containsExactlyElementsOf(LongStream.range(0, RECORDS)
                                                                 .mapToObj(SealedHistoryRecoveryTest::key)
                                                                 .toList());
        }

        @Test
        void earliestRetainedOffset_reachesBackIntoSealedHistory() {
            assertThat(substrate.earliestRetainedOffset(KEYSPACE, PARTITION)).isZero();
        }
    }

    /// Sealed history that is itself broken. The ring's evictions are dropped here, and the segments
    /// covering the evicted range are written by hand so each defect can be placed exactly.
    @Nested
    class BrokenSealedHistory {
        private StreamPartitionManager partitionManager;
        private EntityLogSubstrate substrate;
        private long ringEarliest;

        @BeforeEach
        void setUp() {
            partitionManager = StreamPartitionManager.streamPartitionManager(MANAGER_BUDGET_BYTES,
                                                                             EvictionListener.NOOP,
                                                                             Option.none(),
                                                                             index::lastSealedOffset);
            substrate = substrate(partitionManager);
            substrate.ensureLog(KEYSPACE, 1, 1, 1).unwrap();
            appendRecords(substrate);
            ringEarliest = partitionManager.earliestRetainedOffset(STREAM, PARTITION);
        }

        @Test
        void read_crossesIntoTheRing_whenTheSealedHistoryIsIntact() {
            sealOffsets(0, ringEarliest - 1, LongStream.range(0, ringEarliest).boxed().toList());

            assertThat(readAllKeys(substrate, substrate.headOffset(KEYSPACE, PARTITION))).containsExactlyElementsOf(LongStream.range(0, RECORDS)
                                                                                                                        .mapToObj(SealedHistoryRecoveryTest::key)
                                                                                                                        .toList());
        }

        @Test
        void read_refusesTheBatch_whenASealedSegmentSkipsAnOffset() {
            var skipped = new ArrayList<>(LongStream.range(0, ringEarliest).boxed().toList());

            skipped.remove(Long.valueOf(5));
            sealOffsets(0, ringEarliest - 1, skipped);

            var read = substrate.read(KEYSPACE, PARTITION, 0, READ_BATCH).await();

            assertThat(read.isFailure()).as("a skipped offset must be refused, not folded as if the next record were it")
                                        .isTrue();
        }

        @Test
        void read_refusesTheBatch_whenASealedSegmentRepeatsAnOffset() {
            var repeated = new ArrayList<>(LongStream.range(0, ringEarliest).boxed().toList());

            repeated.add(6, 5L);
            sealOffsets(0, ringEarliest - 1, repeated);

            var read = substrate.read(KEYSPACE, PARTITION, 0, READ_BATCH).await();

            assertThat(read.isFailure()).as("a repeated offset must be refused, not applied twice").isTrue();
        }

        @Test
        void ready_refusesLoudly_whenTheSealedHistoryHasAHole() {
            sealOffsets(0, 99, LongStream.range(0, 100).boxed().toList());
            sealOffsets(200, ringEarliest - 1, LongStream.range(200, ringEarliest).boxed().toList());

            var result = EntityFold.entityFold(KEYSPACE, substrate)
                                   .ready(PARTITION)
                                   .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(EntityLogError.FoldFailed.class));
        }

        /// A segment claiming `[start, end]` whose CONTENT is the given offsets, in the given order.
        private void sealOffsets(long start, long end, List<Long> offsets) {
            StorageSegmentSink.storageSegmentSink(storage, index)
                              .seal(sealedSegment(STREAM, PARTITION, start, end, offsets.size(), 0L, 0L, serialize(offsets)))
                              .await()
                              .unwrap();
        }
    }

    private EntityLogSubstrate substrate(StreamPartitionManager partitionManager) {
        return StreamEntityLogSubstrate.streamEntityLogSubstrate(partitionManager,
                                                                 (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(1,
                                                                                                                                       true),
                                                                 TieredStreamReader.tieredStreamReader(index, storage),
                                                                 storage,
                                                                 emptyKvStore(),
                                                                 _ -> Promise.success(List.of()));
    }

    private static void appendRecords(EntityLogSubstrate substrate) {
        for (long offset = 0; offset < RECORDS; offset++) {
            substrate.append(KEYSPACE, PARTITION, record(offset)).await().unwrap();
        }
    }

    private static void awaitAllSealed(SegmentSealer sealer) throws InterruptedException {
        var deadline = System.currentTimeMillis() + SEAL_WAIT_MILLIS;

        while (sealer.pendingBytes() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(sealer.pendingBytes()).as("every evicted record sealed").isZero();
    }

    /// Read `[0, head]` the way a fold does — batch after batch from the offset after the last record
    /// returned — decoding each record's key, so a gap or a duplicate shows up as a misaligned key.
    private static List<String> readAllKeys(EntityLogSubstrate substrate, long head) {
        var keys = new ArrayList<String>();
        var from = 0L;

        while (from <= head) {
            var batch = substrate.read(KEYSPACE, PARTITION, from, READ_BATCH)
                                 .await()
                                 .unwrap();

            assertThat(batch).as("read at %d below head %d", from, head).isNotEmpty();
            batch.forEach(raw -> keys.add(EntityLogRecord.decode(raw).unwrap().key()));
            from += batch.size();
        }

        return keys;
    }

    private static byte[] record(long offset) {
        return EntityLogRecord.upsert(key(offset), value(offset).getBytes(StandardCharsets.UTF_8)).encode();
    }

    private static String key(long offset) {
        return "k-" + offset;
    }

    private static String value(long offset) {
        return "v-" + offset;
    }

    /// Segment payload in the sealer's format: `[offset:8][timestamp:8][len:4][data:len]` per record.
    private static byte[] serialize(List<Long> offsets) {
        var records = offsets.stream().map(SealedHistoryRecoveryTest::record).toList();
        var size = records.stream().mapToInt(data -> Long.BYTES + Long.BYTES + Integer.BYTES + data.length).sum();
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < offsets.size(); i++) {
            buffer.putLong(offsets.get(i));
            buffer.putLong(0L);
            buffer.putInt(records.get(i).length);
            buffer.put(records.get(i));
        }

        return buffer.array();
    }

    private static KVStore<AetherKey, AetherValue> emptyKvStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
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
