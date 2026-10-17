// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// Learning the age of pre-restart segments runs its batches of [RetentionEnforcer#AGE_READ_CONCURRENCY] reads as a
/// LOOP: a batch whose reads are already settled (a memory tier answers synchronously) is consumed in place, so the
/// stack depth at a read does not grow with the number of segments. It used to nest 6 frames per batch through
/// `flatMap` continuations (measured): after a restart every segment is unknown-aged, and 25,000 pending segments
/// was about 19,000 frames. The bound is relative, 3,200 segments against 16.
class RetentionAgeLearningDepthTest {
    private static final long WINDOW_MS = 60 * 60 * 1000L;
    private static final int SLACK_FRAMES = 60;

    @Test
    @Timeout(120)
    void stackDepthAtAReadDoesNotGrowWithTheSegmentCount_andEveryAgeIsLearned() {
        var shallow = learnOver(16);
        var deep = learnOver(3_200);

        assertThat(deep.learned()).as("every pending segment learned its age").isEqualTo(3_200);
        assertThat(deep.maxDepth()).as("max stack depth at a read: 400 batches vs 2").isLessThanOrEqualTo(shallow.maxDepth() + SLACK_FRAMES);
    }

    private record Learned(int learned, int maxDepth) {}

    private static Learned learnOver(int segments) {
        var tier = new DepthTier();
        var storage = StorageInstance.storageInstance("age-depth", List.of(tier), MetadataStore.inMemoryMetadataStore("age-depth"));
        var sink = storageSegmentSink(storage, new SegmentIndex());
        var eventTime = System.currentTimeMillis() - WINDOW_MS / 2;

        for (var offset = 0; offset < segments; offset++) {
            sink.seal(sealedSegment("orders", 0, offset, offset, 1, eventTime, eventTime, encoded(offset, eventTime))).await();
        }

        tier.depths.clear();

        var refs = new HashMap<String, BlockId>();

        for (var offset = 0; offset < segments; offset++) {
            var name = "streams/orders/0/" + offset + "-" + offset;

            storage.resolveRef(name).onPresent(id -> refs.put(name, id));
        }

        var index = new SegmentIndex();

        index.rebuildFromRefs(refs);
        RetentionEnforcer.retentionEnforcer(storage,
                                            index,
                                            WINDOW_MS,
                                            RetentionEnforcer.SegmentRetentionFloor.NONE,
                                            SegmentReader.segmentReader(storage, index))
                         .enforceNow()
                         .await();

        var learned = (int) index.listSegments("orders", 0).stream().filter(ref -> ref.maxTimestamp() > 0).count();

        return new Learned(learned, tier.depths.stream().mapToInt(Integer::intValue).max().orElse(-1));
    }

    private static byte[] encoded(long offset, long eventTime) {
        return ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).putLong(offset).putLong(eventTime).putInt(1).put((byte) offset).array();
    }

    /// A memory tier that records the stack depth of every read.
    private static final class DepthTier implements StorageTier {
        private final MemoryTier delegate = MemoryTier.memoryTier(256L * 1024 * 1024, TierLevel.MEMORY);
        final List<Integer> depths = new CopyOnWriteArrayList<>();

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            depths.add((int) (long) StackWalker.getInstance().walk(frames -> frames.count()));

            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }
    }
}
