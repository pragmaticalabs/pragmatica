// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Redelivers cluster events whose publish did not land (#1640).
///
/// **Why.** The cluster-events stream is EVENTUAL with a min-sync of 1, and a non-owner write-forwards to the
/// partition-0 owner. When that owner dies (for example with the leader), a forward times out as
/// [PublishOutcomeUnknown]: the event may have landed, if the owner replicated it before dying, or it may not.
/// For about 5 s until a new owner activates, every publish fails this way. Before #1640 such events were
/// logged and dropped, so `LeaderElected`, `NodeFailed` and auto-rollback decisions from the failover window
/// had no durable record.
///
/// **What.** A failed publish is buffered and retried with backoff until it lands or its age passes
/// [#RETRY_HORIZON_MS]. A retry re-sends the SAME event object, stamped once with `details.eventId` when the
/// aggregator accepted it ([ClusterEventIdentity]). An event that landed despite an unknown outcome and is then
/// sent again is therefore a duplicate with the same `eventId`, and `ClusterEventAggregator.events()` and the event
/// feed remove it on read.
///
/// **The emit gate is not re-applied.** It was satisfied when the event was produced. After a failover the
/// producing node is typically no longer the owner, and the new owner never produced the event, so
/// re-checking would drop it on its only producer.
///
/// **Bounds.** At most [#CAPACITY] events wait. A full buffer drops its OLDEST entry (CTO ruling: the newest
/// facts matter most). Every drop is counted by reason, and the next successful publish logs one aggregate WARN
/// naming the dropped event types. The first expiry, meaning the stream refused publishes for a whole horizon
/// (for example a partition flagged by divergence detection and awaiting an operator, #1596), is also logged
/// once at ERROR, because the warning announcing such a flag is itself a cluster event and cannot land.
final class ClusterEventRedelivery {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterEventRedelivery.class);
    /// Most events held for redelivery at once, waiting AND in flight together (#1653 round 2: the bound used to
    /// apply to the waiting queue only, so up to another CAPACITY retries in flight could double it).
    static final int CAPACITY = 1_024;
    /// An event not delivered within this long after its first failure is dropped as expired.
    static final long RETRY_HORIZON_MS = 5 * 60_000L;
    static final long INITIAL_BACKOFF_MS = 1_000L;
    static final long MAX_BACKOFF_MS = 8_000L;

    /// What redelivery knows about an event it gives up on: whether any attempt reported [PublishOutcomeUnknown], so the
    /// event may be in the log although the publisher never confirmed it (#752).
    enum GiveUpOutcome {
        NOT_DELIVERED,
        POSSIBLY_DELIVERED
    }

    /// Why an event was given up on.
    enum DropReason {
        OVERFLOW,
        EXPIRED,
        PERMANENT
    }

    /// One waiting event. `attempts` counts failed publishes so far; `nextAttemptAt` is when it is next due;
    /// `onGivenUp` runs if redelivery finally drops it, `onDelivered` if a retry lands.
    private record Pending(ClusterEvent event,
                           long firstFailedAt,
                           int attempts,
                           long nextAttemptAt,
                           Consumer<GiveUpOutcome> onGivenUp,
                           Runnable onDelivered,
                           boolean maybeLanded) {
        static Pending pending(ClusterEvent event,
                               long now,
                               Consumer<GiveUpOutcome> onGivenUp,
                               Runnable onDelivered,
                               boolean maybeLanded) {
            return new Pending(event, now, 1, now + INITIAL_BACKOFF_MS, onGivenUp, onDelivered, maybeLanded);
        }

        Pending failedAgain(long now) {
            return new Pending(event,
                               firstFailedAt,
                               attempts + 1,
                               now + backoff(attempts + 1),
                               onGivenUp,
                               onDelivered,
                               maybeLanded);
        }

        /// This event after an attempt that failed with `cause`: it may be in the log once any attempt's outcome was unknown.
        Pending afterFailure(Cause cause) {
            return new Pending(event,
                               firstFailedAt,
                               attempts,
                               nextAttemptAt,
                               onGivenUp,
                               onDelivered,
                               maybeLanded || cause instanceof PublishOutcomeUnknown);
        }

        GiveUpOutcome outcome() {
            return maybeLanded
                   ? GiveUpOutcome.POSSIBLY_DELIVERED
                   : GiveUpOutcome.NOT_DELIVERED;
        }

        boolean expiredAt(long now) {
            return now - firstFailedAt >= RETRY_HORIZON_MS;
        }

        boolean dueAt(long now) {
            return now >= nextAttemptAt;
        }

        private static long backoff(int attempts) {
            return Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS<< Math.min(attempts - 1, 3));
        }
    }

    private final Function<ClusterEvent, Promise<Unit>> publish;
    private final LongSupplier clock;
    private final ArrayDeque<Pending> waiting = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Map<DropReason, AtomicLong> dropped = new ConcurrentHashMap<>();
    private final Map<String, Long> droppedTypesSinceReport = new TreeMap<>();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong outcomeUnknown = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong held = new AtomicLong();
    private final Map<String, AtomicLong> failuresByCause = new ConcurrentHashMap<>();
    private final AtomicBoolean expiryReported = new AtomicBoolean();

    private ClusterEventRedelivery(Function<ClusterEvent, Promise<Unit>> publish, LongSupplier clock) {
        this.publish = publish;
        this.clock = clock;
    }

    /// `publish` attempts one publish and reports its outcome; `clock` is the time in milliseconds.
    static ClusterEventRedelivery clusterEventRedelivery(Function<ClusterEvent, Promise<Unit>> publish,
                                                         LongSupplier clock) {
        return new ClusterEventRedelivery(publish, clock);
    }

    /// First publish of a freshly produced event. A failure that a retry could fix is buffered.
    @Contract
    void deliver(ClusterEvent event) {
        deliver(event,
                () -> {});
    }

    /// As [#deliver(ClusterEvent)], and runs `onGivenUp` once if the event is finally dropped (permanent failure,
    /// expiry past the horizon, or overflow), never if it lands (#1617: the operator-warning throttle reopens a
    /// short retry window only when its event is really lost).
    @Contract
    void deliver(ClusterEvent event, Runnable onGivenUp) {
        deliver(event,
                _ -> onGivenUp.run(),
                () -> {});
    }

    /// As [#deliver(ClusterEvent, Runnable)], and runs `onDelivered` once if the event lands, whether on the first
    /// attempt or on a retry (#752: a recovery event is released only once the event it closes is known to be in the log).
    @Contract
    void deliver(ClusterEvent event, Consumer<GiveUpOutcome> onGivenUp, Runnable onDelivered) {
        accepted.incrementAndGet();
        attempt(event).onSuccess(_ -> onDelivered.run())
               .onFailure(cause -> onFirstFailure(event, cause, onGivenUp, onDelivered));
    }

    /// Re-sends every due event (or every waiting event when `all`, used when the partition's owner changes).
    /// Expired events are dropped and counted first. Events in flight leave the buffer until their attempt
    /// settles, so no event is ever sent twice at once.
    @Contract
    void redeliver(boolean all) {
        var now = clock.getAsLong();

        takeDue(now, all).forEach(pending -> retry(pending));
    }

    /// Events waiting now.
    int waiting() {
        synchronized (lock) {
            return waiting.size();
        }
    }

    long dropped(DropReason reason) {
        return dropped.computeIfAbsent(reason,
                                       _ -> new AtomicLong())
                      .get();
    }

    /// Publishes that landed, first attempts and retries alike.
    long delivered() {
        return delivered.get();
    }

    /// Events handed to [#deliver]. Every one of them is eventually delivered, dropped, or still held, so
    /// `accepted - delivered - dropped` is the number held (waiting or in flight).
    long accepted() {
        return accepted.get();
    }

    /// Events held for redelivery right now: their first publish failed, and they have neither landed nor been
    /// dropped since. Waiting and in-flight retries alike.
    long held() {
        return held.get();
    }

    /// Publish attempts started and not yet settled, first attempts and retries alike.
    long inFlight() {
        return inFlight.get();
    }

    /// Retries that were attempted.
    long retried() {
        return retried.get();
    }

    /// Publishes that failed with [PublishOutcomeUnknown] (the event may or may not have landed).
    long outcomeUnknown() {
        return outcomeUnknown.get();
    }

    /// Failed publish attempts by cause type: which failures a failover actually produces.
    Map<String, Long> failuresByCause() {
        var snapshot = new TreeMap<String, Long>();

        failuresByCause.forEach((cause, count) -> snapshot.put(cause, count.get()));

        return snapshot;
    }

    private Promise<Unit> attempt(ClusterEvent event) {
        inFlight.incrementAndGet();

        return publish.apply(event)
                      .onResultRun(inFlight::decrementAndGet)
                      .onSuccess(_ -> onDelivered())
                      .onFailure(this::countUnknown);
    }

    private Unit countUnknown(Cause cause) {
        if (cause instanceof PublishOutcomeUnknown) {
            outcomeUnknown.incrementAndGet();
        }

        failuresByCause.computeIfAbsent(causeName(cause), _ -> new AtomicLong()).incrementAndGet();

        return unit();
    }

    /// The failure's type, or for an enum constant its name: `PublishOutcomeUnknown`, `PARTITION_NOT_LOCAL`, ...
    private static String causeName(Cause cause) {
        return cause instanceof Enum<?> constant
               ? constant.name()
               : cause.getClass()
                      .getSimpleName();
    }

    private Unit onFirstFailure(ClusterEvent event,
                                Cause cause,
                                Consumer<GiveUpOutcome> onGivenUp,
                                Runnable onDelivered) {
        var pending = Pending.pending(event, clock.getAsLong(), onGivenUp, onDelivered, false).afterFailure(cause);

        return isPermanent(cause)
               ? drop(DropReason.PERMANENT, pending)
               : hold(pending);
    }

    /// Starts holding a newly failed event. When CAPACITY events are already held, the OLDEST waiting one is dropped
    /// to make room; if every held event is in flight (none waiting), the new one is dropped instead. Both are
    /// counted as OVERFLOW. `held` only rises here, under the lock, so it never exceeds CAPACITY.
    private Unit hold(Pending pending) {
        synchronized (lock) {
            if (held.get() >= CAPACITY) {
                var oldest = Option.option(waiting.pollFirst());

                if (oldest.isEmpty()) {
                    return drop(DropReason.OVERFLOW, pending);
                }

                oldest.onPresent(entry -> dropHeld(DropReason.OVERFLOW, entry));
            }

            held.incrementAndGet();
            waiting.addLast(pending);
        }

        return unit();
    }

    private void retry(Pending pending) {
        retried.incrementAndGet();
        attempt(pending.event()).onSuccess(_ -> retryLanded(pending)).onFailure(cause -> onRetryFailure(pending, cause));
    }

    private Unit retryLanded(Pending pending) {
        held.decrementAndGet();
        pending.onDelivered().run();

        return unit();
    }

    private Unit onRetryFailure(Pending attempted, Cause cause) {
        var now = clock.getAsLong();
        var pending = attempted.afterFailure(cause);

        if (isPermanent(cause)) {
            return dropHeld(DropReason.PERMANENT, pending);
        }

        return pending.expiredAt(now)
               ? expire(pending)
               : enqueue(pending.failedAgain(now));
    }

    /// A cause no retry can fix: the event itself is refused (too large for the stream), whoever owns it.
    private static boolean isPermanent(Cause cause) {
        return cause instanceof StreamError.EventTooLarge || cause == StreamError.General.EVENT_DROPPED || cause == StreamError.General.RUN_DOES_NOT_FIT;
    }

    /// Puts an already-held event back after a failed retry. It is counted in `held` already, so the bound holds.
    private Unit enqueue(Pending pending) {
        synchronized (lock) {
            waiting.addLast(pending);
        }

        return unit();
    }

    private List<Pending> takeDue(long now, boolean all) {
        var due = new ArrayList<Pending>();

        synchronized (lock) {
            var iterator = waiting.iterator();

            while (iterator.hasNext()) {
                var pending = iterator.next();

                if (pending.expiredAt(now)) {
                    iterator.remove();
                    expire(pending);
                } else if (all || pending.dueAt(now)) {
                    iterator.remove();
                    due.add(pending);
                }
            }
        }

        return due;
    }

    private Unit expire(Pending pending) {
        if (expiryReported.compareAndSet(false, true)) {
            LOG.error("ClusterEventRedelivery: a {} could not be published for {} s ({} attempts) and is dropped. The "
                     + "cluster-events stream is refusing publishes; if its partition is flagged, it waits for an "
                     + "operator, and events raised meanwhile are lost after this horizon. Reported once per node.",
                      pending.event().type(),
                      RETRY_HORIZON_MS / 1_000,
                      pending.attempts());
        }

        return dropHeld(DropReason.EXPIRED, pending);
    }

    /// Drops an event that was being held for redelivery.
    private Unit dropHeld(DropReason reason, Pending pending) {
        held.decrementAndGet();

        return drop(reason, pending);
    }

    private Unit drop(DropReason reason, Pending pending) {
        var event = pending.event();

        pending.onGivenUp().accept(pending.outcome());
        dropped.computeIfAbsent(reason, _ -> new AtomicLong()).incrementAndGet();
        synchronized (droppedTypesSinceReport) {
            droppedTypesSinceReport.merge(event.type() + "/" + reason.name(),
                                          1L,
                                          Long::sum);
        }

        return unit();
    }

    private Unit onDelivered() {
        delivered.incrementAndGet();
        var report = takeDroppedReport();

        if (!report.isEmpty()) {
            LOG.warn("ClusterEventRedelivery: cluster events were dropped since the last report, by type/reason: {}",
                     report);
        }

        return unit();
    }

    private Map<String, Long> takeDroppedReport() {
        synchronized (droppedTypesSinceReport) {
            var report = new TreeMap<>(droppedTypesSinceReport);

            droppedTypesSinceReport.clear();

            return report;
        }
    }
}
