// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.isolation;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.AtomicHolder;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.concurrent.AtomicHolder.atomicHolder;


/// Per-node observer of CORE reachability, and the community tier's half of the #590 mechanism.
///
/// ## The signal, and why it is this one
/// The leader already broadcasts a `ClusterSyncPing` to the whole cluster on a `pingInterval` cadence,
/// carrying its Rabia term. A node that stops receiving those pings has lost the core. Nothing new
/// goes on the wire; what was missing is that nobody noticed the silence.
///
/// Deliberately the LEADER's broadcast rather than `SpokesmanPingLoop`'s governor-targeted ping: that
/// loop activates only once a spokesman role is assigned, and nothing currently assigns one — a signal
/// built on it could sit silent forever and read as permanent isolation.
///
/// The alternatives all fail in the same direction. A failed-`cluster.apply` streak and
/// `observedCoreEpoch` staleness are both DEMAND-driven — a community serving reads commits nothing,
/// so neither would ever fire, and the isolation would go undetected exactly while the community
/// quietly kept answering. (`KVCommand.Noop` is not an idle heartbeat; it is an on-demand
/// linearizable-barrier round.) Ping staleness ticks whether or not the community is busy.
///
/// ## Why the response must be local
/// Dissolve is normally announced by writing `GovernorAnnouncementKey` through consensus — that is, to
/// the core. A community that has LOST the core cannot complete that write, so the isolation response
/// cannot be expressed through it at all. This detector therefore drives a purely local decision, as
/// the core tier's own minority self-fence already does.
///
/// ## Per node, not per governor
/// Every node runs its own detector, mirroring `QuorumLossDetector`. A governor-only decision would
/// need intra-community coordination to reach followers and would strand them if the governor itself
/// died; per-node observation also means a partitioned SUBSET of a community fences exactly itself.
///
/// ## Discipline inherited from the core tier's fence
/// - **Arm-after-first-ping latch.** `lastPingNanos` starts EMPTY, and that emptiness is the latch —
///   there is no separate `armed` flag that could drift out of step with it. A node that has NEVER
///   heard the core is cold-starting, not isolated; without this, every community would fence itself
///   during formation.
/// - **Only ACCEPTED pings count.** The observer is invoked downstream of the collector's fencing
///   check, so a ping from a stale leader (`rabiaTerm` below the observed term) does not refresh
///   liveness. Counting it would let a partitioned-away former leader hold a community open.
/// - **Re-check rather than edge-latch.** The staleness check is a periodic tick, so a suppressed
///   evaluation is retried by construction — the #415 failure, where a deferred one-shot intent was
///   dropped and stranded the fence permanently, is not expressible here.
/// - **Fires at most once.** `fenced` is CAS-guarded. Dissolve is not an operation to run twice, and
///   the tick keeps running only to keep the observability accessors honest.
///
/// A fired fence is TERMINAL for this detector: it does not un-fence if pings resume. Recovery is a
/// re-join, which is the same posture the core tier takes, and it avoids a half-dissolved node
/// deciding on its own that it is serving again.
public final class CoreAbsenceDetector {
    private static final Logger log = LoggerFactory.getLogger(CoreAbsenceDetector.class);

    private final TimeSpan coreAbsence;
    private final TimeSpan checkInterval;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;
    /// Empty until the first accepted ping. That emptiness IS the arm-after-first-ping latch — a node
    /// that has never heard the core is cold-starting, not isolated — so there is no separate `armed`
    /// flag that could disagree with it.
    private final AtomicHolder<Long> lastPingNanos = atomicHolder();
    private final AtomicHolder<ScheduledFuture<?>> tickFuture = atomicHolder();
    private final AtomicBoolean fenced = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Consumer<CoreAbsenceIntent> listener = CoreAbsenceDetector::ignoreIntent;

