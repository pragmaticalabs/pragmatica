// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockEncryptor;
import org.pragmatica.storage.StorageInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;


public sealed interface TieredStreamReader {
    Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents);
    Promise<Unit> prefetch(String streamName, int partition, long fromOffset);

    static TieredStreamReader tieredStreamReader(SegmentIndex index, StorageInstance storage) {
        return new TieredReader(index, storage, none());
    }

    static TieredStreamReader tieredStreamReader(SegmentIndex index,
                                                 StorageInstance storage,
                                                 Option<BlockEncryptor> encryptor) {
        return new TieredReader(index, storage, encryptor);
    }
}

final class TieredReader implements TieredStreamReader {
    private static final Logger log = LoggerFactory.getLogger(TieredReader.class);
    private static final double PREFETCH_THRESHOLD = 0.8;

    private final SegmentIndex index;
    private final SegmentReader reader;
    private final StorageInstance storage;

    TieredReader(SegmentIndex index, StorageInstance storage, Option<BlockEncryptor> encryptor) {
        this.index = index;
        this.reader = SegmentReader.segmentReader(storage, index, encryptor);
        this.storage = storage;
    }

    /// Reads resolve through the index, which records only what was sealed — so an unsealed range is
    /// invisible to it and must be detected, not read around (#1234). The read never crosses a hole: it
    /// stops at the end of the contiguous run holding `fromOffset` (the next read then starts inside the
    /// hole), and a read starting inside a hole fails with [SegmentError.SealedRangeMissing]. An unheld
    /// offset at or below the contiguous sealed watermark was sealed and then reclaimed by retention, so it
    /// fails as `CursorExpired` naming the next sealed offset instead. Only when nothing is sealed at or
    /// above `fromOffset` does it answer empty, leaving the range to the ring (and an in-flight seal to the
    /// caller, which asks the sealer — see `PartitionedStreamAccess`).
    @Override
    public Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents) {
        return index.contiguousSealedEnd(streamName, partition, fromOffset)
                    .map(end -> readContiguous(streamName,
                                               partition,
                                               fromOffset,
                                               boundedCount(fromOffset, end, maxEvents)))
                    .or(() -> holeOrNothingSealed(streamName, partition, fromOffset));
    }

    private Promise<List<RawEvent>> readContiguous(String streamName, int partition, long fromOffset, int maxEvents) {
        return reader.readEvents(streamName, partition, fromOffset, maxEvents)
                     .onSuccess(events -> triggerPrefetchIfNearEnd(streamName, partition, fromOffset, maxEvents, events));
    }

    private static int boundedCount(long fromOffset, long contiguousEnd, int maxEvents) {
        return (int) Math.min(maxEvents, contiguousEnd - fromOffset + 1);
    }

    private Promise<List<RawEvent>> holeOrNothingSealed(String streamName, int partition, long fromOffset) {
        return index.nextSealedOffset(streamName, partition, fromOffset)
                    .map(next -> unheld(streamName, partition, fromOffset, next))
                    .or(() -> Promise.success(List.of()));
    }

    /// The watermark is monotonic and never passes a hole, so an unheld offset at or below it was sealed and
    /// since reclaimed; above it, nothing ever sealed it. After a restart the watermark is anchored at the
    /// lowest surviving ref, so a reclaimed prefix still reads as reclaimed there (#1278 covers the case the
    /// watermark cannot see: every ref of a partition reclaimed).
    private Promise<List<RawEvent>> unheld(String streamName, int partition, long fromOffset, long nextSealed) {
        return fromOffset <= index.lastSealedOffset(streamName, partition)
               ? new StreamError.CursorExpired(fromOffset, nextSealed).promise()
               : new SegmentError.SealedRangeMissing(streamName, partition, fromOffset, nextSealed).promise();
    }

    @Override
    public Promise<Unit> prefetch(String streamName, int partition, long fromOffset) {
        return index.findSegment(streamName, partition, fromOffset)
                    .map(ref -> warmSegment(streamName, partition, ref))
                    .or(Promise.unitPromise());
    }

    private void triggerPrefetchIfNearEnd(String streamName,
                                          int partition,
                                          long fromOffset,
                                          int maxEvents,
                                          List<RawEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        var lastReadOffset = events.getLast().offset();

        index.findSegment(streamName, partition, lastReadOffset)
             .filter(ref -> isNearSegmentEnd(lastReadOffset, ref))
             .onPresent(ref -> prefetchNextSegment(streamName, partition, ref));
    }

    private static boolean isNearSegmentEnd(long currentOffset, SegmentIndex.SegmentRef ref) {
        var segmentSize = ref.endOffset() - ref.startOffset() + 1;
        var positionInSegment = currentOffset - ref.startOffset();

        return positionInSegment >= segmentSize * PREFETCH_THRESHOLD;
    }

    private void prefetchNextSegment(String streamName, int partition, SegmentIndex.SegmentRef currentRef) {
        var nextOffset = currentRef.endOffset() + 1;

        index.findSegment(streamName, partition, nextOffset)
             .onPresent(nextRef -> firePrefetch(streamName, partition, nextRef));
    }

    private void firePrefetch(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        log.debug("Prefetching segment {}/{}:[{}-{}]", streamName, partition, ref.startOffset(), ref.endOffset());
        warmSegment(streamName, partition, ref).onFailure(cause -> log.debug("Prefetch failed for {}/{}:[{}-{}]: {}",
                                                                             streamName,
                                                                             partition,
                                                                             ref.startOffset(),
                                                                             ref.endOffset(),
                                                                             cause.message()));
    }

    private Promise<Unit> warmSegment(String streamName, int partition, SegmentIndex.SegmentRef ref) {
        var refName = SegmentIndex.buildRefName(streamName, partition, ref);

        return storage.resolveRef(refName)
                      .map(blockId -> storage.get(blockId)
                                             .mapToUnit())
                      .or(Promise.unitPromise());
    }
}
