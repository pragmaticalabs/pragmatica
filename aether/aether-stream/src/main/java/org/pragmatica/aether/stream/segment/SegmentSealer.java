// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.WalRangeReader;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.aether.stream.segment.SegmentIndex.PartitionKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Functions.ThrowingFn0;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Seals evicted ring events into segments (#1234). The ring reclaims its space the moment it hands events
/// over; from then on this sealer owns them until the sink has durably stored them, and the partition WAL —
/// whose truncation is gated by the CONTIGUOUS sealed watermark ([SegmentIndex#lastSealedOffset]) — is the
/// durable copy while a seal is pending, so a restart replays whatever was still pending here.
///
///   - **Runs (spec 4.2, #2201 C-02).** A partition's queued hand-overs are its RUN. It is sealed once it is ready
///     ([SealerThresholds]: its queued bytes, its queued events or the age of its oldest entry reach their
///     threshold; or a read asked for one of its offsets, [#holdsUnsealedForRead]; or the node is stopping,
///     [#flushAll]; or the heap copies of the whole sealer reach half the pending cap); until then one timer per
///     partition waits for the oldest entry's age. A seal takes the head and every CONTIGUOUS follower up to
///     `seal_merge_bytes` into one segment, so a backlog that queued behind a slow seal becomes the next, larger
///     block. Segment boundaries are an implementation detail, not a contract.
///   - **Ordered per partition.** One seal is in flight per `(stream, partition)`; the next run is sent
///     only after the previous one succeeded, so a later segment can never be sealed past an earlier one
///     that is still failing and the sealed range grows without holes.
///   - **Retried as the same run.** A failed seal is retried with exponential backoff, always with the entries it
///     first took (I-07: what queued meanwhile waits for the next run); every failure is a WARN and a counted
///     failure ([#sealFailureCount]), and every [#ERROR_AFTER_FAILURES] consecutive failures of one run an
///     ERROR. A pending segment is never dropped (only a deleted stream's are cancelled, [#onStreamDeleted]).
///   - **Closed at node stop (#1875).** [#close] cancels the age timers and the retry pauses and retries nothing,
///     so no seal of a stopped node outlives it; a seal already on its first attempt finishes or fails quietly.
///   - **Heap copies are bounded by `pendingCapBytes`; the WAL is the holder.** Each pending segment starts
///     with a heap copy. For a partition WITH a WAL (attached by its ring at construction, [#walAttached]),
///     going past the cap drops heap copies, oldest first, and keeps only the pending RANGE — but only for a
///     range already durable in the WAL ([AppendLog#durableOffset]); a copy whose range the WAL has not yet
///     fsynced (the replica path writes its WAL asynchronously) is kept, so the heap can exceed the cap by at
///     most that not-yet-durable tail. What bounds the tail is the replica's WAL chain lag: on the owner's
///     publish path it is empty (a publish is acked only after its fsync); on the replica path it is the
///     records `appendRecovered` has landed since the last `syncReplicated` — one replication batch on the
///     live path (the receive handler syncs before acking); during catch-up, every backfilled record since the
///     last sync (#1244 adds a sync at the end of each backfill run). [design intent — unverified: no
///     measurement of the tail under a large backfill with storage down]. A retry rebuilds a spilled segment from
///     [WalRangeReader#readExactRange] — exactly the range, or a loud [SegmentError.WalRangeMissing], never a
///     short segment. Such a hand-over is never refused, so pending-seal pressure never fails an EVENTUAL
///     append; the limit moves to the WAL's disk, where a failed write fail-stops the partition loudly
///     (#634-7, #1231).
///   - **The one refusal: no WAL.** A partition WITHOUT a WAL (a manager built with no WAL directory — the
///     non-crash-durable mode, e.g. Ember or Forge without a data dir) has no durable holder, so its heap copy
///     is the only one. There, once the cap is reached, [#onEviction] refuses with `SEALING_BEHIND`: the ring
///     keeps the events and the append needing their room FAILS. This is the only case in which an EVENTUAL
///     append can fail for pending-seal pressure.
public final class SegmentSealer implements EvictionListener {
    private static final Logger log = LoggerFactory.getLogger(SegmentSealer.class);
    private static final int PER_EVENT_HEADER = Long.BYTES + Long.BYTES + Integer.BYTES;
    /// The default pending-seal cap: the default per-node stream memory budget (128 MiB,
    /// `StreamPartitionManager`'s `DEFAULT_MAX_TOTAL_BYTES`), so a stalled storage tier can at most double the
    /// memory the node's streams hold. A node passes its configured budget instead (`STREAM_MAX_MEMORY_BYTES`).
    public static final long DEFAULT_PENDING_CAP_BYTES = 128 * 1024 * 1024L;
    /// Consecutive failures of one run per retry cycle; each exhausted cycle logs an ERROR and a new
    /// cycle starts after [#CYCLE_PAUSE].
    static final int ERROR_AFTER_FAILURES = 10;
    private static final TimeSpan CYCLE_PAUSE = timeSpan(30).seconds();
    private static final TimeSpan FIRST_RETRY_DELAY = timeSpan(100).millis();

