// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

/// #1555: the two remote reads the owner promotion gate makes of a peer, over the catch-up read class the
/// gate's catch-up then pulls with — the peer's APPENDED head (the probe) and the peer's appended records in an
/// offset range (the overlap verification).
///
/// Both start where the peer's log starts, not at a fixed offset: a peer whose ring and tier no longer hold the
/// requested start answers `CursorExpired` naming its oldest available offset, and the read resumes there. A
/// remote failure travels as its message only, so the refusal is recognised by message and the oldest offset
/// parsed from it. A probe that paged from offset 0 treated every long-lived peer whose offset 0 had aged out as
/// unreachable, blocking promotion for good; a range read that dropped the whole window on one expiry compared
/// nothing, and the gate trusted a source it had not checked.
///
/// Every other failure propagates, so the gate fails closed on it.
public sealed interface OwnerPeerReads {
    /// One page of a peer's partition read over the catch-up class.
    @FunctionalInterface
    interface PageRead {
        Promise<StreamForwardClient.ReadForwardResult> read(NodeId target,
                                                            String streamName,
                                                            int partition,
                                                            long fromOffset,
                                                            int maxEvents);
    }

    Pattern OLDEST_AVAILABLE = Pattern.compile("oldest available is (-?\\d+)");

    /// The peer's appended head; `-1` when the peer holds no ring for the partition (it answers
    /// PARTITION_NOT_LOCAL, so there is nothing to catch up from), or holds an empty one.
    static Promise<Long> appendedWatermark(PageRead read, NodeId target, String streamName, int partition, int page) {
        return pageWatermark(read, target, streamName, partition, 0L, page);
    }

    /// The peer's appended records `from .. to`, starting at the peer's oldest available offset when that is
    /// above `from`. Empty when the peer holds none of the range.
    static Promise<List<OffHeapRingBuffer.RawEvent>> appendedRange(PageRead read,
                                                                    NodeId target,
                                                                    String streamName,
                                                                    int partition,
                                                                    long from,
                                                                    long to,
                                                                    int page) {
        return pageRange(read, target, streamName, partition, from, to, page, List.of());
    }

    private static Promise<Long> pageWatermark(PageRead read,
                                               NodeId target,
                                               String streamName,
                                               int partition,
                                               long cursor,
                                               int page) {
        return read.read(target, streamName, partition, cursor, page)
                   .fold(result -> result.fold(cause -> watermarkRefused(read, target, streamName, partition, cursor, page, cause),
                                               answer -> continueWatermark(read,
                                                                           target,
                                                                           streamName,
                                                                           partition,
                                                                           cursor,
                                                                           page,
                                                                           answer)));
    }

    private static Promise<Long> watermarkRefused(PageRead read,
                                                  NodeId target,
                                                  String streamName,
                                                  int partition,
                                                  long cursor,
                                                  int page,
                                                  Cause cause) {
        return isNotHeld(cause)
               ? Promise.success(-1L)
               : resumeAt(cause, cursor).fold(cause::<Long>promise,
                                              oldest -> pageWatermark(read, target, streamName, partition, oldest, page));
    }

    private static Promise<Long> continueWatermark(PageRead read,
                                                   NodeId target,
                                                   String streamName,
                                                   int partition,
                                                   long cursor,
                                                   int page,
                                                   StreamForwardClient.ReadForwardResult answer) {
        var events = answer.events();

        if (events.isEmpty()) {
            return Promise.success(cursor - 1);
        }

        var lastOffset = events.getLast().offset();

        return events.size() >= page
               ? pageWatermark(read, target, streamName, partition, lastOffset + 1, page)
               : Promise.success(lastOffset);
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> pageRange(PageRead read,
                                                                        NodeId target,
                                                                        String streamName,
                                                                        int partition,
                                                                        long cursor,
                                                                        long to,
                                                                        int page,
                                                                        List<OffHeapRingBuffer.RawEvent> gathered) {
        return read.read(target, streamName, partition, cursor, (int) Math.min(page, to - cursor + 1))
                   .fold(result -> result.fold(cause -> rangeRefused(read,
                                                                     target,
                                                                     streamName,
                                                                     partition,
                                                                     cursor,
                                                                     to,
                                                                     page,
                                                                     gathered,
                                                                     cause),
                                               answer -> continueRange(read,
                                                                       target,
                                                                       streamName,
                                                                       partition,
                                                                       to,
                                                                       page,
                                                                       gathered,
                                                                       answer)));
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> rangeRefused(PageRead read,
                                                                           NodeId target,
                                                                           String streamName,
                                                                           int partition,
                                                                           long cursor,
                                                                           long to,
                                                                           int page,
                                                                           List<OffHeapRingBuffer.RawEvent> gathered,
                                                                           Cause cause) {
        return resumeAt(cause, cursor).fold(cause::<List<OffHeapRingBuffer.RawEvent>>promise,
                                            oldest -> oldest > to
                                                      ? Promise.success(gathered)
                                                      : pageRange(read, target, streamName, partition, oldest, to, page, gathered));
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> continueRange(PageRead read,
                                                                            NodeId target,
                                                                            String streamName,
                                                                            int partition,
                                                                            long to,
                                                                            int page,
                                                                            List<OffHeapRingBuffer.RawEvent> gathered,
                                                                            StreamForwardClient.ReadForwardResult answer) {
        var pageEvents = answer.events()
                               .stream()
                               .filter(event -> event.offset() <= to)
                               .map(event -> OffHeapRingBuffer.RawEvent.rawEvent(event.offset(), event.data(), event.timestamp()))
                               .toList();
        var all = Stream.concat(gathered.stream(), pageEvents.stream()).toList();

        return pageEvents.isEmpty() || pageEvents.getLast().offset() >= to
               ? Promise.success(all)
               : pageRange(read, target, streamName, partition, pageEvents.getLast().offset() + 1, to, page, all);
    }

    private static boolean isNotHeld(Cause cause) {
        return cause.message()
                    .contains(StreamError.General.PARTITION_NOT_LOCAL.message());
    }

    /// The offset to resume at after a `CursorExpired` refusal: the peer's oldest available offset, when it is
    /// ahead of `cursor` (so every resume strictly advances). Any other refusal has none.
    private static Option<Long> resumeAt(Cause cause, long cursor) {
        var matcher = OLDEST_AVAILABLE.matcher(cause.message());

        return matcher.find()
               ? Option.some(Long.parseLong(matcher.group(1))).filter(oldest -> oldest > cursor)
               : Option.none();
    }

    record unused() implements OwnerPeerReads {}
}
