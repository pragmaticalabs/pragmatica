/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.net.tcp.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Schedules certificate renewal at 40% of remaining validity (60% remaining). Built on an
/// explicit FSM with four states:
/// - `Healthy` — timer scheduled to fire at renewal time.
/// - `Renewing` — `issueCertificate` call is in flight.
/// - `RetryBackoff` — last renewal failed; exponential-backoff retry scheduled.
/// - `Stopped` — terminal, further events ignored.
///
/// Retry strategy: exponential backoff starting at 5 minutes, capped at 4 hours.
public final class CertificateRenewalScheduler {
    private static final Logger log = LoggerFactory.getLogger(CertificateRenewalScheduler.class);
    private static final long RENEWAL_NUMERATOR = 40;
    private static final long RENEWAL_DENOMINATOR = 100;
    private static final long INITIAL_RETRY_MINUTES = 5;
    private static final long MAX_RETRY_MINUTES = 240;
    private static final double RETRY_MULTIPLIER = 3.0;

    /// Public-facing status enum derived from the current FSM state.
    public enum RenewalStatus {
        /// Pre-start: scheduler constructed but `start()` not yet invoked.
        INITIALIZING,
        /// Last renewal succeeded; timer scheduled for the next renewal.
        HEALTHY,
        /// Renewal attempt currently in flight.
        RENEWING,
        /// Last renewal failed; exponential-backoff retry scheduled.
        FAILED,
        /// `stop()` was invoked; terminal state, no further renewals.
        STOPPED
    }

    /// Events driving the scheduler FSM. None cross module boundaries.
    public sealed interface RenewalEvent {
        record Start() implements RenewalEvent {}

        record Tick() implements RenewalEvent {}

        record RenewalSucceeded(CertificateBundle bundle) implements RenewalEvent {}

        record RenewalFailed(String reason) implements RenewalEvent {}

        record Stop() implements RenewalEvent {}
    }

    /// Shared runtime context. Holds configuration + the cross-transition mutable bookkeeping.
    /// The `Fsm` reference and every data-free state singleton are built inside the constructor
    /// via the constructor-driven initial-state factory in [`certificateRenewalScheduler`].
    static final class Context {
        final Fsm<SchedulerState, RenewalEvent> fsm;
        final CertificateProvider provider;
        final String nodeId;
        final String hostname;
        final Consumer<CertificateBundle> renewalCallback;
        final AtomicReference<Instant> currentNotAfter;
        final AtomicReference<Instant> lastRenewalAt = new AtomicReference<>(Instant.now());

        final AtomicReference<Option<ScheduledFuture<?>>> scheduledTask = new AtomicReference<>(Option.none());

        /// Raised inside `timerLock` by `Stopped.onEntry`, in the same critical section that drains
        /// `scheduledTask` — see `markTerminatedAndDrain`.
        final AtomicBoolean terminated = new AtomicBoolean(false);

        /// Guards the PAIRING of `terminated` with `scheduledTask`. They are two separate
        /// references, so no arrangement of atomics makes "is this scheduler terminal" and "what is
        /// armed" a single observation, and a check-then-store leaves a window in both directions.
        /// Arming, draining and marking terminal all take this lock. Cold path: a timer is armed
        /// once per renewal cycle, never per request.
        final Object timerLock = new Object();

        /// Monotonic arming epoch. Every hook that arms a timer opens a new epoch, captures it, and
        /// hands that value back to `armScheduledTask`.
        ///
        /// Why an epoch and not just the lock. `Healthy.onEntry` SCHEDULES before it arms, and on the
        /// immediate branch the delay is zero — so the tick can fire and drive
        /// `Healthy → Renewing → RetryBackoff` to completion, arming the real retry, while the
        /// original hook is still between its `schedule` and its `armScheduledTask`. That late arm is
        /// STALE. The lock makes the two arms atomic with respect to each other; it cannot say which
        /// is NEWER, and without that a stale arm displaces and cancels the live retry — renewal then
        /// stops permanently while the status surface still reports FAILED. Strictly worse than the
        /// orphan it was meant to fix.
        final AtomicLong epoch = new AtomicLong(0);

