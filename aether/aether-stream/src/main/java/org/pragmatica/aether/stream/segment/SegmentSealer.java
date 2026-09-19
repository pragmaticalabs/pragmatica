// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.segment.SegmentIndex.PartitionKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Seals evicted ring events into segments (#1234). The ring reclaims its space the moment it hands events
/// over; from then on this sealer owns them until the sink has durably stored them, and the partition WAL —
/// whose truncation is gated by the CONTIGUOUS sealed watermark ([SegmentIndex#lastSealedOffset]) — is the
/// durable copy while a seal is pending, so a restart replays whatever was still pending here.
///
///   - **Ordered per partition.** One seal is in flight per `(stream, partition)`; the next segment is sent
///     only after the previous one succeeded, so a later segment can never be sealed past an earlier one
///     that is still failing and the sealed range grows without holes.
///   - **Retried from a retained copy.** Each pending segment is kept on the heap until its seal succeeds and
///     is retried with exponential backoff; every failure is a WARN and a counted failure
///     ([#sealFailureCount]), and every [#ERROR_AFTER_FAILURES] consecutive failures of one segment an ERROR.
///     A pending segment is never dropped.
///   - **Bounded.** The retained copies are capped at `pendingCapBytes`. Only once that cap is reached does
///     [#onEviction] refuse a hand-over, with `SEALING_BEHIND`: the ring then keeps the events and refuses
///     the append that needed their room, so appends fail only when sealing has fallen a whole budget behind.
public final class SegmentSealer implements EvictionListener {
    private static final Logger log = LoggerFactory.getLogger(SegmentSealer.class);
    private static final int PER_EVENT_HEADER = Long.BYTES + Long.BYTES + Integer.BYTES;
    /// The default pending-seal cap: the default per-node stream memory budget (128 MiB,
    /// `StreamPartitionManager`'s `DEFAULT_MAX_TOTAL_BYTES`), so a stalled storage tier can at most double the
    /// memory the node's streams hold. A node passes its configured budget instead (`STREAM_MAX_MEMORY_BYTES`).
    public static final long DEFAULT_PENDING_CAP_BYTES = 128 * 1024 * 1024L;
    /// Consecutive failures of one segment per retry cycle; each exhausted cycle logs an ERROR and a new
    /// cycle starts after [#CYCLE_PAUSE].
    static final int ERROR_AFTER_FAILURES = 10;
    private static final TimeSpan CYCLE_PAUSE = timeSpan(30).seconds();

    private static final Retry SEAL_RETRY = Retry.retry()
                                                 .attempts(ERROR_AFTER_FAILURES)
                                                 .strategy(BackoffStrategy.exponential()
                                                                          .initialDelay(timeSpan(100).millis())
                                                                          .maxDelay(CYCLE_PAUSE)
                                                                          .factor(2.0)
                                                                          .withJitter());

    private final SegmentSink sink;
    private final long pendingCapBytes;
    private final ConcurrentHashMap<PartitionKey, PendingSeals> pending = new ConcurrentHashMap<>();
    private final AtomicLong pendingBytes = new AtomicLong(0);
    private final AtomicLong sealFailures = new AtomicLong(0);
    private final AtomicLong refusals = new AtomicLong(0);
    /// Set by the first refused hand-over and cleared by the next accepted one, so a refusal episode logs one
    /// WARN while [#refusalCount] counts every refusal.
    private final AtomicBoolean refusing = new AtomicBoolean(false);

    private SegmentSealer(SegmentSink sink, long pendingCapBytes) {
        this.sink = sink;
        this.pendingCapBytes = pendingCapBytes;
    }

    public static SegmentSealer segmentSealer(SegmentSink sink) {
        return new SegmentSealer(sink, DEFAULT_PENDING_CAP_BYTES);
    }

    public static SegmentSealer segmentSealer(SegmentSink sink, long pendingCapBytes) {
        return new SegmentSealer(sink, pendingCapBytes);
    }

