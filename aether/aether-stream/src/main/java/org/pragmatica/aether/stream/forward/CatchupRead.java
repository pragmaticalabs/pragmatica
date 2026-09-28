// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The replication-class read of this node's copy of a partition, up to the APPENDED head: the ring, and for a
/// prefix the ring has evicted, the tier. One implementation serves both the peer that asks for it (a replica's
/// catch-up forward, [StreamForwardHandler]) and this node reading its own copy for the owner promotion gate's
/// overlap check (#1555), so the two sides of a comparison read the same retention.
public sealed interface CatchupRead {
    /// #1383: a replica catch-up read is served from the ring up to the APPENDED head, or — for a prefix the
    /// ring has evicted but this node's tier retains — from the tier, then the ring for the rest of the page.
    /// Before this the read was ring-only, so a replacement replica whose catch-up started below the ring tail
    /// was answered `CursorExpired` on every redrive and never left SYNCING; with `min-sync` 2 and the original
    /// peer gone, the partition's visible position never advanced again. The tier read is bounded by the
    /// appended head — the replication-read class (#1235), never the consumer's visible bound (#1352) — so the
    /// replica holds every offset it acks and nothing this owner has not appended. A prefix retention has
    /// reclaimed is still `CursorExpired` (from the tier, [TieredStreamReader#read]) and still stalls: #1407.
    static Promise<List<OffHeapRingBuffer.RawEvent>> readAppended(StreamPartitionManager manager,
                                                                  Option<TieredStreamReader> tieredReader,
                                                                  String streamName,
                                                                  int partition,
                                                                  long fromOffset,
                                                                  int maxEvents) {
        var request = new Span(manager, tieredReader, streamName, partition, fromOffset, maxEvents);

        return manager.readAppended(request.streamName(),
                                    request.partition(),
                                    request.fromOffset(),
                                    request.maxEvents())
                      .fold(cause -> recoverEvicted(request, cause),
                            Promise::success);
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> recoverEvicted(Span request, Cause cause) {
        return cause instanceof StreamError.CursorExpired
               ? readEvictedPrefix(request, cause)
               : cause.promise();
    }

    /// An evicted offset the sealer still retains is IN FLIGHT: its seal is not indexed yet, so it is in neither
    /// place, and the read fails transient ([SegmentError.SealInFlight]) for the backfill to redrive — never
    /// `CursorExpired`, which names an offset nobody holds. Asked BEFORE the tier read, as the consumer path
    /// does: the sink indexes a segment before the sealer releases its copy, so an offset not retained here is
    /// already findable in the index. Without a tier wired the ring's own refusal stands.
    private static Promise<List<OffHeapRingBuffer.RawEvent>> readEvictedPrefix(Span request, Cause expired) {
        if (request.manager().sealInFlight(request.streamName(), request.partition(), request.fromOffset())) {
            return new SegmentError.SealInFlight(request.streamName(), request.partition(), request.fromOffset()).promise();
        }

        return request.tier()
                      .fold(expired::promise,
                            reader -> readTierThenRing(request, reader, expired));
    }

    /// The tier is asked for no more than `[fromOffset, appended head]`. The bound is load-bearing, not a belt: the
    /// `SegmentIndex` is never purged when a stream is removed, so a stream re-created under the same name starts
    /// a fresh ring over the old incarnation's refs, and the tier can be contiguous past the new head (rev1417 F3).
    /// Nothing sealed at `fromOffset` means the ring's refusal was right (a seal that failed for good, a ring
    /// released under the read) and it is returned as-is — an empty success would let the backfill take the
    /// no-source path off a partition that has history.
    private static Promise<List<OffHeapRingBuffer.RawEvent>> readTierThenRing(Span request,
                                                                              TieredStreamReader reader,
                                                                              Cause expired) {
        var head = appendedHead(request);

        if (request.fromOffset() > head) {
            return expired.promise();
        }

        return reader.read(request.streamName(),
                           request.partition(),
                           request.fromOffset(),
                           (int) Math.min(request.maxEvents(),
                                          head - request.fromOffset() + 1))
                     .flatMap(sealed -> serveSealedPrefix(request, sealed, expired));
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> serveSealedPrefix(Span request,
                                                                               List<OffHeapRingBuffer.RawEvent> sealed,
                                                                               Cause expired) {
        return sealed.isEmpty()
               ? expired.promise()
               : appendRingTail(request, sealed);
    }

    private static long appendedHead(Span request) {
        return request.manager()
                      .partitionBuffer(request.streamName(),
                                       request.partition())
                      .map(OffHeapRingBuffer::headOffset)
                      .or(-1L);
    }

    /// The ring's share of the page starts right after the sealed prefix, and it is NOT best-effort: the pull
    /// ends on a short page (`ForwardCatchupTransport.continueOrFinish`) and the backfill then promotes at the
    /// page's own last offset (`PartitionBackfill.applyOwnerResponse`), so a page that succeeds must reach the
    /// appended head — the invariant the ring-only read always had. A ring refusal for the next offset
    /// therefore fails the whole page (rev1417 F1: a prefix-alone page promoted a replica CAUGHT_UP below the
    /// head), as [SegmentError.SealInFlight] when the sealer still holds that offset, else as the ring's own
    /// cause; the backfill redrives, and a later redrive reads a longer sealed prefix.
    private static Promise<List<OffHeapRingBuffer.RawEvent>> appendRingTail(Span request,
                                                                            List<OffHeapRingBuffer.RawEvent> sealed) {
        var remaining = request.maxEvents() - sealed.size();

        if (remaining <= 0) {
            return Promise.success(sealed);
        }

        var next = sealed.getLast().offset() + 1;

        return request.manager()
                      .readAppended(request.streamName(),
                                    request.partition(),
                                    next,
                                    remaining)
                      .map(ring -> List.copyOf(Stream.concat(sealed.stream(),
                                                             ring.stream()).toList()))
                      .fold(cause -> ringTailRefused(request, next, cause),
                            Promise::success);
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> ringTailRefused(Span request, long next, Cause cause) {
        return request.manager()
                      .sealInFlight(request.streamName(),
                                    request.partition(),
                                    next)
               ? new SegmentError.SealInFlight(request.streamName(), request.partition(), next).promise()
               : cause.promise();
    }

    record Span(StreamPartitionManager manager,
                Option<TieredStreamReader> tier,
                String streamName,
                int partition,
                long fromOffset,
                int maxEvents) {}

    record unused() implements CatchupRead {}
}
