// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.segment.RefDurability;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1873 (KIP-320), the v1862 r2 probe over the REAL stack except the cluster: a no-WAL owner publishes 0..4 (sealing 0..2 into
/// the durable tier), a consumer reads all five under E1, the owner restarts (its ring is rebuilt from the sealed floor, head 2)
/// and begins epoch E2 at offset 3 (the record the restarted owner commits), then assigns 3 and 4 to NEW records (the ring holds two, so nothing new is evicted). Before the
/// epoch check the consumer's cursor (5) sat past the new head and it silently skipped the records at 3 and 4. Now the owner's
/// read answers the typed divergence and the consumer re-reads from 3: `new-0` at 3 and `new-1` at 4.
class NoWalRestartConsumerRedeliveryTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);

    @TempDir
    Path storageDir;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private SegmentIndex index;
    private final AtomicReference<StreamPartitionManager> owner = new AtomicReference<>();
    private final AtomicReference<StreamPartitionOwnershipValue> committed = new AtomicReference<>();
    private StreamPartitionManager wakeChannel;
    private StreamConsumerRuntime consumer;
    private final List<String> delivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("redelivery");
        storage = StorageInstance.storageInstance("redelivery",
                                                  List.of(MemoryTier.memoryTier(ONE_GB), LocalDiskTier.localDiskTier(storageDir, ONE_GB).unwrap()),
                                                  metadataStore);
        wakeChannel = streamPartitionManager();
        wakeChannel.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        Option.option(consumer).onPresent(StreamConsumerRuntime::close);
        Option.option(owner.get()).onPresent(StreamPartitionManager::close);
        wakeChannel.close();
        storage.shutdown();
    }

    @Test
    void consumerPastTheRestartedOwnersHead_isToldWhereTheNewEpochBegan_andReadsTheNewRecords() throws InterruptedException {
        index = new SegmentIndex();
        startOwner();
        committed.set(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, E1, 1L, HlcTimestamp.ZERO, List.of(SELF), 1L).withEpochStart(0L));
        publish("old", 5);
        awaitSealedThrough(2L);

        // 0..2 left the ring when they were sealed, so the group resumes from its checkpoint: cursor 3, read under E1.
        consumer = StreamConsumerRuntime.streamConsumerRuntime(wakeChannel, DeadLetterHandler.deadLetterHandler(), checkpointAt(3L, E1), ownerReader());
        consumer.subscribe(STREAM,
                           PARTITION,
                           ConsumerConfig.consumerConfig("group-1"),
                           (offset, payload, ts) -> {
                               delivered.add(offset + ":" + new String(payload, UTF_8));

                               return Promise.unitPromise();
                           },
                           StreamConsumerRuntime.IdlePolicy.REAP_WHEN_IDLE,
                           FENCE);
        keepWaking(() -> delivered.size() >= 2);
        assertThat(delivered).as("control: the consumer read the old lineage").containsExactly("3:old-3", "4:old-4");

        owner.get().close();
        index = new SegmentIndex();
        index.rebuildFromRefs(metadataStore);
        startOwner();
        committed.set(committed.get().restarted(3L, HlcTimestamp.ZERO));
        assertThat(owner.get().partitionInfo(STREAM, PARTITION).map(StreamPartitionManager.PartitionInfo::headOffset).or(-9L)).as("control: the ring restarted at the sealed floor").isEqualTo(2L);
        publish("new", 2);
        keepWaking(() -> delivered.size() >= 4);

        assertThat(delivered.subList(2, delivered.size())).as("the records at the re-assigned offsets are delivered, not skipped")
                                                           .startsWith("3:new-0", "4:new-1");
    }

    private StreamConsumerRuntime.PartitionReader ownerReader() {
        return new StreamConsumerRuntime.PartitionReader() {
            @Override
            public Promise<List<OffHeapRingBuffer.RawEvent>> read(String stream, int partition, long from, int max) {
                throw new AssertionError("a consumer reads through readFrom");
            }

            @Override
            public Promise<StreamPartitionManager.EpochRead> readFrom(String stream, int partition, long from, int max, Epoch consumerEpoch) {
                return owner.get().readServing(stream, partition, from, max, consumerEpoch).async();
            }
        };
    }

    private void startOwner() {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(storageSegmentSink(storage, index, RefDurability.LIVE)),
                                             replicationManager(SELF, registry),
                                             Option.none(),
                                             index::lastSealedOffset);

        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
        manager.ownershipRecords((_, _) -> Option.option(committed.get()));
        owner.set(manager);
    }

    private void publish(String prefix, int count) {
        for (var i = 0; i < count; i++) {
            owner.get().publishLocal(STREAM, PARTITION, (prefix + "-" + i).getBytes(UTF_8), 1000L + i).onFailure(cause -> fail(cause.message()));
        }
    }

    private void awaitSealedThrough(long offset) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (index.lastSealedOffset(STREAM, PARTITION) < offset && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(index.lastSealedOffset(STREAM, PARTITION)).isGreaterThanOrEqualTo(offset);
    }

    /// The consumer's wake-up ring is a separate local one, so appends there wake it; the owner is read through the reader.
    private void keepWaking(java.util.function.BooleanSupplier done) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;
        var tick = 0L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            wakeChannel.publishLocal(STREAM, PARTITION, ("wake-" + tick++).getBytes(UTF_8), tick);
            Thread.sleep(50);
        }
    }

    private static final ConsumerFence FENCE = new ConsumerFence() {
        @Override
        public Epoch epoch() {
            return Epoch.epoch(1L, 1L, 9L);
        }

        @Override
        public boolean admitted() {
            return true;
        }
    };

    private static org.pragmatica.aether.stream.segment.ConsumerCursorStore checkpointAt(long offset, Epoch ownerEpoch) {
        return new org.pragmatica.aether.stream.segment.ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long committedOffset) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.none());
            }

            @Override
            public Promise<Option<Cursor>> fetchCursor(String group, String stream, int partition, Epoch assignmentEpoch) {
                return Promise.success(Option.some(Cursor.cursor(offset, org.pragmatica.aether.slice.generation.RewindEpoch.NONE, ownerEpoch)));
            }
        };
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(2, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