    /// Take ownership of `events` as one segment and seal it in the background, or refuse with
    /// `SEALING_BEHIND` when the retained copies have reached the pending-seal cap. The sink is never awaited
    /// here: the appending thread does not wait on storage.
    @Override
    public Result<Unit> onEviction(String streamName, int partition, List<RawEvent> events) {
        if (events.isEmpty()) {
            return unitResult();
        }

        var segment = PendingSegment.pendingSegment(buildSegment(streamName, partition, events));

        return reserve(segment).map(_ -> enqueue(segment));
    }

    /// Whether a segment holding `offset` of `(streamName, partition)` is still waiting to be sealed. The
    /// sink updates the index before its promise succeeds and a segment leaves this queue only after that,
    /// so an evicted offset is always in this queue, in the index, or both.
    @Override
    public boolean holdsUnsealed(String streamName, int partition, long offset) {
        return option(pending.get(PartitionKey.partitionKey(streamName, partition))).map(seals -> seals.covers(offset))
                     .or(false);
    }

    /// Seal attempts that failed. Each failed segment stays pending and is retried.
    public long sealFailureCount() {
        return sealFailures.get();
    }

    /// Hand-overs refused because the retained copies had reached the pending-seal cap.
    public long refusalCount() {
        return refusals.get();
    }

    /// Bytes currently retained for segments not yet sealed.
    public long pendingBytes() {
        return pendingBytes.get();
    }

    private Result<Unit> reserve(PendingSegment segment) {
        var before = pendingBytes.getAndUpdate(current -> admitted(current, segment.bytes()));

        return before < pendingCapBytes
               ? accepted()
               : refuse(segment.segment(), before);
    }

    /// The cap is checked against what is already retained, so a segment is admitted while anything below the
    /// cap is pending: the retained total overshoots the cap by at most one segment, and a segment larger than
    /// the cap is still admitted when nothing else is pending.
    private long admitted(long current, long size) {
        return current < pendingCapBytes
               ? current + size
               : current;
    }

    private Result<Unit> accepted() {
        refusing.set(false);

        return unitResult();
    }

    private Result<Unit> refuse(SealedSegment segment, long retained) {
        var count = refusals.incrementAndGet();

        if (refusing.compareAndSet(false, true)) {
            log.warn("Pending-seal cap reached: {} bytes retained for unsealed segments (cap {}); refusing to take {}/{} "
                    + "offsets [{}-{}] — the ring keeps them and refuses appends that need their room until sealing "
                    + "catches up (refusal {})",
                     retained,
                     pendingCapBytes,
                     segment.streamName(),
                     segment.partition(),
                     segment.startOffset(),
                     segment.endOffset(),
                     count);
        }

        return StreamError.General.SEALING_BEHIND.result();
    }

    private Unit enqueue(PendingSegment segment) {
        var seals = pending.computeIfAbsent(PartitionKey.partitionKey(segment.segment().streamName(),
                                                                      segment.segment().partition()),
                                            _ -> PendingSeals.pendingSeals());

        seals.queue().add(segment);
        drain(seals);

        return unit();
    }

    private void drain(PendingSeals seals) {
        if (seals.inFlight().compareAndSet(false, true)) {
            sealHead(seals);
        }
    }

    /// Runs holding the partition's in-flight flag.
    private void sealHead(PendingSeals seals) {
        option(seals.queue().peek()).onPresent(segment -> sealWithRetry(seals, segment)).onEmpty(() -> release(seals));
    }

    /// The re-check after clearing the flag closes the window where a segment was queued after [#sealHead]
    /// saw an empty queue but before the flag was cleared: its [#drain] lost the flag race to us.
    private void release(PendingSeals seals) {
        seals.inFlight().set(false);
        if (!seals.queue().isEmpty()) {
            drain(seals);
        }
    }

    private void sealWithRetry(PendingSeals seals, PendingSegment segment) {
        SEAL_RETRY.execute(() -> attempt(segment.segment()))
                  .onSuccess(_ -> sealed(seals, segment))
                  .onFailure(cause -> retryCycleExhausted(seals, segment, cause));
    }