        final Idle idle;
        final Healthy healthy;
        final Renewing renewing;
        final Stopped stopped;

        // RetryBackoff is data-carrying (retryCount) — fresh instance per failure.
        Context(Fsm<SchedulerState, RenewalEvent> fsm,
                CertificateProvider provider,
                String nodeId,
                String hostname,
                Consumer<CertificateBundle> renewalCallback,
                Instant initialNotAfter) {
            this.fsm = fsm;
            this.provider = provider;
            this.nodeId = nodeId;
            this.hostname = hostname;
            this.renewalCallback = renewalCallback;
            this.currentNotAfter = new AtomicReference<>(initialNotAfter);
            this.idle = new Idle(this);
            this.healthy = new Healthy(this);
            this.renewing = new Renewing(this);
            this.stopped = new Stopped(this);
        }

        void cancelScheduledTask() {
            synchronized (timerLock) {
                scheduledTask.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
            }
        }

        /// Marks the scheduler terminal and drains in ONE critical section. Split across two, a task
        /// armed between the mark and the drain is stored by a thread that saw `terminated` false
        /// and then missed by a drain that has already run — the leaked timer of #1191.
        void markTerminatedAndDrain() {
            synchronized (timerLock) {
                terminated.set(true);
                epoch.incrementAndGet();
                scheduledTask.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
            }
        }

        /// Opens a new arming epoch and returns it. Called at the TOP of every hook that arms, before
        /// it schedules anything, so a hook overtaken between scheduling and arming is detectable.
        ///
        /// THE ORDERING IS THE GUARANTEE, NOT A CONVENTION. `openArmingEpoch()` must precede
        /// `SharedScheduler.schedule(...)` at every site. An overtaking thread cannot exist until
        /// `schedule` returns, and `schedule` runs after this call — so the overtaker is CAUSALLY
        /// downstream of this hook's epoch and necessarily draws a higher number. Move this call
        /// below the schedule and the guarantee dies silently, with no test to catch it.
        ///
        /// `configureShortValidity` is the one arming path NOT causally downstream, because it is
        /// invoked externally rather than by a transition: a hook already past its CAS but not yet
        /// at its own `openArmingEpoch()` can draw a HIGHER epoch afterwards and displace a freshly
        /// configured timer. Dev-mode only — production never calls it — and the clean fix is to
        /// dispatch an event rather than arm out of band, as the note on that method already says.
        long openArmingEpoch() {
            synchronized (timerLock) {
                return epoch.incrementAndGet();
            }
        }

        /// Stores `task` as the live timer, unless the scheduler has already reached `Stopped`, in
        /// which case the task is cancelled instead of stored. Any future this DISPLACES is
        /// cancelled rather than orphaned.
        ///
        /// The mechanism of #1191, stated correctly. `Fsm.tryAdvance` returns early when the CAS
        /// fails, so a LOSING transition runs no hooks at all — it is not the loser that arms.
        /// Hooks run AFTER and OUTSIDE the CAS, so two WINNERS overtake each other: a thread that
        /// has won `Renewing → RetryBackoff` may still sit between the CAS and its `onEntry` while a
        /// second thread wins `RetryBackoff → Stopped` and completes the drain; the first then arms
        /// onto a stopped scheduler. The CAS serialises STATE CHANGES, never the hooks after it.
        ///
        /// Why a lock rather than the two atomics. `terminated` and `scheduledTask` are separate
        /// references, so check-then-store leaves a window in both directions: a concurrent arm can
        /// orphan the future it displaces, and the holder can be observably non-empty after `stop()`
        /// has returned. One lock across arming, draining and the terminal mark removes both; a
        /// re-check after an unlocked store removes neither.
        ///
        /// Scoped to this scheduler on purpose; #1191 is FSM-wide and its general fix is a design
        /// decision about the FSM. Do NOT read that scoping as "the others are safe":
        /// `LeaderElectionState.AwaitingKvSync.onEntry` schedules and stores the same way and is
        /// exposed to the same overtake. Its `onCasLost` override does not help here — `tryAdvance`
        /// calls that hook only on the CAS-LOSS path, and this race is between two winners.
        void armScheduledTask(ScheduledFuture<?> task, long armingEpoch) {
            synchronized (timerLock) {
                // A STALE arm cancels ITSELF, never the resident task. Cancelling the resident one
                // would kill a live retry armed by the hook that overtook this one.
                if (terminated.get() || epoch.get() != armingEpoch) {
                    task.cancel(false);
                    return;
                }

                scheduledTask.getAndSet(Option.some(task))
                             .onPresent(displaced -> displaced.cancel(false));
            }
        }