    private final SegmentSink sink;
    private final long pendingCapBytes;
    private final SealerThresholds thresholds;
    private final Retry sealRetry;
    private final int cycleAttempts;
    private final TimeSpan cyclePause;
    private final ConcurrentHashMap<PartitionKey, PendingSeals> pending = new ConcurrentHashMap<>();
    private final AtomicLong pendingBytes = new AtomicLong(0);
    private final AtomicLong sealFailures = new AtomicLong(0);
    private final AtomicLong refusals = new AtomicLong(0);
    /// Set by the first refused hand-over and cleared by the next accepted one, so a refusal episode logs one
    /// WARN while [#refusalCount] counts every refusal.
    private final AtomicBoolean refusing = new AtomicBoolean(false);
    private final AtomicLong spills = new AtomicLong(0);
    private final AtomicLong drainCrashes = new AtomicLong(0);
    /// Set by the first spill of an episode and cleared by the next hand-over that fits under the cap, so a
    /// spill episode logs one WARN while [#spillCount] counts every dropped heap copy.
    private final AtomicBoolean spilling = new AtomicBoolean(false);
    private final ConcurrentHashMap<PartitionKey, AppendLog> wals = new ConcurrentHashMap<>();
    /// Set by [#flushAll]: from then on every run is ready at once.
    private final AtomicBoolean closing = new AtomicBoolean(false);
    /// Set by [#close]: nothing new starts, nothing failed is retried.
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong ageTimersScheduled = new AtomicLong(0);

    private SegmentSealer(SegmentSink sink,
                          long pendingCapBytes,
                          SealerThresholds thresholds,
                          int cycleAttempts,
                          TimeSpan firstRetryDelay,
                          TimeSpan cyclePause) {
        this.sink = sink;
        this.pendingCapBytes = pendingCapBytes;
        this.thresholds = thresholds;
        this.cycleAttempts = cycleAttempts;
        this.cyclePause = cyclePause;
        this.sealRetry = Retry.retry()
                              .attempts(cycleAttempts)
                              .strategy(BackoffStrategy.exponential()
                                                       .initialDelay(firstRetryDelay)
                                                       .maxDelay(cyclePause)
                                                       .factor(2.0)
                                                       .withJitter());
    }

    public static SegmentSealer segmentSealer(SegmentSink sink) {
        return segmentSealer(sink, DEFAULT_PENDING_CAP_BYTES);
    }

    public static SegmentSealer segmentSealer(SegmentSink sink, long pendingCapBytes) {
        return segmentSealer(sink, pendingCapBytes, SealerThresholds.defaults());
    }

    public static SegmentSealer segmentSealer(SegmentSink sink, long pendingCapBytes, SealerThresholds thresholds) {
        return segmentSealer(sink, pendingCapBytes, thresholds, ERROR_AFTER_FAILURES, FIRST_RETRY_DELAY, CYCLE_PAUSE);
    }

    /// The retry cycle shortened, for tests that must reach a cycle's end and its pause.
    static SegmentSealer segmentSealer(SegmentSink sink,
                                       long pendingCapBytes,
                                       SealerThresholds thresholds,
                                       int cycleAttempts,
                                       TimeSpan firstRetryDelay,
                                       TimeSpan cyclePause) {
        return new SegmentSealer(sink, pendingCapBytes, thresholds, cycleAttempts, firstRetryDelay, cyclePause);
    }

