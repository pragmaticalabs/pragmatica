// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.SwimObservation.DepartedObserved;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.pragmatica.lang.Option.option;


/// Node Topology Tracker (membership v2 spec §6, I12 — E2 Phase 1.5 simplification).
/// Per-peer one-shot departure timer that subscribes to SWIM-converged departure
/// observations and, on expiry, invokes a reconcile-trigger callback. NTT no longer
/// carries per-event records — reconciliation is fully state-derived in
/// [`LeaderReconciler`]; NTT only needs to *signal* "something changed, reconcile
/// soon".
///
/// **Mechanism only.** NTT is universal — it runs on every node and observes regardless of
/// leader status. The observation-only feature gate ([`NttObservationFlag`]) is enforced
/// upstream by the wiring layer; NTT itself does not consult the flag. The reconcile
/// trigger is intentionally a `Runnable` (no peer payload) because the post-fire reconcile
/// reads cluster state from scratch — the *fact* that a timer fired is the only datum
/// the trigger carries.
///
/// **Inputs.**
/// - [`#onSwimObservation`] — filters for `DepartedObserved`, schedules a per-peer timer
///   (idempotent via `computeIfAbsent`: a duplicate departure for an already-tracked peer
///   is a no-op; the deadline is NOT re-stamped, matching spec §6.2 "first-departure-wins").
/// - [`#onQuicReconnect`] — cancels any pending timer for that peer (the resurrection
///   signal per I7).
///
/// **Output.** Timer expiry removes the per-peer map entry and invokes the constructor-
/// injected `Runnable onReconcileNeeded` callback. The callback is expected to use a
/// CAS-debounce pattern (see [`LeaderReconciler#triggerReconcile`]) so a burst of
/// per-peer expiries collapses to at most a handful of reconcile passes.
///
/// **Observability.** [`#pendingTimerCount`] exposes the live map size for metrics.
///
/// **Concurrency.** All state is held in a single
/// `ConcurrentHashMap<NodeId, ScheduledFuture<?>>`. Every transition is expressed via
/// atomic `computeIfAbsent` / `remove` primitives so no synchronized block or lock is
/// required.
///
/// **QUIC-reconnect event source.** Stage 6 will adapt this method's `onQuicReconnect(NodeId)`
/// from `org.pragmatica.consensus.net.quic.PeerConnectivityReporter#onPeerConnected`
/// (`integrations/consensus/.../PeerConnectivityReporter.java:40`).
public final class NodeTopologyTracker {
    private static final Runnable NOOP_RECONCILE_TRIGGER = () -> {};

    private final MembershipConfig config;
    private final NttTimerScheduler scheduler;
    private final Runnable onReconcileNeeded;
    private final Map<NodeId, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    private NodeTopologyTracker(MembershipConfig config,
                                NttTimerScheduler scheduler,
                                Runnable onReconcileNeeded) {
        this.config = config;
        this.scheduler = scheduler;
        this.onReconcileNeeded = onReconcileNeeded;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`].
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config, Runnable onReconcileNeeded) {
        return new NodeTopologyTracker(config, SharedScheduler::schedule, onReconcileNeeded);
    }

    /// Test factory accepting an explicit scheduler and a no-op reconcile trigger —
    /// used by tests that want to inspect the timer map without exercising a downstream
    /// reconciler.
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config, NttTimerScheduler scheduler) {
        return new NodeTopologyTracker(config, scheduler, NOOP_RECONCILE_TRIGGER);
    }

    /// Test factory accepting both an explicit scheduler and a reconcile-trigger
    /// callback. Required for deterministic timer-fire assertions in unit tests.
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config,
                                                          NttTimerScheduler scheduler,
                                                          Runnable onReconcileNeeded) {
        return new NodeTopologyTracker(config, scheduler, onReconcileNeeded);
    }

    /// SWIM observation entry point. Only [`DepartedObserved`] starts a timer; all other
    /// observation kinds are ignored. Re-arming on duplicate departure is a no-op
    /// (`computeIfAbsent`-style guard).
    @Contract
    public void onSwimObservation(SwimObservation observation) {
        if (observation instanceof DepartedObserved departed) {
            scheduleIfAbsent(departed.peer());
        }
    }

    /// QUIC reconnect entry point. Cancels any pending timer for `peer`. Atomic
    /// remove — a single map removal cancels the still-armed future.
    @Contract
    public void onQuicReconnect(NodeId peerId) {
        option(timers.remove(peerId)).onPresent(NodeTopologyTracker::cancelFuture);
    }

    /// Count of currently-tracked entries with a not-yet-fired timer.
    public int pendingTimerCount() {
        return timers.size();
    }

    private void scheduleIfAbsent(NodeId peerId) {
        timers.computeIfAbsent(peerId, this::armTimer);
    }

    private ScheduledFuture<?> armTimer(NodeId peerId) {
        return scheduler.schedule(() -> onTimerFire(peerId), config.nttDepartureTimeout());
    }

    private void onTimerFire(NodeId peerId) {
        timers.remove(peerId);
        onReconcileNeeded.run();
    }

    @Contract
    private static void cancelFuture(ScheduledFuture<?> future) {
        future.cancel(false);
    }
}
