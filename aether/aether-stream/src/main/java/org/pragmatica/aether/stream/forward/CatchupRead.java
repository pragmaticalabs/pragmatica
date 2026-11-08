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
import org.pragmatica.lang.Result;


/// The replication-class read of this node's copy of a partition, up to the APPENDED head: the ring, and for a
/// prefix the ring has evicted, the tier. One implementation serves both the peer that asks for it (a replica's
/// catch-up forward, [StreamForwardHandler]) and this node reading its own copy for the owner promotion gate's
/// overlap check (#1555), so the two sides of a comparison read the same retention.
public sealed interface CatchupRead {
    /// #1383: a replica catch-up read is served from the ring up to the APPENDED head, or — for a prefix the
    /// ring has evicted but this node's tier retains — from the tier, then the ring for the rest of the page.
    /// Before this the read was ring-only, so a replacement replica whose catch-up started below the ring tail
    /// was answered `CursorExpired` on every redrive and never left SYNCING; with a confirmation factor of 2 and the original
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
        var request = new Span(manager, tieredReader, streamName, partition, fromOffset, maxEvents, false);

        return manager.readAppended(request.streamName(),
                                    request.partition(),
                                    request.fromOffset(),
                                    request.maxEvents())
                      .fold(cause -> recoverEvicted(request, cause),
                            Promise::success);
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> recoverEvicted(Span request, Cause cause) {
        if (cause instanceof StreamError.CursorExpired) {
            return readEvictedPrefix(request, cause);
        }

        return cause == StreamError.General.PARTITION_NOT_LOCAL
               ? readHeld(request.asHeld(), cause)
               : cause.promise();
    }

    /// I-27 (#1759): a partition this node HOLDS but has not materialized (paced, or deferred for off-heap budget) has no
    /// ring, and is read from its WAL READ-ONLY ([StreamPartitionManager#readHeldDurable]) -- no ring is created, no budget is
    /// taken -- and, for a prefix sealed below the WAL, from the tier, as the ring's evicted prefix is. An owner behind a
    /// budget-deferred replica that holds records above it can then catch up from that replica and activate; before this,
    /// such a replica answered only its watermark until it materialized, which budget may never allow.
    ///
    /// A partition not held here, or held without a log, keeps the ring's `PARTITION_NOT_LOCAL`. Every refusal of the WAL
    /// read other than `CursorExpired` -- a torn or damaged frame, a hole, an unreadable log -- answers that same
    /// `PARTITION_NOT_LOCAL`, which the forward handler names as the held partition's watermark, exactly as before the WAL
    /// read existed: a damaged WAL answers unavailable, never a partial range. The page is shipped with the WAL's own
    /// owner-epoch history ([StreamForwardHandler]), never an empty or substituted one.
    private static Promise<List<OffHeapRingBuffer.RawEvent>> readHeld(Span request, Cause notLocal) {
        return localRead(request,
                         request.fromOffset(),
                         request.maxEvents(),
                         notLocal).fold(cause -> heldRefused(request, cause),
                                        Promise::success);
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> heldRefused(Span request, Cause cause) {
        return cause instanceof StreamError.CursorExpired
               ? readEvictedPrefix(request, cause)
               : cause.promise();
    }

    /// This node's own copy of `[from, from + max)`: the ring, or for a held partition the WAL, every refusal of which
    /// other than `CursorExpired` is answered `notLocal` ([#readHeld]).
    private static Result<List<OffHeapRingBuffer.RawEvent>> localRead(Span request, long from, int max, Cause notLocal) {
        return request.held()
               ? request.manager()
                        .readHeldDurable(request.streamName(),
                                         request.partition(),
                                         from,
                                         max)
                        .map(read -> read.mapError(cause -> cause instanceof StreamError.CursorExpired
                                                            ? cause
                                                            : notLocal))
                        .or(notLocal::result)
               : request.manager()
                        .readAppended(request.streamName(),
                                      request.partition(),
                                      from,
                                      max);
    }

    /// An evicted offset the sealer still retains is IN FLIGHT: its seal may not be indexed yet (and for a moment
    /// after it is, the sealer still holds it — #1682), and the read fails transient ([SegmentError.SealInFlight]) for the backfill to redrive — never
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

    /// The ring's appended head, or for a held partition its durable watermark (the higher of the WAL head and the sealed
    /// bound, [StreamPartitionManager#heldNotMaterializedWatermark]).
    private static long appendedHead(Span request) {
        return request.held()
               ? request.manager()
                        .heldNotMaterializedWatermark(request.streamName(),
                                                      request.partition())
                        .or(-1L)
               : request.manager()
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

        return localRead(request,
                         next,
                         remaining,
                         StreamError.General.PARTITION_NOT_LOCAL)
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

    /// `held`: the partition is held here without a ring, and the local share of a read is its WAL ([#readHeld]).
    record Span(StreamPartitionManager manager,
                Option<TieredStreamReader> tier,
                String streamName,
                int partition,
                long fromOffset,
                int maxEvents,
                boolean held) {
        Span asHeld() {
            return new Span(manager, tier, streamName, partition, fromOffset, maxEvents, true);
        }
    }

    record unused() implements CatchupRead {}
}
