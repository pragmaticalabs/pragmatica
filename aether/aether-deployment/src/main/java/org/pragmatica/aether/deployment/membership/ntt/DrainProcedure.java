// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unified drain procedure (membership v2 spec §8.2). A single irreversible sequence
/// regardless of trigger source: stop accepting new app-layer work, await the
/// [`InFlightRequestTracker`] to quiesce (bounded by a grace deadline), emit one
/// SWIM `LEAVE` for accelerated peer departure, then `Runtime.halt(2)`.
///
/// **Replaces** the Phase 1 `SelfDrainCoordinator` + `ConsensusDrainCoordinator` +
/// `OrphanSelfDrainChecker` triad — all of which braided trigger detection (φ-accrual,
/// 1Hz visibility tick, orphan slot-binding check, Rabia-paused) with the actual drain
/// execution. Phase 2b extracts execution only: triggers are owned upstream
/// (`LocalQuorumWatcher` for quorum-loss; the operator/CTM path delivers a `DRAIN`
/// command on the leader↔node heartbeat per spec §7.5.4 — there is no KV drain record).
/// This class observes nothing — it just runs the procedure when called.
///
/// **State machine (single CAS guard).**
/// ```text
///   INACTIVE  ── initiate(reason) ─────▶  DRAINING  ── performExit() ──▶  EXITED
///                                             │
///                                             └── re-trigger → no-op (single-shot)
/// ```
///
/// **Invariants.**
///   - `initiate` is idempotent / single-shot: the CAS to `DRAINING` runs at most once,
///     so a flurry of triggers (quorum loss + Rabia paused + operator drain) produces
///     exactly one drain.
///   - `jvmExit` runs exactly once. The grace-deadline fork and the tracker-quiesced fork
///     both funnel through `performExit()`'s `DRAINING → EXITED` CAS. The tracker-quiesced fork
///     additionally waits on the graceful-departure push (issue #427) via a two-flag gate; the
///     grace-deadline fork ignores both flags, so the push can never gate the halt beyond grace.
///     The push gets a [DrainWindow] whose deadline is the drain start plus the grace, less a halt
///     slack (#2034), so it resolves, and reports its [DepartureOutcome], before the grace fork fires.
///   - No KV / consensus dependency. A partition victim cannot rely on consensus to drain
///     itself; the procedure runs purely off node-local state.
///   - The SWIM `LEAVE` emit is wrapped in a no-throw guard at the caller (`Runnable`).
///     If the emitter throws, the drain still progresses to exit — the node is about to
///     halt regardless.
public final class DrainProcedure {
    private static final Logger log = LoggerFactory.getLogger(DrainProcedure.class);
    private static final TimeSpan DEFAULT_DRAIN_TIMEOUT = timeSpan(30).seconds();
    /// How long before the grace deadline the departure legs must resolve (#2034): time to log the outcome and reach the
    /// quiesced-fork exit before the grace fork forces it. [taste, marked: settle by forge drain timings]
    static final TimeSpan HALT_SLACK = timeSpan(1).seconds();

    /// Default push for triggers that carry no DHT data (or hosts without a DHT layer): an
    /// already-settled success, so the quiesced-fork exit gate never waits on it (issue #427).
    private static final Function<DrainWindow, Promise<DepartureOutcome>> NO_DEPARTURE_PUSH = _ -> Promise.success(DepartureOutcome.COMPLETED);

    public enum DrainState {
        INACTIVE,
        DRAINING,
        EXITED
    }

    private final InFlightRequestTracker tracker;
    private final Runnable swimLeaveEmitter;
    private final Consumer<DrainReason> drainInitiatedEmitter;
    private final Function<DrainWindow, Promise<DepartureOutcome>> departurePush;
    private final Runnable jvmExit;
    private final TimeSpan drainTimeout;
    private final AtomicReference<DrainState> state = new AtomicReference<>(DrainState.INACTIVE);
    /// Why this node is draining, set by the single winning [#initiate] BEFORE anything the drain triggers
    /// (the drainee's consensus going passive happens synchronously inside it). Null until initiated.
    private final AtomicReference<DrainReason> initiatedReason = new AtomicReference<>();
    /// Two-condition exit gate (issue #427): [#performExit] via the quiesced fork runs only once BOTH
    /// the in-flight tracker has drained AND the graceful-departure push has settled (acks in, or its
    /// own bounded budget expired). The grace-deadline fork ([#onGraceExpired]) ignores both flags —
    /// it is the hard backstop, so the push can never gate the halt beyond the grace window.
    private final AtomicBoolean trackerDrained = new AtomicBoolean(false);
    private final AtomicBoolean pushSettled = new AtomicBoolean(false);
    /// The drain clock, captured by the single winning [#initiate] (#2034): monotonic for the deadline, wall clock for the
    /// event subject.
    private volatile long drainStartNanos;
    private volatile long drainStartMillis;

