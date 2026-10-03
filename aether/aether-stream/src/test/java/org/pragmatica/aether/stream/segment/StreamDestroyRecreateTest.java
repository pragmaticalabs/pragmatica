// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1278 review (blocking): a destroyed stream must take its durable footprint with it. A stream retention reclaimed
/// leaves a reclaimed-through floor; before the fix destroy left that floor (and the segment refs) in storage, so a
/// stream created again under the same name, after a restart, rebuilt its sealed watermark at the OLD floor and its
/// own fresh records were placed above it — or, recovering a WAL that starts at 0, dropped as "already sealed".
///
/// Also: a destroy a crash interrupted — the tombstone is on disk with some refs still present — hides every surviving
/// ref of the old stream from a rebuild, and is finished before the stream materializes again.
class StreamDestroyRecreateTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final int RING_EVENTS = 4;
    private static final int FIRST_LIFE = 60;
    private static final int SECOND_LIFE = 10;

    @TempDir
    Path walDir;

    @TempDir
    Path storageDir;

    private final MetadataStore metadata = MetadataStore.inMemoryMetadataStore("streams");

    @Test
    void destroyThenRecreate_afterRestart_startsFromNothing_andRecoversEveryOwnRecord() {
        var storage = storage();
        var firstIndex = new SegmentIndex();
        var first = manager(storage, firstIndex);

        createStream(first);
        publish(first, FIRST_LIFE);
        awaitSealed(firstIndex);
        reclaimEverything(storage, firstIndex);
        assertThat(firstIndex.reclaimedThrough(STREAM, PARTITION)).as("fixture: retention left a floor").isPositive();

        first.destroyStream(STREAM).onFailure(cause -> fail(cause.message()));
        first.close();
        assertThat(refsOf(STREAM)).as("destroy dropped every durable ref of the stream").isEmpty();

        var secondIndex = rebuilt();

        assertThat(secondIndex.lastSealedOffset(STREAM, PARTITION)).as("the recreated stream inherits no watermark").isEqualTo(-1L);

        var second = manager(storage, secondIndex);

        createStream(second);
        var offsets = publish(second, SECOND_LIFE);
        second.close();

        assertThat(offsets).as("the recreated stream starts at offset 0").startsWith(0L);

        var recoveredIndex = rebuilt();
        var recovered = manager(storage, recoveredIndex);

        createStream(recovered);
        var info = recovered.partitionInfo(STREAM, PARTITION).onFailure(cause -> fail(cause.message())).unwrap();
        var ringEvents = recovered.readLocal(STREAM, PARTITION, info.tailOffset(), 100)
                                  .onFailure(cause -> fail(cause.message()))
                                  .unwrap();

        recovered.close();
        assertThat(info.headOffset()).as("every own record recovered: head at the last offset published").isEqualTo(SECOND_LIFE - 1L);
        assertThat(recoveredIndex.lastSealedOffset(STREAM, PARTITION)).as("only its own sealed prefix anchors recovery, never the old floor")
                                                                      .isLessThan(info.tailOffset());
        assertThat(ringEvents).extracting(RawEvent::offset)
                              .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(info.tailOffset(), SECOND_LIFE - 1L)
                                                                                    .boxed()
                                                                                    .toList());
    }

    @Test
    void rebuild_aTombstonedStream_ignoresEverySurvivingRef_andTheDestroyIsFinishedBeforeRecreate() {
        var block = BlockId.blockId(new byte[]{1}).unwrap();

        metadata.putRef("streams/" + STREAM + "/0/0-99", block);
        metadata.putRef(SegmentIndex.floorRefName(STREAM, PARTITION, 199), block);
        metadata.putRef(SegmentIndex.tombstoneRefName(STREAM), block);

        assertThat(rebuilt().lastSealedOffset(STREAM, PARTITION)).as("a half-destroyed stream anchors nothing").isEqualTo(-1L);

        var storage = storage();
        var index = rebuilt();
        var manager = manager(storage, index);

        createStream(manager);
        manager.close();

        assertThat(refsOf(STREAM)).as("the interrupted destroy was finished before the stream materialized again").isEmpty();
        assertThat(metadata.resolveRef(SegmentIndex.tombstoneRefName(STREAM)).isPresent()).isFalse();
    }

    private StorageInstance storage() {
        return StorageInstance.storageInstance("streams",
                                               List.of(MemoryTier.memoryTier(ONE_GB),
                                                       LocalDiskTier.localDiskTier(storageDir, ONE_GB).unwrap()),
                                               metadata);
    }

    private StreamPartitionManager manager(StorageInstance storage, SegmentIndex index) {
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(storageSegmentSink(storage, index)),
                                             Option.some(walDir),
                                             index::lastSealedOffset);

        manager.streamFootprint(StreamFootprint.streamFootprint(storage, metadata, index, RetentionEnforcer.FloorDurability.LIVE));

        return manager;
    }

    private SegmentIndex rebuilt() {
        var index = new SegmentIndex();

        index.rebuildFromRefs(metadata);

        return index;
    }

    private Map<String, BlockId> refsOf(String stream) {
        var refs = new java.util.HashMap<>(metadata.listAllRefs());

        refs.keySet()
            .removeIf(ref -> !ref.contains("/" + stream + "/") && !ref.endsWith("/" + stream));

        return refs;
    }

    private static void reclaimEverything(StorageInstance storage, SegmentIndex index) {
        RetentionEnforcer.retentionEnforcer(storage, index, 1L)
                         .enforceNow()
                         .await()
                         .onFailure(cause -> fail(cause.message()));
    }

    private static void createStream(StreamPartitionManager manager) {
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(RING_EVENTS, 1024 * 1024, 600_000),
                                                       "earliest"))
               .onFailure(cause -> fail(cause.message()));
    }

    private static List<Long> publish(StreamPartitionManager manager, int count) {
        var offsets = new java.util.ArrayList<Long>();

        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, ("e-" + i).getBytes(UTF_8), 1000L + i)
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(offsets::add);
        }

        return offsets;
    }

    private static void awaitSealed(SegmentIndex index) {
        var deadline = System.nanoTime() + 10_000_000_000L;

        while (index.lastSealedOffset(STREAM, PARTITION) < FIRST_LIFE - RING_EVENTS - 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(index.lastSealedOffset(STREAM, PARTITION)).as("fixture: sealed").isGreaterThanOrEqualTo(FIRST_LIFE - RING_EVENTS - 1);
    }
}
