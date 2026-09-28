// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.RetentionEnforcer.retentionEnforcer;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1604: a segment rebuilt from its ref name after a restart has no timestamp, so the age policy could never
/// reclaim it and every restart ratcheted the disk tier up. Retention now reads the segment's age from its own
/// block -- the events' time, as a live seal records it -- so pre-restart segments age out, and never early:
/// the age is the events' time, not the (possibly earlier) creation time of a block shared through dedup.
class RetentionAgeAfterRestartTest {
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long WINDOW_MS = 60 * 60 * 1000L;

    @Test
    void afterRestart_segmentsWhoseEventsAreOlderThanTheWindow_areReclaimed() {
        var storage = storage();
        var eventTime = System.currentTimeMillis() - 2 * WINDOW_MS;

        sealBothPartitions(storage, eventTime);

        var rebuilt = restartedIndex(storage);
        var removed = enforcerOver(storage, rebuilt).enforceNow().await().unwrap();

        assertThat(removed).as("both pre-restart segments aged out by their events' time").isEqualTo(2);
        assertThat(storage.resolveRef("streams/orders/0/0-2").isPresent()).isFalse();
        assertThat(storage.resolveRef("streams/audit/0/0-2").isPresent()).isFalse();
    }

    @Test
    void afterRestart_segmentsWhoseEventsAreInsideTheWindow_areKept() {
        var storage = storage();
        var eventTime = System.currentTimeMillis() - WINDOW_MS / 2;

        sealBothPartitions(storage, eventTime);

        var rebuilt = restartedIndex(storage);
        var removed = enforcerOver(storage, rebuilt).enforceNow().await().unwrap();

        assertThat(removed).isZero();
        assertThat(rebuilt.listSegments("orders", 0)).singleElement()
                                                     .extracting(SegmentIndex.SegmentRef::maxTimestamp)
                                                     .as("the age was learned from the block and kept in the index")
                                                     .isEqualTo(eventTime);
    }

    /// A ref whose block cannot be read keeps its age unknown and is withheld -- never reclaimed on a guess.
    @Test
    void afterRestart_aSegmentWhoseBlockCannotBeRead_isWithheld() {
        var storage = storage();

        storage.createRef("streams/orders/0/0-2", BlockId.blockId(new byte[]{9}).unwrap()).await();

        var rebuilt = restartedIndex(storage);
        var removed = enforcerOver(storage, rebuilt).enforceNow().await().unwrap();

        assertThat(removed).isZero();
        assertThat(storage.resolveRef("streams/orders/0/0-2").isPresent()).isTrue();
    }

    private static StorageInstance storage() {
        return StorageInstance.storageInstance("age", List.of(MemoryTier.memoryTier(ONE_GB)));
    }

    /// Identical events in two streams: one content-addressed block, two refs.
    private static void sealBothPartitions(StorageInstance storage, long eventTime) {
        var sink = storageSegmentSink(storage, new SegmentIndex());
        var bytes = encoded(eventTime, "a", "b", "c");

        sink.seal(sealedSegment("orders", 0, 0, 2, 3, eventTime, eventTime, bytes)).await();
        sink.seal(sealedSegment("audit", 0, 0, 2, 3, eventTime, eventTime, bytes)).await();
        assertThat(storage.resolveRef("streams/orders/0/0-2")).isEqualTo(storage.resolveRef("streams/audit/0/0-2"));
    }

    /// What a restart sees: the index rebuilt from ref NAMES, every timestamp unknown.
    private static SegmentIndex restartedIndex(StorageInstance storage) {
        var index = new SegmentIndex();

        index.rebuildFromRefs(Map.of("streams/orders/0/0-2",
                                               storage.resolveRef("streams/orders/0/0-2").or(BlockId.blockId(new byte[]{0}).unwrap()),
                                               "streams/audit/0/0-2",
                                               storage.resolveRef("streams/audit/0/0-2").or(BlockId.blockId(new byte[]{0}).unwrap())));
        assertThat(index.listSegments("orders", 0)).allSatisfy(ref -> assertThat(ref.maxTimestamp()).isZero());

        return index;
    }

    private static RetentionEnforcer enforcerOver(StorageInstance storage, SegmentIndex index) {
        return retentionEnforcer(storage,
                                 index,
                                 WINDOW_MS,
                                 RetentionEnforcer.SegmentRetentionFloor.NONE,
                                 SegmentReader.segmentReader(storage, index));
    }

    private static byte[] encoded(long eventTime, String... payloads) {
        var size = 0;

        for (var payload : payloads) {
            size += 20 + payload.length();
        }

        var buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        for (var offset = 0; offset < payloads.length; offset++) {
            var data = payloads[offset].getBytes(StandardCharsets.UTF_8);

            buffer.putLong(offset).putLong(eventTime).putInt(data.length).put(data);
        }

        return buffer.array();
    }
}