    /// How the departure push ended; empty until it settles (and for ever if the grace fork halts first).
    private final AtomicReference<Option<DepartureOutcome>> departureOutcome = new AtomicReference<>(Option.none());

    private DrainProcedure(InFlightRequestTracker tracker,
                           Runnable swimLeaveEmitter,
                           Consumer<DrainReason> drainInitiatedEmitter,
                           Function<DrainWindow, Promise<DepartureOutcome>> departurePush,
                           Runnable jvmExit,
                           TimeSpan drainTimeout) {
        this.tracker = tracker;
        this.swimLeaveEmitter = swimLeaveEmitter;
        this.drainInitiatedEmitter = drainInitiatedEmitter;
        this.departurePush = departurePush;
        this.jvmExit = jvmExit;
        this.drainTimeout = drainTimeout;
    }

    /// Canonical factory. Production wiring passes
    /// `Runtime.getRuntime()::halt` (bound via lambda to `() -> Runtime.getRuntime().halt(2)`);
    /// in-JVM hosts (Forge / Ember) pass a host-managed shutdown hook. The
    /// `swimLeaveEmitter` may be a no-op runnable in Phase 2b — the wiring of the
    /// SWIM `LEAVE` acceleration belongs in Phase 6 (spec §8.2 step 3).
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Runnable jvmExit,
                                                TimeSpan drainTimeout) {
        return new DrainProcedure(tracker,
                                  swimLeaveEmitter,
                                  reason -> {},
                                  NO_DEPARTURE_PUSH,
                                  jvmExit,
                                  drainTimeout);
    }

    /// Convenience factory using the spec §14 default drain timeout (30s).
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Runnable jvmExit) {
        return new DrainProcedure(tracker,
                                  swimLeaveEmitter,
                                  reason -> {},
                                  NO_DEPARTURE_PUSH,
                                  jvmExit,
                                  DEFAULT_DRAIN_TIMEOUT);
    }

    /// Factory variant that additionally takes a best-effort `drainInitiatedEmitter`, invoked once
    /// at the INACTIVE→DRAINING transition (single-shot, preserved by the CAS gate). The node owns
    /// the emit of a `SelfDrainInitiated` cluster event there; the deployment module stays free of
    /// any `ClusterEvent` dependency by accepting only a `Consumer<DrainReason>`. A throwing emitter
    /// does not interrupt the drain.
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Consumer<DrainReason> drainInitiatedEmitter,
                                                Runnable jvmExit) {
        return new DrainProcedure(tracker,
                                  swimLeaveEmitter,
                                  drainInitiatedEmitter,
                                  NO_DEPARTURE_PUSH,
                                  jvmExit,
                                  DEFAULT_DRAIN_TIMEOUT);
    }

    /// Production factory (issue #427) that additionally takes a bounded, best-effort
    /// `departurePush` — the leaving node's supplier of a `Promise<Unit>` that pushes its held DHT
    /// chunks to their new replicas and settles when the pushes are acknowledged or its own budget
    /// expires. The quiesced-fork exit waits for it (alongside the in-flight tracker); the
    /// grace-deadline fork does not, so it never gates the halt beyond the grace window. The supplier
    /// is invoked once the in-flight tracker has QUIESCED (post-quiesce, so the chunk snapshot follows
    /// the last in-flight write); a synchronous throw is isolated and the drain proceeds as if the
    /// push had settled.
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Consumer<DrainReason> drainInitiatedEmitter,
                                                Supplier<Promise<Unit>> departurePush,
                                                Runnable jvmExit) {
        return drainProcedure(tracker,
                              swimLeaveEmitter,
                              drainInitiatedEmitter,
                              departurePush,
                              jvmExit,
                              DEFAULT_DRAIN_TIMEOUT);
    }

    /// Fully-explicit variant of the issue #427 factory with a caller-supplied drain grace window.
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Consumer<DrainReason> drainInitiatedEmitter,
                                                Supplier<Promise<Unit>> departurePush,
                                                Runnable jvmExit,
                                                TimeSpan drainTimeout) {
        return new DrainProcedure(tracker,
                                  swimLeaveEmitter,
                                  drainInitiatedEmitter,
                                  completedAfter(departurePush),
                                  jvmExit,
                                  drainTimeout);
    }

    /// The #2034 production factory: the departure push receives the [DrainWindow] (absolute deadline, drain start, halt flag)
    /// and reports its [DepartureOutcome]. Otherwise as the `Supplier` factory above, which adapts to this one with an outcome of
    /// [DepartureOutcome#COMPLETED] (Forge and Ember hosts keep using it unchanged).
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Consumer<DrainReason> drainInitiatedEmitter,
                                                Function<DrainWindow, Promise<DepartureOutcome>> departurePush,
                                                Runnable jvmExit) {
        return drainProcedure(tracker,
                              swimLeaveEmitter,
                              drainInitiatedEmitter,
                              departurePush,
                              jvmExit,
                              DEFAULT_DRAIN_TIMEOUT);
    }

    /// Fully-explicit variant of the #2034 factory with a caller-supplied drain grace window.
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Consumer<DrainReason> drainInitiatedEmitter,
                                                Function<DrainWindow, Promise<DepartureOutcome>> departurePush,
                                                Runnable jvmExit,
                                                TimeSpan drainTimeout) {
        return new DrainProcedure(tracker, swimLeaveEmitter, drainInitiatedEmitter, departurePush, jvmExit, drainTimeout);
    }

    /// A legacy push knows nothing of sole copies: whatever it settles with, the outcome is [DepartureOutcome#COMPLETED].
    private static Function<DrainWindow, Promise<DepartureOutcome>> completedAfter(Supplier<Promise<Unit>> departurePush) {
        return _ -> departurePush.get()
                                 .map(_ -> DepartureOutcome.COMPLETED);
    }

    /// Kick off the §8.2 procedure. Single-shot: re-entries while `DRAINING` or `EXITED`
    /// are no-ops by design (the cluster will not change its mind once a node has
    /// decided to halt itself).
    @Contract
    public void initiate(DrainReason reason) {
        if (!state.compareAndSet(DrainState.INACTIVE, DrainState.DRAINING)) {
            return;
        }

        drainStartNanos = System.nanoTime();
        drainStartMillis = System.currentTimeMillis();
        initiatedReason.set(reason);
        log.warn("DrainProcedure: DRAINING (reason={}) — closing tracker gate, grace={}ms",
                 reason,
                 drainTimeout.millis());
        emitDrainInitiatedSafely(reason);
        tracker.setAcceptingNewWork(false);
        tracker.onAllDrained(this::onTrackerDrained);
        SharedScheduler.schedule(this::onGraceExpired, drainTimeout);
    }

    /// Kick off the bounded graceful-departure push (issue #427, D1). Started ONLY from the
    /// tracker-quiesced continuation ([#onTrackerDrained]) — after in-flight accepted requests have
    /// completed — so the chunk enumeration snapshots storage AFTER the last in-flight write lands,
    /// never concurrently with it. The [DrainWindow] bounds it (#2034); `onResult` fires on settle and
    /// flips the `pushSettled` gate. A synchronous throw from the push, or a failed push, is isolated
    /// and counts as [DepartureOutcome#COMPLETED] — the drain must proceed regardless (FER: the push's own
    /// report already named what it left behind).
    private void startDeparturePush() {
        runDeparturePushSafely().onResult(result -> onPushSettled(result.or(DepartureOutcome.COMPLETED)));
    }

    private Promise<DepartureOutcome> runDeparturePushSafely() {
        var window = DrainWindow.drainWindow(drainStartNanos + drainTimeout.nanos() - HALT_SLACK.nanos(),
                                             drainStartMillis,
                                             () -> state.get() == DrainState.EXITED);

        return Result.lift(Causes::fromThrowable,
                           () -> departurePush.apply(window))
                     .fold(DrainProcedure::kickoffFailed, promise -> promise);
    }

    private static Promise<DepartureOutcome> kickoffFailed(Cause cause) {
        log.warn("DrainProcedure: departure push kickoff failed: {} — drain proceeds", cause.message());

        return Promise.success(DepartureOutcome.COMPLETED);
    }

    /// Best-effort `SelfDrainInitiated` emit. A throwing emitter does not interrupt the drain — the
    /// emit is purely observability, not a correctness requirement (same philosophy as the SWIM
    /// LEAVE emit). Single-shot: only reached once, immediately after the CAS to DRAINING.
    private void emitDrainInitiatedSafely(DrainReason reason) {
        Result.lift(Causes::fromThrowable,
                    () -> drainInitiatedEmitter.accept(reason))
              .onFailure(cause -> log.warn("DrainProcedure: SelfDrainInitiated emit failed: {} — drain proceeds",
                                           cause.message()));
    }

    /// Whether this node is draining because the leader COMMANDED it (a planned operator or controller drain,
    /// #2014). False for `QUORUM_LOSS` and every other reason: a node that drains because it LOST quorum is
    /// exactly the node whose `QuorumLost` must stay loud.
    public boolean isCommandedDrain() {
        return state.get() != DrainState.INACTIVE && initiatedReason.get() == DrainReason.COMMANDED;
    }

    /// Whether this node is dissolving because it lost the core (`CORE_ABSENCE`, #590): a self-initiated fence, not a
    /// catch-up (#2439). False for every other reason, `COMMANDED` and `QUORUM_LOSS` included.
    public boolean isCoreAbsenceDrain() {
        return state.get() != DrainState.INACTIVE && initiatedReason.get() == DrainReason.CORE_ABSENCE;
    }

    /// Current observability state. Exposed for `/api/status` projections and tests.
    public DrainState state() {
        return state.get();
    }

    private void onTrackerDrained() {
        log.warn("DrainProcedure: in-flight tracker drained — starting departure push");
        trackerDrained.set(true);
        startDeparturePush();
    }

    /// How the departure push ended, once it settled (#2034). Empty before, and when the grace fork halted first.
    public Option<DepartureOutcome> departureOutcome() {
        return departureOutcome.get();
    }

    /// Departure-push settled (acks in, or its bounded window ended). Records and logs the outcome, then flips the second exit
    /// gate, so the outcome is known before `jvmExit` runs.
    private void onPushSettled(DepartureOutcome outcome) {
        departureOutcome.set(Option.some(outcome));
        if (outcome == DepartureOutcome.COMPLETED_WITH_DATA_AT_RISK) {
            log.warn("DrainProcedure: drain completed with data at risk — sole-copy DHT chunk(s) unconfirmed on any survivor; "
                    + "halting anyway (see the departure-push report and the dht-departure-sole-copy-at-risk event)");
        }

        pushSettled.set(true);
        maybeExit();
    }

    /// Quiesced-fork exit: both the in-flight tracker AND the departure push must have settled. The
    /// grace-deadline fork bypasses this entirely (see [#onGraceExpired]).
    private void maybeExit() {
        if (trackerDrained.get() && pushSettled.get()) {
            performExit();
        }
    }

    private void onGraceExpired() {
        if (state.get() == DrainState.DRAINING) {
            log.warn("DrainProcedure: grace expired with in-flight={} — forcing exit", tracker.count());
        }

        performExit();
    }

    @Contract
    private void performExit() {
        if (!state.compareAndSet(DrainState.DRAINING, DrainState.EXITED)) {
            return;
        }

        emitSwimLeaveSafely();
        jvmExit.run();
    }

    /// Best-effort SWIM `LEAVE` emit. A throwing emitter does not interrupt the drain —
    /// the node halts regardless (the LEAVE is an *acceleration* of peer-side suspect
    /// aging, not a correctness requirement; suspect→DEAD aging will detect the
    /// halted node anyway).
    private void emitSwimLeaveSafely() {
        Result.lift(Causes::fromThrowable, swimLeaveEmitter::run).onFailure(cause -> log.warn("DrainProcedure: SWIM LEAVE emit failed: {} — drain proceeds",
                                                                                              cause.message()));
    }
}