    private Promise<Unit> attempt(SealedSegment segment) {
        return sink.seal(segment)
                   .onFailure(cause -> recordFailure(segment, cause));
    }

    private void recordFailure(SealedSegment segment, Cause cause) {
        var failures = sealFailures.incrementAndGet();

        log.warn("Sealing {}/{} offsets [{}-{}] failed (seal failure {} on this node); the segment stays pending and "
                + "is retried, and the WAL keeps its offsets meanwhile: {}",
                 segment.streamName(),
                 segment.partition(),
                 segment.startOffset(),
                 segment.endOffset(),
                 failures,
                 cause.message());
    }

    private void sealed(PendingSeals seals, PendingSegment segment) {
        seals.queue().poll();
        pendingBytes.addAndGet(-segment.bytes());
        sealHead(seals);
    }

    /// Absorbing the exhausted cycle is design-out, not loss: the segment stays at the head of its queue (so
    /// nothing later in the partition is sealed past it), its retained copy and the WAL both still hold its
    /// offsets, and a fresh retry cycle starts after [#CYCLE_PAUSE].
    private void retryCycleExhausted(PendingSeals seals, PendingSegment segment, Cause cause) {
        log.error("Sealing {}/{} offsets [{}-{}] failed through a retry cycle of up to {} attempts; the segment stays "
                 + "pending, the partition's later segments wait behind it, and a new cycle starts in {}: {}",
                  segment.segment().streamName(),
                  segment.segment().partition(),
                  segment.segment().startOffset(),
                  segment.segment().endOffset(),
                  ERROR_AFTER_FAILURES,
                  CYCLE_PAUSE,
                  cause.message());
        SharedScheduler.schedule(() -> sealWithRetry(seals, segment), CYCLE_PAUSE);
    }

    private SealedSegment buildSegment(String streamName, int partition, List<RawEvent> events) {
        var startOffset = events.getFirst().offset();
        var endOffset = events.getLast().offset();
        var timestamps = extractTimestamps(events);
        var serialized = serializeEvents(events);

        return SealedSegment.sealedSegment(streamName,
                                           partition,
                                           startOffset,
                                           endOffset,
                                           events.size(),
                                           timestamps[0],
                                           timestamps[1],
                                           serialized);
    }

    private long[] extractTimestamps(List<RawEvent> events) {
        var min = Long.MAX_VALUE;
        var max = Long.MIN_VALUE;

        for (var event : events) {
            min = Math.min(min, event.timestamp());
            max = Math.max(max, event.timestamp());
        }

        return new long[]{min, max};
    }

    private byte[] serializeEvents(List<RawEvent> events) {
        var totalSize = events.stream().mapToInt(e -> PER_EVENT_HEADER + e.data().length).sum();
        var buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);

        events.forEach(event -> writeEvent(buffer, event));

        return buffer.array();
    }

    private void writeEvent(ByteBuffer buffer, RawEvent event) {
        var data = event.data();

        buffer.putLong(event.offset());
        buffer.putLong(event.timestamp());
        buffer.putInt(data.length);
        buffer.put(data);
    }

    /// One partition's segments awaiting their seal, in offset order, and the flag that keeps a single seal in
    /// flight for the partition.
    private record PendingSeals(ConcurrentLinkedQueue<PendingSegment> queue, AtomicBoolean inFlight) {
        static PendingSeals pendingSeals() {
            return new PendingSeals(new ConcurrentLinkedQueue<>(), new AtomicBoolean(false));
        }

        boolean covers(long offset) {
            return queue.stream()
                        .anyMatch(pendingSegment -> pendingSegment.covers(offset));
        }
    }

    /// A retained segment and its size, measured once: [SealedSegment#serializedEvents] copies on every call.
    private record PendingSegment(SealedSegment segment, int bytes) {
        static PendingSegment pendingSegment(SealedSegment segment) {
            return new PendingSegment(segment, segment.serializedEvents().length);
        }

        boolean covers(long offset) {
            return segment.startOffset() <= offset && offset <= segment.endOffset();
        }
    }
}