        void dispatch(RenewalEvent event) {
            fsm.dispatch(event);
        }
    }

    public sealed interface SchedulerState extends FsmState<SchedulerState, RenewalEvent> permits Idle, Healthy, Renewing, RetryBackoff, Stopped {}

    record Idle(Context ctx) implements SchedulerState {
        @Override
        public void handle(RenewalEvent event, TransitionRequest<SchedulerState, RenewalEvent> tx) {
            switch (event) {
                case RenewalEvent.Start _ -> tx.transitionTo(ctx.healthy);
                case RenewalEvent.Stop _ -> tx.transitionTo(ctx.stopped);
                default -> tx.ignore();
            }
        }
    }

    record Healthy(Context ctx) implements SchedulerState {
        @Override
        public void onEntry() {
            var delay = calculateRenewalDelay(ctx.currentNotAfter.get());
            // Both branches schedule a tick AND store the future on the context — the immediate
            // (delay <= 0) branch must NOT skip the store, otherwise stale ticks fire after a
            // transition out of `Healthy` and the cancellation in `onExit` becomes a no-op.
            var armingEpoch = ctx.openArmingEpoch();
            var scheduleDelay = delay.isNegative() || delay.isZero()
                                ? TimeSpan.timeSpan(0).millis()
                                : TimeSpan.timeSpan(delay.toMillis()).millis();
            var task = SharedScheduler.schedule(() -> ctx.dispatch(new RenewalEvent.Tick()),
                                                scheduleDelay);

            ctx.armScheduledTask(task, armingEpoch);
            if (delay.isNegative() || delay.isZero()) {
                log.info("Certificate validity window passed — renewing immediately");
            } else {
                log.info("Next certificate renewal in {}", formatDuration(delay));
            }
        }

        @Override
        public void onExit() {
            ctx.cancelScheduledTask();
        }

        @Override
        public void handle(RenewalEvent event, TransitionRequest<SchedulerState, RenewalEvent> tx) {
            switch (event) {
                case RenewalEvent.Tick _ -> tx.transitionTo(ctx.renewing);
                case RenewalEvent.Stop _ -> tx.transitionTo(ctx.stopped);
                default -> tx.ignore();
            }
        }
    }

    record Renewing(Context ctx) implements SchedulerState {
        @Override
        public void onEntry() {
            log.info("Renewing certificate for node {}", ctx.nodeId);
            ctx.provider.issueCertificate(ctx.nodeId, ctx.hostname)
                        .onSuccess(bundle -> ctx.dispatch(new RenewalEvent.RenewalSucceeded(bundle)))
                        .onFailure(cause -> ctx.dispatch(new RenewalEvent.RenewalFailed(cause.message())));
        }

        @Override
        public void handle(RenewalEvent event, TransitionRequest<SchedulerState, RenewalEvent> tx) {
            switch (event) {
                case RenewalEvent.RenewalSucceeded rs -> tx.transitionTo(ctx.healthy,
                                                                         () -> applySuccess(ctx, rs.bundle()));
                case RenewalEvent.RenewalFailed rf -> tx.transitionTo(new RetryBackoff(ctx, 1),
                                                                      () -> logFailure(rf.reason(), 1));
                case RenewalEvent.Stop _ -> tx.transitionTo(ctx.stopped);
                default -> tx.ignore();
            }
        }
    }

