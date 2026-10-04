// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.aether.stream.forward.CatchupRead;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
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
///
/// A page the peer cut at its byte cap (`truncated`, #1431) is never read as the peer's end: the probe pages on past
/// it, so a byte-capped page cannot understate the peer's head and let the gate promote below records the peer
/// holds. A cut page carrying no event at all can never advance and fails the read.
///
/// The backfill's own watermark probe ([#replicaWatermark]) reads the same way, so a peer that HOLDS a partition
/// it has not materialized (paced by `reshuffle_concurrency`, or budget-deferred) answers with its durable
/// watermark and is REACHABLE, where a plain read answered `PARTITION_NOT_LOCAL` and was read as unreachable.
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

    /// This node's OWN copy, paged exactly as a peer's catch-up forward is answered — ring, then tier for a prefix the
    /// ring has evicted ([CatchupRead#readAppended]) — so the gate reads both sides of a comparison with the same
    /// retention (v1555 R3: a ring-only read of the candidate's own window compared nothing once its ring was
    /// evicted below a lower peer's head, and a divergent candidate was accepted over that peer's acked records).
    static PageRead localPages(StreamPartitionManager manager, Option<TieredStreamReader> tier) {
        return (_, streamName, partition, fromOffset, maxEvents) -> CatchupRead.readAppended(manager,
                                                                                             tier,
                                                                                             streamName,
                                                                                             partition,
                                                                                             fromOffset,
                                                                                             maxEvents).map(OwnerPeerReads::asPage);
    }

    /// The owner promotion gate's overlap read ([OwnerActivation.RecordRange]): the APPENDED records `from .. to`
    /// held by any member — this node's own copy through its ring and tier ([#localPages]), a peer's over the
    /// catch-up forward (`peerPages`), which serves the same read — each resuming at that copy's oldest available
    /// offset ([#appendedRange]). The tier is not optional here: a ring-only candidate read compared nothing once
    /// the candidate's ring was evicted below a lower peer's head (v1555 R3). The dispatch lives here, not at the
    /// node's assembly, so the gate tests exercise the read production makes (v1555 F1).
    static OwnerActivation.RecordRange ownerRange(NodeId self,
                                                  StreamPartitionManager manager,
                                                  TieredStreamReader tier,
                                                  PageRead peerPages,
                                                  int page) {
        var local = localPages(manager, Option.some(tier));

        return (node, streamName, partition, from, to) -> appendedRange(node.equals(self)
                                                                        ? local
                                                                        : peerPages,
                                                                        node,
                                                                        streamName,
                                                                        partition,
                                                                        from,
                                                                        to,
                                                                        page);
    }

    private static StreamForwardClient.ReadForwardResult asPage(List<OffHeapRingBuffer.RawEvent> events) {
        return StreamForwardClient.ReadForwardResult.readForwardResult(events.stream()
                                                                             .map(RawEventDto::fromRawEvent)
                                                                             .toList(),
                                                                       false);
    }

    /// The peer's appended head; `-1` when the peer holds no ring for the partition (it answers
    /// PARTITION_NOT_LOCAL, so there is nothing to catch up from), or holds an empty one; its durable watermark
    /// when it holds the partition unmaterialized ([StreamError.PartitionHeldNotMaterialized]).
    static Promise<Long> appendedWatermark(PageRead read, NodeId target, String streamName, int partition, int page) {
        return pageWatermark(read, target, streamName, partition, 0L, page, OwnerPeerReads::settleForGate);
    }

    /// A REACHABLE replica's watermark for the backfill's cold-start and owner-catch-up probes
    /// ([org.pragmatica.aether.stream.replication.ReplicaWatermarkProbe]), over the same catch-up read class as
    /// [#appendedWatermark]: the peer's appended head, `-1` when it holds an empty ring, and — for a partition it
    /// HOLDS but has not materialized (paced or budget-deferred) — its durable watermark, reported with
    /// [StreamError.PartitionHeldNotMaterialized]. That refusal is an answer from a reachable peer, so a paced
    /// replica no longer reads as unreachable and holds a promotion for the whole source wait. A peer that
    /// answers `PARTITION_NOT_LOCAL` is NOT settled here, unlike the gate's probe: this probe has no committed
    /// replica set to say the peer is meant to hold the partition, so a genuine non-holder stays a failure (no
    /// information) and self does not promote past it. Every other failure propagates, and so does a transport
    /// failure.
    static Promise<Long> replicaWatermark(PageRead read, NodeId target, String streamName, int partition, int page) {
        return pageWatermark(read, target, streamName, partition, 0L, page, OwnerPeerReads::settleForReplica);
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
                                               int page,
                                               Fn1<Option<Long>, Cause> settle) {
        return read.read(target, streamName, partition, cursor, page)
                   .fold(result -> result.fold(cause -> watermarkRefused(read,
                                                                         target,
                                                                         streamName,
                                                                         partition,
                                                                         cursor,
                                                                         page,
                                                                         settle,
                                                                         cause),
                                               answer -> continueWatermark(read,
                                                                           target,
                                                                           streamName,
                                                                           partition,
                                                                           cursor,
                                                                           page,
                                                                           settle,
                                                                           answer)));
    }

    /// A refusal the probe's `settle` policy turns into a watermark ends the probe there; a `CursorExpired`
    /// resumes at the peer's oldest offset; any other refusal fails the probe.
    private static Promise<Long> watermarkRefused(PageRead read,
                                                  NodeId target,
                                                  String streamName,
                                                  int partition,
                                                  long cursor,
                                                  int page,
                                                  Fn1<Option<Long>, Cause> settle,
                                                  Cause cause) {
        return settle.apply(cause)
                     .fold(() -> resumeAt(cause, cursor).fold(cause::<Long> promise,
                                                              oldest -> pageWatermark(read,
                                                                                      target,
                                                                                      streamName,
                                                                                      partition,
                                                                                      oldest,
                                                                                      page,
                                                                                      settle)),
                           Promise::success);
    }

    /// The gate's probe: a peer that holds no ring at all has nothing to catch up from (`-1`), and one that
    /// holds the partition unmaterialized reports its durable watermark.
    private static Option<Long> settleForGate(Cause cause) {
        return isNotHeld(cause)
               ? Option.some(-1L)
               : StreamError.PartitionHeldNotMaterialized.watermarkOf(cause);
    }

    private static Option<Long> settleForReplica(Cause cause) {
        return StreamError.PartitionHeldNotMaterialized.watermarkOf(cause);
    }

    private static Promise<Long> continueWatermark(PageRead read,
                                                   NodeId target,
                                                   String streamName,
                                                   int partition,
                                                   long cursor,
                                                   int page,
                                                   Fn1<Option<Long>, Cause> settle,
                                                   StreamForwardClient.ReadForwardResult answer) {
        var events = answer.events();

        if (events.isEmpty()) {
            return answer.truncated()
                   ? new EventExceedsReadCap(cursor).<Long> promise()
                   : Promise.success(cursor - 1);
        }

        var lastOffset = events.getLast().offset();

        return events.size() >= page || answer.truncated()
               ? pageWatermark(read, target, streamName, partition, lastOffset + 1, page, settle)
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
        return read.read(target,
                         streamName,
                         partition,
                         cursor,
                         (int) Math.min(page, to - cursor + 1))
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
                                                                       cursor,
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
        return resumeAt(cause, cursor).fold(cause::<List<OffHeapRingBuffer.RawEvent>> promise,
                                            oldest -> oldest > to
                                                      ? Promise.success(gathered)
                                                      : pageRange(read,
                                                                  target,
                                                                  streamName,
                                                                  partition,
                                                                  oldest,
                                                                  to,
                                                                  page,
                                                                  gathered));
    }

    private static Promise<List<OffHeapRingBuffer.RawEvent>> continueRange(PageRead read,
                                                                           NodeId target,
                                                                           String streamName,
                                                                           int partition,
                                                                           long cursor,
                                                                           long to,
                                                                           int page,
                                                                           List<OffHeapRingBuffer.RawEvent> gathered,
                                                                           StreamForwardClient.ReadForwardResult answer) {
        if (answer.events().isEmpty() && answer.truncated()) {
            return new EventExceedsReadCap(cursor).promise();
        }

        var pageEvents = answer.events()
                               .stream()
                               .filter(event -> event.offset() <= to)
                               .map(event -> OffHeapRingBuffer.RawEvent.rawEvent(event.offset(),
                                                                                 event.data(),
                                                                                 event.timestamp()))
                               .toList();
        var all = Stream.concat(gathered.stream(), pageEvents.stream()).toList();

        return pageEvents.isEmpty() || pageEvents.getLast()
                                                 .offset() >= to
               ? Promise.success(all)
               : pageRange(read,
                           target,
                           streamName,
                           partition,
                           pageEvents.getLast().offset() + 1,
                           to,
                           page,
                           all);
    }

    /// Only `PARTITION_NOT_LOCAL` means "holds nothing". `Stream not found` stays UNREACHABLE on purpose: after a cold
    /// restart a same-id peer that has not yet applied the stream's config still holds its data on disk, so reading
    /// "not found" as `-1` could let the owner promote past durable records. The owner gate's re-drive covers the
    /// transient case (config not applied yet) safely.
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

    /// #1431: a page the peer cut at its byte cap before its first event — the event at `offset` alone exceeds the
    /// peer's `maxReadResponseBytes` — can never advance, and reading it as the peer's end would understate the peer.
    /// A peer whose handler admits the first event of every page never produces one, so this is a BACKSTOP (a peer
    /// on an older handler). The peer ANSWERED: callers must never read this as an unreachable peer. The event's
    /// size is not known here — the cut page carries no event.
    record EventExceedsReadCap(long offset) implements Cause {
        @Override
        public String message() {
            return ("Peer page was cut at the peer's read cap before its first event: the event at offset %d is larger "
                   + "than the peer's maxReadResponseBytes").formatted(offset);
        }
    }

    record unused() implements OwnerPeerReads {}
}
