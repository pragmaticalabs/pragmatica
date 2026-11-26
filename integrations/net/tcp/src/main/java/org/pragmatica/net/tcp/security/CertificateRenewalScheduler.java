/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.net.tcp.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.OwnerLifetime;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Schedules certificate renewal at 40% of remaining validity (60% remaining). Built on an
/// explicit FSM with four states:
/// - `Healthy` — timer scheduled to fire at renewal time; a `Reconfigure` event re-enters it on a fresh lifetime.
/// - `Renewing` — `issueCertificate` call is in flight.
/// - `RetryBackoff` — last renewal failed; exponential-backoff retry scheduled.
/// - `Stopped` — terminal, further events ignored.
///
/// Retry strategy: exponential backoff starting at 5 minutes, capped at 4 hours.
///
/// Every timer is armed through the lifetime of the state entry that arms it ([`FsmState#onEntry(OwnerLifetime)`]), never
/// kept as a handle: the lifetime ends when a successor's CAS wins, before the predecessor's `onExit`, so a hook that runs
/// late (behind the next winner's hooks) is refused and its timer never runs (#1191).
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

        /// `currentNotAfter` was reconfigured: a `Healthy` scheduler re-enters itself so its timer is armed afresh.
        record Reconfigure() implements RenewalEvent {}
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
        public void onEntry(OwnerLifetime life) {
            var delay = calculateRenewalDelay(ctx.currentNotAfter.get());
            // Both branches arm a tick through the entry's lifetime: the immediate (delay <= 0) branch must not skip
            // it. A late hook is refused by the lifetime, so no guard sits here.
            var scheduleDelay = delay.isNegative() || delay.isZero()
                                ? TimeSpan.timeSpan(0).millis()
                                : TimeSpan.timeSpan(delay.toMillis()).millis();

            life.schedule(_ -> ctx.dispatch(new RenewalEvent.Tick()),
                          scheduleDelay);
            if (delay.isNegative() || delay.isZero()) {
                log.info("Certificate validity window passed — renewing immediately");
            } else {
                log.info("Next certificate renewal in {}", formatDuration(delay));
            }
        }

        @Override
        public void handle(RenewalEvent event, TransitionRequest<SchedulerState, RenewalEvent> tx) {
            switch (event) {
                case RenewalEvent.Tick _ -> tx.transitionTo(ctx.renewing);
                case RenewalEvent.Reconfigure _ -> tx.transitionTo(ctx.healthy);
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
        public void onEntry(OwnerLifetime life) {
            var delayMinutes = calculateRetryDelay(retryCount);
            var jitteredMs = JitterUtil.applyJitter(delayMinutes * 60_000L,
                                                    JitterUtil.MIN_FACTOR_DEFAULT,
                                                    JitterUtil.MAX_FACTOR_DEFAULT);

            log.error("Scheduling certificate renewal retry #{} in {}ms (base {} minutes)",
                      retryCount,
                      jitteredMs,
                      delayMinutes);
            life.schedule(_ -> ctx.dispatch(new RenewalEvent.Tick()),
                          TimeSpan.timeSpan(jitteredMs).millis());
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
            // Nothing to drain: leaving the predecessor ended its lifetime, which cancelled its timer and refuses
            // any arm still on its way (#1191).
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
    /// - Dispatches `Reconfigure`: a `Healthy` scheduler re-enters `Healthy` on a fresh lifetime, which cancels
    ///   the old timer and arms one at the recomputed renewal point; in any other state the event is ignored
    ///   (the new `currentNotAfter` is read by the next `Healthy` entry).
    /// - Returns the new `notAfter` instant so the caller can surface it in the response.
    public Instant configureShortValidity(int validitySeconds) {
        var newNotAfter = Instant.now().plusSeconds(validitySeconds);

        ctx.currentNotAfter.set(newNotAfter);
        log.info("Short-validity reconfiguration: certificate notAfter set to {}", newNotAfter);
        fsm.dispatch(new RenewalEvent.Reconfigure());

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