    record RetryBackoff(Context ctx, int retryCount) implements SchedulerState {
        @Override
        public void onEntry() {
            var delayMinutes = calculateRetryDelay(retryCount);
            var jitteredMs = JitterUtil.applyJitter(delayMinutes * 60_000L,
                                                    JitterUtil.MIN_FACTOR_DEFAULT,
                                                    JitterUtil.MAX_FACTOR_DEFAULT);

            log.error("Scheduling certificate renewal retry #{} in {}ms (base {} minutes)",
                      retryCount,
                      jitteredMs,
                      delayMinutes);
            var armingEpoch = ctx.openArmingEpoch();
            var task = SharedScheduler.schedule(() -> ctx.dispatch(new RenewalEvent.Tick()),
                                                TimeSpan.timeSpan(jitteredMs).millis());

            ctx.armScheduledTask(task, armingEpoch);
        }

        @Override
        public void onExit() {
            ctx.cancelScheduledTask();
        }

        @Override
        public void handle(RenewalEvent event, TransitionRequest<SchedulerState, RenewalEvent> tx) {
            switch (event) {
                case RenewalEvent.Tick _ -> tx.transitionTo(ctx.renewing);
                case RenewalEvent.Stop _ -> tx.transitionTo(ctx.stopped);
                default -> tx.ignore();
            }
        }
    }

    record Stopped(Context ctx) implements SchedulerState {
        @Override
        public void onEntry() {
            // Marking terminal and draining must be ONE critical section, not two ordered steps: a
            // timer armed between them is stored by a thread that saw `terminated` false and then
            // missed by a drain that has already run (#1191). See `markTerminatedAndDrain`.
            ctx.markTerminatedAndDrain();
            log.info("Certificate renewal scheduler stopped");
        }

        @Override
        public void handle(RenewalEvent event, TransitionRequest<SchedulerState, RenewalEvent> tx) {
            tx.ignore();
        }
    }

    private final Context ctx;
    private final Fsm<SchedulerState, RenewalEvent> fsm;

    private CertificateRenewalScheduler(Context ctx, Fsm<SchedulerState, RenewalEvent> fsm) {
        this.ctx = ctx;
        this.fsm = fsm;
    }

    public static CertificateRenewalScheduler certificateRenewalScheduler(CertificateProvider provider,
                                                                          String nodeId,
                                                                          String hostname,
                                                                          Consumer<CertificateBundle> renewalCallback,
                                                                          Instant initialNotAfter) {
        var ctxHolder = new AtomicReference<Context>();
        Function<Fsm<SchedulerState, RenewalEvent>, SchedulerState> initialStateFactory = f -> buildContextAndInitialState(ctxHolder,
                                                                                                                           f,
                                                                                                                           provider,
                                                                                                                           nodeId,
                                                                                                                           hostname,
                                                                                                                           renewalCallback,
                                                                                                                           initialNotAfter);
        var fsm = Fsm.fsm("cert-renewal", nodeId, initialStateFactory);

        return new CertificateRenewalScheduler(ctxHolder.get(), fsm);
    }

    private static SchedulerState buildContextAndInitialState(AtomicReference<Context> ctxHolder,
                                                              Fsm<SchedulerState, RenewalEvent> fsm,
                                                              CertificateProvider provider,
                                                              String nodeId,
                                                              String hostname,
                                                              Consumer<CertificateBundle> renewalCallback,
                                                              Instant initialNotAfter) {
        var ctx = new Context(fsm, provider, nodeId, hostname, renewalCallback, initialNotAfter);

        ctxHolder.set(ctx);

        return ctx.idle;
    }

    public void start() {
        fsm.dispatch(new RenewalEvent.Start());
        log.info("Certificate renewal scheduler started for node {}", ctx.nodeId);
    }

    public void stop() {
        fsm.dispatch(new RenewalEvent.Stop());
    }

    public Instant currentNotAfter() {
        return ctx.currentNotAfter.get();
    }

    public long secondsUntilExpiry() {
        return Duration.between(Instant.now(),
                                ctx.currentNotAfter.get())
                       .toSeconds();
    }

    public Instant lastRenewalAt() {
        return ctx.lastRenewalAt.get();
    }