    /// Take ownership of `events` as one segment and seal it in the background. A partition with a WAL is
    /// never refused (past the cap, heap copies spill to WAL-backed ranges); one without refuses with
    /// `SEALING_BEHIND` once the cap is reached — see the class doc. The sink is never awaited here: the
    /// appending thread does not wait on storage.
    @Override
    public Result<Unit> onEviction(String streamName, int partition, List<RawEvent> events) {
        if (events.isEmpty()) {
            return unitResult();
        }

        var segment = PendingSegment.retained(buildSegment(streamName, partition, events));

        return durable(streamName, partition)
               ? success(admitSpilling(segment))
               : reserve(segment).map(_ -> enqueue(segment));
    }

    /// Registered per partition by its ring before anything is replayed into it, so recovery-time hand-overs
    /// already see the partition as WAL-backed; a re-materialized partition replaces its closed WAL here.
    @Override
    public Unit walAttached(String streamName, int partition, AppendLog wal) {
        wals.put(PartitionKey.partitionKey(streamName, partition), wal);

        return unit();
    }

    /// The lowest start offset among `(streamName, partition)`'s pending segments — heap-held or WAL-backed —
    /// or none when nothing is pending. The queue is in offset order and a segment leaves it only once sealed.
    @Override
    public Option<Long> lowestUnsealed(String streamName, int partition) {
        return option(pending.get(PartitionKey.partitionKey(streamName, partition))).flatMap(seals -> option(seals.queue()
                                                                                                                  .peek()))
                     .map(PendingSegment::startOffset);
    }

    /// Whether a segment holding `offset` of `(streamName, partition)` is still waiting to be sealed. The
    /// sink updates the index before its promise succeeds and a segment leaves this queue only after that,
    /// so an evicted offset is always in this queue, in the index, or both.
    @Override
    public boolean holdsUnsealed(String streamName, int partition, long offset) {
        return option(pending.get(PartitionKey.partitionKey(streamName, partition))).map(seals -> seals.covers(offset))
                     .or(false);
    }

    /// [#holdsUnsealed], and a pending `offset` makes every run up to the one holding it ready at once (demand flush):
    /// a reader waits for the seals ahead of its offset, never for `seal_run_age`. Repeated reads cost nothing more, as
    /// one seal per partition is in flight at a time.
    @Override
    public boolean holdsUnsealedForRead(String streamName, int partition, long offset) {
        return option(pending.get(PartitionKey.partitionKey(streamName, partition))).filter(seals -> seals.covers(offset))
                     .map(seals -> requestFlush(seals, offset))
                     .or(false);
    }

    private boolean requestFlush(PendingSeals seals, long offset) {
        seals.flushThrough().accumulateAndGet(offset, Math::max);
        maybeDrain(seals);

        return true;
    }

    /// Cancel every pending seal of the deleted `streamName` and release the bytes they held against the cap.
    /// A seal already in flight may still land; it then releases nothing twice (see [#released]), and a
    /// scheduled retry of a cancelled segment stops at its next attempt without calling the sink.
    @Override
    public Unit onStreamDeleted(String streamName) {
        pending.keySet().stream().filter(key -> key.streamName()
                                                   .equals(streamName)).toList().forEach(this::cancel);
        wals.keySet().removeIf(key -> key.streamName()
                                         .equals(streamName));

        return unit();
    }

    /// Node stop, best effort (spec 4.2 item 6): every partition's queued run is ready at once, and so is any later one.
    /// A WAL-backed range needs none of it (the WAL replays it at restart); for a partition without a WAL it narrows the
    /// window of acknowledged events a crash loses. Followed by [#close], so each partition seals at most the run it
    /// starts here.
    public Unit flushAll() {
        closing.set(true);
        pending.values().forEach(this::maybeDrain);

        return unit();
    }

    /// Node stop (#1875): cancels every age timer and retry pause, starts nothing new and retries nothing, so the
    /// sealer of a stopped node does not keep sealing against its closed WALs. A failure after this is counted but not
    /// a WARN. What stays pending is held by the WAL, or lost with the heap on a partition without one (as a crash).
    public Unit close() {
        closed.set(true);
        pending.values().forEach(PendingSeals::cancelTimers);

        return unit();
    }

