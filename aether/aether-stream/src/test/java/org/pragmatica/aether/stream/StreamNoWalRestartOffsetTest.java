// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1441: a restart WITHOUT a WAL must not re-assign offsets already sealed into a durable segment.
///
/// No-WAL streams are reachable: every node built outside `Main` (Forge, Ember, embedded) degrades to no WAL
/// when its WAL directory is unwritable (`AetherNode.resolveStreamWalDir`), and `Main` admits it under
/// `aether.allowNonDurableStreams`. Sealing does not depend on the WAL, so the sealed segments exist. Recovery
/// used to seed the fresh ring only inside WAL replay, so with no WAL the ring restarted at head -1 and the next
/// publish was assigned offset 0 — a second record at an offset the durable tier already holds.
///
/// The scenario is the ticket's probe: no WAL, 5 offsets published, 0..2 evicted and sealed, restart over the
/// same storage with the index rebuilt from refs. The next offset must be 3. The un-sealed 3..4 are lost — that
/// is the non-durable mode's stated trade-off — but they were never in the durable tier, so 3 reuses nothing
/// a reader of the tier can see.
class StreamNoWalRestartOffsetTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final long RING_CAPACITY = 2;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final int PUBLISHED = 5;
    private static final long SEALED_THROUGH = 2;

    @TempDir
    Path storageDir;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private SegmentIndex index;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("nowal-restart");
        storage = StorageInstance.storageInstance("nowal-restart",
                                                  List.of(MemoryTier.memoryTier(ONE_GB),
                                                          LocalDiskTier.localDiskTier(storageDir, ONE_GB).unwrap()),
                                                  metadataStore);
    }

    @AfterEach
    void tearDown() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
        Option.option(storage).onPresent(StorageInstance::shutdown);
    }

    @Test
    void noWalRestart_assignsTheNextOffsetAboveTheSealedFloor() {
        index = new SegmentIndex();
        startOwnerOn(index);
        publish(PUBLISHED);
        awaitSealedThrough(SEALED_THROUGH);

        manager.close();
        index = new SegmentIndex();
        index.rebuildFromRefs(metadataStore);
        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("control: the sealed floor was rebuilt from the refs")
                                                             .isEqualTo(SEALED_THROUGH);
        startOwnerOn(index);

        assertThat(head()).as("the ring is seeded at the sealed floor, not left empty").isEqualTo(SEALED_THROUGH);
        assertThat(publishOne()).as("the next offset is above the sealed floor, never an offset the tier holds")
                                .isEqualTo(SEALED_THROUGH + 1);
    }

    /// Over-seeding guard: a no-WAL stream that never sealed anything still starts at offset 0.
    @Test
    void noWalRestart_withNothingSealed_startsAtZero() {
        index = new SegmentIndex();
        startOwnerOn(index);
        manager.close();
        index = new SegmentIndex();
        index.rebuildFromRefs(metadataStore);
        startOwnerOn(index);

        assertThat(publishOne()).isEqualTo(0L);
    }

    // ---- fixture -------------------------------------------------------------------------------------------
    private void startOwnerOn(SegmentIndex segmentIndex) {
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         segmentSealer(storageSegmentSink(storage, segmentIndex)),
                                         replicationManager(SELF, registry),
                                         Option.none(),
                                         segmentIndex::lastSealedOffset);
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(RING_CAPACITY, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private long head() {
        return manager.partitionBuffer(STREAM, PARTITION)
                      .map(OffHeapRingBuffer::headOffset)
                      .or(Long.MIN_VALUE);
    }

    private long publishOne() {
        return manager.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1L)
                      .onFailure(cause -> fail("publish failed: " + cause.message()))
                      .or(Long.MIN_VALUE);
    }

    private void publish(int count) {
        for (var i = 0; i < count; i++) {
            publishOne();
        }
    }

    /// Sealing runs off the appending thread (#1234); the tier holds the offset only once its seal is indexed.
    private void awaitSealedThrough(long offset) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (index.lastSealedOffset(STREAM, PARTITION) < offset && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("offsets through %d were sealed", offset)
                                                             .isGreaterThanOrEqualTo(offset);
    }
}
