// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// The one decision for a read of an offset the ring no longer holds (#2201 C-16, I-09). Three callers ask it, and none
/// decides on its own: the pull API (`PartitionedStreamAccess`), the validated consumer read
/// (`StreamPartitionManager.readServingTiered`, which `StreamReadRouter.readValidated` calls) and the owner's forwarded
/// consumer read (`StreamForwardHandler`, through the same method).
///
/// - While the segment sealer still retains the offset it is IN FLIGHT (#1234): [SegmentError.SealInFlight], transient;
///   the caller backs off and re-reads. Asked BEFORE the tier: the sink indexes a segment before the sealer lets it go, so
///   an offset not retained at this point is already findable in the index, and there is no instant at which it is in
///   neither place.
/// - Otherwise the tier is read, bounded by the partition's VISIBLE position exactly as the ring read is (#1352), and the
///   ring's events continue the read after the sealed ones.
/// - The tier's own failures pass through unchanged: `CursorExpired(fromOffset, earliestRetained)` for an offset retention
///   reclaimed (the tier names its next sealed offset), [SegmentError.SealedRangeMissing] for a hole above the sealed
///   watermark, and a storage cause for an unreadable tier. Telling them apart is the consumer's business.
/// - With no tier wired, or a tier that holds nothing at or above `fromOffset`, the offset is gone as far as this node can
///   tell: `CursorExpired` at the ring tail (the ring read's own answer).
public sealed interface SealedTierFallback permits TierFallback {
    Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents);

    static SealedTierFallback sealedTierFallback(StreamPartitionManager partitionManager,
                                                 Option<TieredStreamReader> tieredReader) {
        return new TierFallback(partitionManager, tieredReader);
    }
}

final class TierFallback implements SealedTierFallback {
    private final StreamPartitionManager partitionManager;
    private final Option<TieredStreamReader> tieredReader;

    TierFallback(StreamPartitionManager partitionManager, Option<TieredStreamReader> tieredReader) {
        this.partitionManager = partitionManager;
        this.tieredReader = tieredReader;
    }

    @Override
    public Promise<List<RawEvent>> read(String streamName, int partition, long fromOffset, int maxEvents) {
        return partitionManager.sealInFlight(streamName, partition, fromOffset)
               ? new SegmentError.SealInFlight(streamName, partition, fromOffset).promise()
               : readWithSegmentFallback(streamName, partition, fromOffset, maxEvents);
    }

    private Promise<List<RawEvent>> readWithSegmentFallback(String streamName,
                                                            int partition,
                                                            long fromOffset,
                                                            int maxEvents) {
        return tieredReader.map(reader -> readFromTieredReader(reader, streamName, partition, fromOffset, maxEvents))
                           .or(() -> cursorExpiredAtTheRingTail(streamName, partition, fromOffset));
    }

    private Promise<List<RawEvent>> cursorExpiredAtTheRingTail(String streamName, int partition, long fromOffset) {
        return new StreamError.CursorExpired(fromOffset,
                                             partitionManager.partitionBuffer(streamName, partition)
                                                             .map(OffHeapRingBuffer::tailOffset)
                                                             .or(0L)).promise();
    }

    /// The cold read is bounded by the partition's VISIBLE position exactly as the ring read is (#1352):
    /// DROP_OLDEST seals every evictee, acknowledged or not, so the durable tier can hold offsets above visible, and
    /// serving one there would expose an event the ring refuses for the same offset. An offset above visible reads as the
    /// ring reads it (`OffHeapRingBuffer.readChecked`: `fromOffset > head` gives `[]`, nothing yet); below it the tier is
    /// asked for no more than `[fromOffset, visible]`. Visible only rises, so a consumer waiting here is woken by the same
    /// advance that would wake it on the ring.
    private Promise<List<RawEvent>> readFromTieredReader(TieredStreamReader reader,
                                                         String streamName,
                                                         int partition,
                                                         long fromOffset,
                                                         int maxEvents) {
        var visible = visibleOffset(streamName, partition);

        if (fromOffset > visible) {
            return Promise.success(List.of());
        }

        return reader.read(streamName,
                           partition,
                           fromOffset,
                           (int) Math.min(maxEvents, visible - fromOffset + 1))
                     .flatMap(sealedEvents -> combineWithBufferEvents(sealedEvents,
                                                                      streamName,
                                                                      partition,
                                                                      fromOffset,
                                                                      maxEvents));
    }

    /// `-1` (nothing visible) when the partition has no ring here: this path is reached only after a local ring read, so
    /// that is a ring released under the reader, and nothing may be served from the tier in its name.
    private long visibleOffset(String streamName, int partition) {
        return partitionManager.partitionBuffer(streamName, partition)
                               .map(OffHeapRingBuffer::visibleOffset)
                               .or(-1L);
    }

    /// Continue a cold read into the ring (#1234). With nothing sealed at `fromOffset` the ring is the only remaining
    /// source, so its failure (`CursorExpired` for an offset held by neither tier) is returned as-is: answering `[]` here
    /// stalled the consumer at that cursor forever. After a sealed prefix, a ring failure is absorbed by FER (degrade
    /// forward, [#bufferReadFallback]): the sealed prefix is returned and the next read, which starts right after it,
    /// reaches this method with nothing sealed and surfaces the failure then, so the consumer makes progress and still sees
    /// the error at the exact offset it occurs. The one exception is a corrupted ring (#1247 review M2), which fails the
    /// read on either path.
    private Promise<List<RawEvent>> combineWithBufferEvents(List<RawEvent> sealedEvents,
                                                            String streamName,
                                                            int partition,
                                                            long fromOffset,
                                                            int maxEvents) {
        var remaining = maxEvents - sealedEvents.size();

        if (remaining <= 0) {
            return Promise.success(sealedEvents);
        }

        if (sealedEvents.isEmpty()) {
            return partitionManager.readServing(streamName, partition, fromOffset, remaining)
                                   .async();
        }

        return partitionManager.readServing(streamName,
                                            partition,
                                            sealedEvents.getLast().offset() + 1,
                                            remaining)
                               .fold(TierFallback::bufferReadFallback, Result::success)
                               .map(bufferEvents -> List.copyOf(Stream.concat(sealedEvents.stream(),
                                                                              bufferEvents.stream())
                                                                      .toList()))
                               .async();
    }

    /// FER (degrade forward) for the ring tail after a segment fallback: any buffer failure except a corrupted ring returns
    /// the sealed events alone, a short read the consumer continues from its next offset. A corrupted ring is the exception
    /// (#1247 review M2): its events cannot be trusted and "no buffer events" would silently truncate the read, so
    /// [StreamError.RingIndexCorrupted] reaches the caller.
    private static Result<List<RawEvent>> bufferReadFallback(Cause cause) {
        return cause instanceof StreamError.RingIndexCorrupted
               ? cause.result()
               : success(List.of());
    }
}