    /// Scheduled timers of this sealer (age timers, retry pauses) that have neither run nor been cancelled.
    public int armedTimers() {
        return pending.values()
                      .stream()
                      .mapToInt(PendingSeals::armedTimers)
                      .sum();
    }

    /// Age timers scheduled since construction.
    long ageTimersScheduled() {
        return ageTimersScheduled.get();
    }

    /// Drain steps that threw — never expected; each is an ERROR and the drain resumes after a pause.
    public long drainCrashCount() {
        return drainCrashes.get();
    }

    /// Seal attempts that failed. Each failed segment stays pending and is retried.
    public long sealFailureCount() {
        return sealFailures.get();
    }

    /// Hand-overs refused because the retained copies had reached the pending-seal cap.
    public long refusalCount() {
        return refusals.get();
    }

    /// Heap copies dropped past the cap, their pending ranges left to be rebuilt from the WAL.
    public long spillCount() {
        return spills.get();
    }

    /// Heap bytes currently retained for segments not yet sealed (WAL-backed ranges hold none).
    public long pendingBytes() {
        return pendingBytes.get();
    }

    private boolean durable(String streamName, int partition) {
        return wals.containsKey(PartitionKey.partitionKey(streamName, partition));
    }

    private Option<AppendLog> walOf(PendingSegment segment) {
        return walOf(segment.streamName(), segment.partition());
    }

    private Option<AppendLog> walOf(String streamName, int partition) {
        return option(wals.get(PartitionKey.partitionKey(streamName, partition)));
    }

    /// A heap copy may be dropped only when the WAL already holds its whole range durably.
    private boolean spillable(PendingSegment segment) {
        return walOf(segment).map(wal -> segment.endOffset() <= wal.durableOffset())
                    .or(false);
    }

    /// With a WAL behind the partition the hand-over always succeeds: the new segment joins with its heap
    /// copy, then copies are dropped oldest-first — the new one last — until the heap is back under the cap.
    private Unit admitSpilling(PendingSegment segment) {
        var seals = sealsFor(segment);

        pendingBytes.addAndGet(segment.bytes());
        seals.join(segment);
        spillOverCap(seals, segment);
        maybeDrain(seals);

        return unit();
    }

    private void spillOverCap(PendingSeals seals, PendingSegment admitted) {
        var dropped = 0;

        for (var candidate : seals.queue()) {
            if (pendingBytes.get() <= pendingCapBytes) {
                break;
            }

            if (spillable(candidate) && releaseCopy(candidate)) {
                dropped++;
            }
        }

        reportSpill(admitted, dropped);
    }

    private void reportSpill(PendingSegment admitted, int dropped) {
        if (dropped == 0) {
            spilling.set(false);

            return;
        }

        var total = spills.addAndGet(dropped);

        if (spilling.compareAndSet(false, true)) {
            log.warn("Pending-seal heap cap {} reached at {}/{} offsets [{}-{}]: dropped {} heap copy(ies), oldest first, "
                    + "keeping their pending ranges — those seals rebuild from the WAL on their next attempt "
                    + "(spill {} on this node); appends are not refused",
                     pendingCapBytes,
                     admitted.streamName(),
                     admitted.partition(),
                     admitted.startOffset(),
                     admitted.endOffset(),
                     dropped,
                     total);
        }
    }