    public RenewalStatus renewalStatus() {
        return switch (fsm.current()) {
            case Healthy _ -> RenewalStatus.HEALTHY;
            case Idle _ -> RenewalStatus.INITIALIZING;
            case Renewing _ -> RenewalStatus.RENEWING;
            case RetryBackoff _ -> RenewalStatus.FAILED;
            case Stopped _ -> RenewalStatus.STOPPED;
        };
    }

    /// Test-only hook (P-NEW-I, 2026-05-21): reconfigures the scheduler so the active
    /// certificate appears to expire in `validitySeconds` from now and reschedules the
    /// renewal timer accordingly (the next Tick fires after 40% of the remaining window).
    /// Production code does not call this — exposed only for the dev-mode-gated
    /// `POST /api/certificates/configure-short-validity` endpoint used by
    /// `Strengthen-cert-rotation-trigger` integration tests.
    ///
    /// Behaviour:
    /// - Updates `currentNotAfter` to `Instant.now() + validitySeconds`.
    /// - If currently `Healthy`, cancels the in-flight timer and reschedules at the
    ///   recomputed renewal point (re-enters `Healthy` via Stop+Start would also clear
    ///   `Renewing`/`RetryBackoff` — out of scope here; a no-op when not `Healthy`).
    /// - Returns the new `notAfter` instant so the caller can surface it in the response.
    public Instant configureShortValidity(int validitySeconds) {
        var newNotAfter = Instant.now().plusSeconds(validitySeconds);

        ctx.currentNotAfter.set(newNotAfter);
        if (fsm.current() instanceof Healthy) {
            // Re-enter Healthy with the new currentNotAfter so the timer reschedules.
            // Dispatch is single-threaded on the FSM; the Tick→Renewing→Healthy round-trip
            // would also work but is needlessly invasive when we just need a fresh timer.
            ctx.cancelScheduledTask();
            var armingEpoch = ctx.openArmingEpoch();
            var delay = calculateRenewalDelay(newNotAfter);
            var scheduleDelay = delay.isNegative() || delay.isZero()
                                ? TimeSpan.timeSpan(0).millis()
                                : TimeSpan.timeSpan(delay.toMillis()).millis();
            var task = SharedScheduler.schedule(() -> ctx.dispatch(new RenewalEvent.Tick()),
                                                scheduleDelay);

            ctx.armScheduledTask(task, armingEpoch);
            log.info("Short-validity reconfiguration: certificate notAfter set to {}, next Tick in {}",
                     newNotAfter,
                     formatDuration(delay));
        } else {
            log.info("Short-validity reconfiguration: certificate notAfter set to {} (scheduler not in Healthy state — timer untouched)",
                     newNotAfter);
        }

        return newNotAfter;
    }

    // --- Shared transition-action / computation helpers ---
    private static void applySuccess(Context ctx, CertificateBundle bundle) {
        ctx.currentNotAfter.set(bundle.notAfter());
        ctx.lastRenewalAt.set(Instant.now());
        ctx.renewalCallback.accept(bundle);
        log.info("Certificate renewed, valid until {}", bundle.notAfter());
    }

    private static void logFailure(String reason, int attempt) {
        log.error("Certificate renewal failed (attempt {}): {}", attempt, reason);
    }

    private static long calculateRetryDelay(int retryCount) {
        var delayMinutes = (long)(INITIAL_RETRY_MINUTES * Math.pow(RETRY_MULTIPLIER, retryCount - 1));

        return Math.min(delayMinutes, MAX_RETRY_MINUTES);
    }

    private static Duration calculateRenewalDelay(Instant notAfter) {
        var remaining = Duration.between(Instant.now(), notAfter);

        return remaining.multipliedBy(RENEWAL_NUMERATOR)
                        .dividedBy(RENEWAL_DENOMINATOR);
    }

    private static String formatDuration(Duration d) {
        var hours = d.toHours();
        var minutes = d.toMinutesPart();

        return hours > 0
               ? hours + "h " + minutes + "m"
               : minutes + "m";
    }
}