    private CoreAbsenceDetector(TimeSpan coreAbsence,
                                TimeSpan checkInterval,
                                TimeSource timeSource,
                                NttTimerScheduler scheduler) {
        this.coreAbsence = coreAbsence;
        this.checkInterval = checkInterval;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory. `checkInterval` is the ping cadence: checking at the rate the signal
    /// arrives bounds detection overshoot to one interval without busy-polling.
    public static CoreAbsenceDetector coreAbsenceDetector(TimeSpan coreAbsence, TimeSpan checkInterval) {
        return new CoreAbsenceDetector(coreAbsence, checkInterval, TimeSource.system(), SharedScheduler::schedule);
    }

    /// Test factory with an explicit time source and scheduler, so firing is driven by an explicit
    /// tick rather than wall-clock advancement.
    public static CoreAbsenceDetector coreAbsenceDetector(TimeSpan coreAbsence,
                                                          TimeSpan checkInterval,
                                                          TimeSource timeSource,
                                                          NttTimerScheduler scheduler) {
        return new CoreAbsenceDetector(coreAbsence, checkInterval, timeSource, scheduler);
    }

    /// Record an ACCEPTED inbound `ClusterSyncPing`. Wired in `AetherNode` to
    /// `ClusterSyncCollector#setCorePingObserver`, which invokes it only after the ping clears
    /// term fencing.
    @Contract
    public void recordCorePing() {
        lastPingNanos.set(timeSource.nanoTime());
    }

    /// Register the consumer that performs the local dissolve. Invoked at most once.
    @Contract
    public void setCoreAbsenceListener(Consumer<CoreAbsenceIntent> newListener) {
        listener = newListener;
    }

    @Contract
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduleTick();
        }
    }

    @Contract
    public void stop() {
        running.set(false);
        tickFuture.getAndClear().onPresent(pending -> pending.cancel(false));
    }

    /// Observability — whether a core ping has ever been accepted. While `false` the fence is
    /// suppressed (cold-start guard).
    public boolean isArmed() {
        return ! lastPingNanos.isEmpty();
    }

    /// Observability — whether the local dissolve has fired.
    public boolean isFenced() {
        return fenced.get();
    }

    /// Observability — the configured window this detector fences on
    /// (`timeouts.cluster.core_absence`), so a reader of the snapshot can see how far through the
    /// countdown a node is without having to fetch its config separately.
    public TimeSpan coreAbsenceWindow() {
        return coreAbsence;
    }

    /// Observability — nanos since the last accepted core ping, or `none()` if none has ever arrived.
    public Option<Long> sinceLastCorePingNanos() {
        return lastPingNanos.get()
                            .map(last -> timeSource.nanoTime() - last);
    }

    /// Observability — nanos remaining before this node fences itself, clamped at zero. `none()`
    /// while unarmed or already fenced, both of which mean no countdown is running. This is the field
    /// an operator watches during a suspected partition.
    public Option<Long> remainingBeforeFenceNanos() {
        if (fenced.get()) {
            return Option.none();
        }

        return sinceLastCorePingNanos().map(age -> Math.max(0L, coreAbsence.nanos() - age));
    }

    @Contract
    private void scheduleTick() {
        if (!running.get()) {
            return;
        }

        tickFuture.set(scheduler.schedule(this::onTick, checkInterval));
    }

    @Contract
    private void onTick() {
        try {
            evaluate();
        } catch (Exception e) {
            log.warn("CoreAbsenceDetector tick failed: {}", e.getMessage());
        } finally {
            scheduleTick();
        }
    }

    @Contract
    private void evaluate() {
        if (fenced.get()) {
            return;
        }
        // An empty holder is the cold-start case: no ping has EVER been accepted, so there is nothing
        // to have gone stale. onPresent makes that unrepresentable rather than a guard someone can drop.
        lastPingNanos.get()
                     .map(last -> timeSource.nanoTime() - last)
                     .filter(age -> age >= coreAbsence.nanos())
                     .onPresent(this::fence);
    }

    @Contract
    private void fence(long age) {
        if (!fenced.compareAndSet(false, true)) {
            return;
        }

        var intent = CoreAbsenceIntent.coreAbsenceIntent(age, coreAbsence.nanos());

        log.warn("CORE ABSENCE fence firing: no accepted ClusterSyncPing for {} ms (threshold {} ms) — "
                + "dissolving locally. This node stops serving without writing to the core, which it cannot "
                + "reach; the core independently re-places this community's slices on a strictly longer window.",
                 intent.sinceLastPingNanos() / 1_000_000,
                 intent.thresholdNanos() / 1_000_000);
        listener.accept(intent);
    }

    @Contract
    private static void ignoreIntent(CoreAbsenceIntent intent) {
    // intentionally empty — default listener prior to wiring
    }
}