    private Result<Unit> reserve(PendingSegment segment) {
        var before = pendingBytes.getAndUpdate(current -> admitted(current, segment.bytes()));

        return before < pendingCapBytes
               ? accepted()
               : refuse(segment, before);
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

    private Result<Unit> refuse(PendingSegment segment, long retained) {
        var count = refusals.incrementAndGet();

        if (refusing.compareAndSet(false, true)) {
            log.warn("Pending-seal cap reached: {} bytes retained for unsealed segments (cap {}); {}/{} has no WAL, so the "
                    + "heap copy is the only holder — refusing to take offsets [{}-{}]; the ring keeps them and refuses "
                    + "appends that need their room until sealing catches up (refusal {})",
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
        var seals = sealsFor(segment);

        seals.join(segment);
        maybeDrain(seals);

        return unit();
    }

    private PendingSeals sealsFor(PendingSegment segment) {
        return pending.computeIfAbsent(PartitionKey.partitionKey(segment.streamName(), segment.partition()),
                                       _ -> PendingSeals.pendingSeals());
    }

    /// Starts a seal when the run is ready, otherwise makes sure the partition's age timer is armed. After [#close],
    /// nothing.
    private void maybeDrain(PendingSeals seals) {
        if (closed.get()) {
            return;
        }

        option(seals.queue().peek()).onPresent(oldest -> drainOrWait(seals, oldest));
    }

    private void drainOrWait(PendingSeals seals, PendingSegment oldest) {
        if (ready(seals, oldest)) {
            drain(seals);
        } else {
            armAgeTimer(seals, oldest);
        }
    }

    private boolean ready(PendingSeals seals, PendingSegment oldest) {
        return asked(seals, oldest) || underPressure() || full(seals) || aged(oldest);
    }

    /// A reader asked for an offset of the run or one behind it, or the node is stopping.
    private boolean asked(PendingSeals seals, PendingSegment oldest) {
        return closing.get() || oldest.startOffset() <= seals.flushThrough()
                                                             .get();
    }

    /// The heap copies of the whole sealer hold half the pending cap.
    private boolean underPressure() {
        return pendingBytes.get() >= pendingCapBytes / 2;
    }

    private boolean full(PendingSeals seals) {
        return seals.queuedBytes()
                    .get() >= thresholds.runBytes() || seals.queuedEvents()
                                                            .get() >= thresholds.runEvents();
    }

    private boolean aged(PendingSegment oldest) {
        return System.nanoTime() - oldest.enqueuedAt() >= thresholds.runAge()
                                                                    .nanos();
    }

    /// One timer per partition, at the oldest entry's `enqueuedAt + seal_run_age` (monotonic nanoseconds). A timer armed
    /// for an older entry fires no later than the current oldest's deadline, and its [#maybeDrain] re-arms for that one.
    private void armAgeTimer(PendingSeals seals, PendingSegment oldest) {
        if (!seals.ageTimerArmed().compareAndSet(false, true)) {
            return;
        }

        var delay = Math.max(0,
                             oldest.enqueuedAt() + thresholds.runAge().nanos() - System.nanoTime());

        ageTimersScheduled.incrementAndGet();
        seals.ageTimer().set(some(SharedScheduler.schedule(() -> ageReached(seals), timeSpan(delay).nanos())));
        if (closed.get()) {
            seals.cancelTimers();
        }
    }

    private void ageReached(PendingSeals seals) {
        seals.ageTimerArmed().set(false);
        maybeDrain(seals);
    }

    private void drain(PendingSeals seals) {
        if (seals.inFlight().compareAndSet(false, true)) {
            hop(seals, () -> sealHead(seals));
        }
    }

    /// The drain's trampoline (#1234): every step — the first seal of a drain, the next head after a seal, a
    /// new retry cycle — runs as its own task on a fresh stack. A sink that completes synchronously used to make
    /// `sealed -> sealHead -> seal -> sealed` recurse on ONE stack, one frame group per queued segment, until a
    /// `StackOverflowError` was swallowed inside a promise callback with the in-flight flag still set: the
    /// partition never sealed again, silently. Stack depth no longer depends on the backlog, and any Throwable
    /// a step lets escape is caught here, logged, counted and the flag released ([#drainCrashed]).
    private void hop(PendingSeals seals, ThrowingFn0<Unit> step) {
        Promise.lift(Causes::fromThrowable, step).onFailure(cause -> drainCrashed(seals, cause));
    }

    /// Runs holding the partition's in-flight flag.
    private Unit sealHead(PendingSeals seals) {
        var run = runAtHead(seals);

        if (run.isEmpty()) {
            release(seals);
        } else {
            sealWithRetry(seals, run, false);
        }

        return unit();
    }

    /// The head and every entry that continues it (`next.start == last.end + 1`) while the block stays within
    /// `seal_merge_bytes`; a head larger than that seals alone. A gap ends the run.
    private List<PendingSegment> runAtHead(PendingSeals seals) {
        var run = new ArrayList<PendingSegment>();
        var bytes = 0L;

        for (var entry : seals.queue()) {
            if (!run.isEmpty() && (entry.startOffset() != run.getLast().endOffset() + 1 || bytes + entry.bytes() > thresholds.mergeBytes())) {
                break;
            }

            run.add(entry);
            bytes += entry.bytes();
        }

        return List.copyOf(run);
    }

    /// Absorbing the crash is design-out, not loss: the segment that was being sealed is still queued (it
    /// leaves the queue only once sealed), so releasing the flag and draining again after [#CYCLE_PAUSE]
    /// resumes exactly where the drain stopped. What must never happen is the flag staying set.
    private void drainCrashed(PendingSeals seals, Cause cause) {
        var crashes = drainCrashes.incrementAndGet();

        log.error("Seal drain step failed unexpectedly (drain crash {} on this node); the in-flight flag is released "
                 + "and the partition's pending seals resume in {}: {}",
                  crashes,
                  cyclePause,
                  cause.message());
        seals.inFlight().set(false);
        scheduleRetry(seals, () -> maybeDrain(seals));
    }

    /// The handle is kept so [#close] can cancel it (#1875).
    private void scheduleRetry(PendingSeals seals, Runnable retry) {
        seals.retryTimer().set(some(SharedScheduler.schedule(retry, cyclePause)));
        if (closed.get()) {
            seals.cancelTimers();
        }
    }

    /// The re-check after clearing the flag closes the window where a segment was queued after [#sealHead]
    /// saw an empty queue but before the flag was cleared: its [#drain] lost the flag race to us.
    private void release(PendingSeals seals) {
        seals.inFlight().set(false);
        if (!seals.queue().isEmpty()) {
            maybeDrain(seals);
        }
    }

    /// The retained copy is released in [#released], a DEPENDENT continuation of the seal promise, which the
    /// [SegmentSink] contract resolves only once the segment is readable (for [StorageSegmentSink]: after its
    /// index update). The order index-then-release is therefore a data dependency of this chain, not a matter
    /// of which callback happens to be scheduled first.
    ///
    /// Every attempt but the first of a run is a retry, refused once the sealer is closed: the first attempt of a run
    /// that started before [#close] still runs (the [#flushAll] seal), nothing failed is tried again.
    private Unit sealWithRetry(PendingSeals seals, List<PendingSegment> run, boolean retry) {
        var first = new AtomicBoolean(!retry);

        sealRetry.execute(() -> attempt(seals,
                                        run,
                                        first.getAndSet(false)),
                          _ -> !closed.get())
                 .map(_ -> released(seals, run))
                 .onResult(outcome -> hop(seals,
                                          () -> afterAttempt(seals, run, outcome)));

        return unit();
    }

    private Unit afterAttempt(PendingSeals seals, List<PendingSegment> run, Result<Unit> outcome) {
        outcome.onFailure(cause -> retryCycleExhausted(seals, run, cause)).onSuccess(_ -> release(seals));

        return unit();
    }

    private Promise<Unit> attempt(PendingSeals seals, List<PendingSegment> run, boolean first) {
        if (seals.cancelled().get()) {
            return SegmentError.General.SEAL_CANCELLED.promise();
        }

        return closed.get() && !first
               ? SegmentError.General.SEALER_CLOSED.promise()
               : runToSeal(run).async()
                          .flatMap(this::sealGuarded)
                          .onFailure(cause -> recordFailure(run, cause));
    }

    /// A sink that THROWS instead of failing its promise must still fail this attempt: retries run on the
    /// scheduler's thread, where an escaping exception would leave the retry — and the drain — hanging.
    private Promise<Unit> sealGuarded(SealedSegment segment) {
        return Result.lift(Causes::fromThrowable,
                           () -> sink.seal(segment,
                                           walOf(segment.streamName(),
                                                 segment.partition())))
                     .fold(Cause::<Unit> promise, promise -> promise);
    }

    /// One segment from the run's entries in offset order, each from its own source (F-26): an entry spilled past the
    /// cap is durable in the WAL and is read from exactly ITS range, while a later entry of the same run may not be
    /// written there yet, so the merged span is never read from the WAL in one piece.
    private Result<SealedSegment> runToSeal(List<PendingSegment> run) {
        return Result.allOf(run.stream().map(this::segmentToSeal).toList()).map(SegmentSealer::merged);
    }

    private static SealedSegment merged(List<SealedSegment> parts) {
        var first = parts.getFirst();
        var buffer = ByteBuffer.allocate(parts.stream().mapToInt(part -> part.serializedEvents().length).sum());

        parts.forEach(part -> buffer.put(part.serializedEvents()));

        return SealedSegment.sealedSegment(first.streamName(),
                                           first.partition(),
                                           first.startOffset(),
                                           parts.getLast().endOffset(),
                                           parts.stream().mapToInt(SealedSegment::eventCount).sum(),
                                           parts.stream()
                                                .mapToLong(SealedSegment::minTimestamp)
                                                .reduce(first.minTimestamp(),
                                                        Math::min),
                                           parts.stream()
                                                .mapToLong(SealedSegment::maxTimestamp)
                                                .reduce(first.maxTimestamp(),
                                                        Math::max),
                                           buffer.array());
    }

    /// The heap copy while it is retained; after a spill, the segment rebuilt from exactly its WAL range.
    private Result<SealedSegment> segmentToSeal(PendingSegment segment) {
        return segment.heapCopy()
                      .get()
                      .fold(() -> rebuildFromWal(segment),
                            Result::success);
    }

    private Result<SealedSegment> rebuildFromWal(PendingSegment segment) {
        return walOf(segment).toResult(new SegmentError.WalRangeMissing(segment.streamName(),
                                                                        segment.partition(),
                                                                        segment.startOffset(),
                                                                        segment.endOffset(),
                                                                        0))
                    .flatMap(wal -> WalRangeReader.readExactRange(wal,
                                                                  segment.streamName(),
                                                                  segment.partition(),
                                                                  segment.startOffset(),
                                                                  segment.endOffset()))
                    .map(events -> buildSegment(segment.streamName(),
                                                segment.partition(),
                                                events));
    }

    private void recordFailure(List<PendingSegment> run, Cause cause) {
        var failures = sealFailures.incrementAndGet();
        var segment = run.getFirst();

        if (closed.get()) {
            log.debug("Sealing {}/{} from offset {} failed after the sealer closed (seal failure {}): {}",
                      segment.streamName(),
                      segment.partition(),
                      segment.startOffset(),
                      failures,
                      cause.message());

            return;
        }

        log.warn("Sealing {}/{} offsets [{}-{}] failed (seal failure {} on this node); the segment stays pending and "
                + "is retried, and the WAL keeps its offsets meanwhile: {}",
                 segment.streamName(),
                 segment.partition(),
                 segment.startOffset(),
                 run.getLast().endOffset(),
                 failures,
                 cause.message());
    }

    /// Dequeue the sealed run's entries together and release their heap bytes, if they still hold any: a spill or a
    /// stream deletion may already have released them, and [#releaseCopy] releases each copy exactly once.
    private Unit released(PendingSeals seals, List<PendingSegment> run) {
        run.forEach(entry -> released(seals, entry));

        return unit();
    }

    private void released(PendingSeals seals, PendingSegment entry) {
        seals.leave(entry);
        releaseCopy(entry);
    }

    private boolean releaseCopy(PendingSegment segment) {
        var hadCopy = segment.dropCopy();

        if (hadCopy) {
            pendingBytes.addAndGet(-segment.bytes());
        }

        return hadCopy;
    }

    private void cancel(PartitionKey key) {
        option(pending.remove(key)).onPresent(seals -> cancelSeals(key, seals));
    }

    private void cancelSeals(PartitionKey key, PendingSeals seals) {
        seals.cancelled().set(true);
        seals.cancelTimers();
        var dropped = Stream.generate(seals.queue()::poll).takeWhile(Objects::nonNull).toList();
        var bytes = dropped.stream().filter(this::releaseCopy).mapToLong(PendingSegment::bytes).sum();

        log.info("Stream {} deleted: cancelled {} pending seal(s) of partition {}, releasing {} heap bytes",
                 key.streamName(),
                 dropped.size(),
                 key.partition(),
                 bytes);
    }

    /// Absorbing the exhausted cycle is design-out, not loss: the segment stays at the head of its queue (so
    /// nothing later in the partition is sealed past it), its retained copy and the WAL both still hold its
    /// offsets, and a fresh retry cycle starts after [#CYCLE_PAUSE].
    private Unit retryCycleExhausted(PendingSeals seals, List<PendingSegment> run, Cause cause) {
        if (seals.cancelled().get() || closed.get()) {
            release(seals);

            return unit();
        }

        var segment = run.getFirst();

        log.error("Sealing {}/{} offsets [{}-{}] failed through a retry cycle of up to {} attempts; the run stays "
                 + "pending, the partition's later segments wait behind it, and a new cycle starts in {}: {}",
                  segment.streamName(),
                  segment.partition(),
                  segment.startOffset(),
                  run.getLast().endOffset(),
                  cycleAttempts,
                  cyclePause,
                  cause.message());
        // A new cycle takes the same run again (I-07), never one re-formed from the queue.
        scheduleRetry(seals, () -> hop(seals, () -> sealWithRetry(seals, run, true)));

        return unit();
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

    /// One partition's segments awaiting their seal, in offset order (the run), the flag that keeps a single seal in
    /// flight for the partition, the run's totals, the highest offset a reader asked for, and the two timer handles.
    private record PendingSeals(ConcurrentLinkedQueue<PendingSegment> queue,
                                AtomicBoolean inFlight,
                                AtomicBoolean cancelled,
                                AtomicLong queuedBytes,
                                AtomicLong queuedEvents,
                                AtomicLong flushThrough,
                                AtomicBoolean ageTimerArmed,
                                AtomicReference<Option<ScheduledFuture<?>>> ageTimer,
                                AtomicReference<Option<ScheduledFuture<?>>> retryTimer) {
        static PendingSeals pendingSeals() {
            return new PendingSeals(new ConcurrentLinkedQueue<>(),
                                    new AtomicBoolean(false),
                                    new AtomicBoolean(false),
                                    new AtomicLong(0),
                                    new AtomicLong(0),
                                    new AtomicLong(-1),
                                    new AtomicBoolean(false),
                                    new AtomicReference<>(none()),
                                    new AtomicReference<>(none()));
        }

        boolean covers(long offset) {
            return queue.stream()
                        .anyMatch(pendingSegment -> pendingSegment.covers(offset));
        }

        Unit join(PendingSegment segment) {
            queue.add(segment);
            queuedBytes.addAndGet(segment.bytes());
            queuedEvents.addAndGet(segment.events());

            return unit();
        }

        Unit leave(PendingSegment segment) {
            if (queue.remove(segment)) {
                queuedBytes.addAndGet(-segment.bytes());
                queuedEvents.addAndGet(-segment.events());
            }

            return unit();
        }

        Unit cancelTimers() {
            ageTimer.get().onPresent(timer -> timer.cancel(false));
            retryTimer.get().onPresent(timer -> timer.cancel(false));

            return unit();
        }

        int armedTimers() {
            return (int) Stream.of(ageTimer.get(),
                                   retryTimer.get())
                               .filter(timer -> timer.filter(scheduled -> !scheduled.isDone())
                                                     .isPresent())
                               .count();
        }
    }

    /// A pending range and, until it is spilled or released, its heap copy. `bytes` is the copy's size,
    /// measured once ([SealedSegment#serializedEvents] copies on every call).
    /// `enqueuedAt` is the hand-over's monotonic time, the start of its age.
    private record PendingSegment(String streamName,
                                  int partition,
                                  long startOffset,
                                  long endOffset,
                                  AtomicReference<Option<SealedSegment>> heapCopy,
                                  int bytes,
                                  long enqueuedAt) {
        static PendingSegment retained(SealedSegment segment) {
            return new PendingSegment(segment.streamName(),
                                      segment.partition(),
                                      segment.startOffset(),
                                      segment.endOffset(),
                                      new AtomicReference<>(some(segment)),
                                      segment.serializedEvents().length,
                                      System.nanoTime());
        }

        long events() {
            return endOffset - startOffset + 1;
        }

        /// True only for the one call that actually dropped the copy.
        boolean dropCopy() {
            return heapCopy.getAndSet(none())
                           .isPresent();
        }

        boolean covers(long offset) {
            return startOffset <= offset && offset <= endOffset;
        }
    }
}
