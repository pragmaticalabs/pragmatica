// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

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
///     both funnel through `performExit()`'s `DRAINING → EXITED` CAS.
///   - No KV / consensus dependency. A partition victim cannot rely on consensus to drain
///     itself; the procedure runs purely off node-local state.
///   - The SWIM `LEAVE` emit is wrapped in a no-throw guard at the caller (`Runnable`).
///     If the emitter throws, the drain still progresses to exit — the node is about to
///     halt regardless.
public final class DrainProcedure {
    private static final Logger log = LoggerFactory.getLogger(DrainProcedure.class);
    private static final TimeSpan DEFAULT_DRAIN_TIMEOUT = timeSpan(30).seconds();

    public enum DrainState {
        INACTIVE,
        DRAINING,
        EXITED
    }

    private final InFlightRequestTracker tracker;
    private final Runnable swimLeaveEmitter;
    private final java.util.function.Consumer<DrainReason> drainInitiatedEmitter;
    private final Runnable jvmExit;
    private final TimeSpan drainTimeout;
    private final AtomicReference<DrainState> state = new AtomicReference<>(DrainState.INACTIVE);

    private DrainProcedure(InFlightRequestTracker tracker,
                           Runnable swimLeaveEmitter,
                           java.util.function.Consumer<DrainReason> drainInitiatedEmitter,
                           Runnable jvmExit,
                           TimeSpan drainTimeout) {
        this.tracker = tracker;
        this.swimLeaveEmitter = swimLeaveEmitter;
        this.drainInitiatedEmitter = drainInitiatedEmitter;
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
        return new DrainProcedure(tracker, swimLeaveEmitter, reason -> {}, jvmExit, drainTimeout);
    }

    /// Convenience factory using the spec §14 default drain timeout (30s).
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                Runnable jvmExit) {
        return new DrainProcedure(tracker, swimLeaveEmitter, reason -> {}, jvmExit, DEFAULT_DRAIN_TIMEOUT);
    }

    /// Factory variant that additionally takes a best-effort `drainInitiatedEmitter`, invoked once
    /// at the INACTIVE→DRAINING transition (single-shot, preserved by the CAS gate). The node owns
    /// the emit of a `SelfDrainInitiated` cluster event there; the deployment module stays free of
    /// any `ClusterEvent` dependency by accepting only a `Consumer<DrainReason>`. A throwing emitter
    /// does not interrupt the drain.
    public static DrainProcedure drainProcedure(InFlightRequestTracker tracker,
                                                Runnable swimLeaveEmitter,
                                                java.util.function.Consumer<DrainReason> drainInitiatedEmitter,
                                                Runnable jvmExit) {
        return new DrainProcedure(tracker, swimLeaveEmitter, drainInitiatedEmitter, jvmExit, DEFAULT_DRAIN_TIMEOUT);
    }

    /// Kick off the §8.2 procedure. Single-shot: re-entries while `DRAINING` or `EXITED`
    /// are no-ops by design (the cluster will not change its mind once a node has
    /// decided to halt itself).
    @Contract
    public void initiate(DrainReason reason) {
        if (!state.compareAndSet(DrainState.INACTIVE, DrainState.DRAINING)) {
            return;
        }
        log.warn("DrainProcedure: DRAINING (reason={}) — closing tracker gate, grace={}ms",
                 reason,
                 drainTimeout.millis());
        emitDrainInitiatedSafely(reason);
        tracker.setAcceptingNewWork(false);
        tracker.onAllDrained(this::onTrackerDrained);
        SharedScheduler.schedule(this::onGraceExpired, drainTimeout);
    }

    /// Best-effort `SelfDrainInitiated` emit. A throwing emitter does not interrupt the drain — the
    /// emit is purely observability, not a correctness requirement (same philosophy as the SWIM
    /// LEAVE emit). Single-shot: only reached once, immediately after the CAS to DRAINING.
    private void emitDrainInitiatedSafely(DrainReason reason) {
        try {
            drainInitiatedEmitter.accept(reason);
        } catch (Throwable t) {
            log.warn("DrainProcedure: SelfDrainInitiated emit failed: {} — drain proceeds", t.getMessage());
        }
    }

    /// Current observability state. Exposed for `/api/status` projections and tests.
    public DrainState state() {
        return state.get();
    }

    private void onTrackerDrained() {
        log.warn("DrainProcedure: in-flight tracker drained — exiting");
        performExit();
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
        try {
            swimLeaveEmitter.run();
        } catch (Throwable t) {
            log.warn("DrainProcedure: SWIM LEAVE emit failed: {} — drain proceeds", t.getMessage());
        }
    }
}
