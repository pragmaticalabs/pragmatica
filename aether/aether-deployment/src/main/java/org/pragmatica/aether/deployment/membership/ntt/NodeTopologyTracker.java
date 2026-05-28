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
import org.pragmatica.swim.SwimObservation.HealthyObserved;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static org.pragmatica.lang.Option.option;


/// Node Topology Tracker (membership v2 spec §6, I12 — E2 Phase 1.6 member-set
/// tracking). Per-peer one-shot departure timer that subscribes to SWIM-converged
/// observations and, on expiry, invokes a reconcile-trigger callback. NTT also
/// maintains the authoritative cluster-membership set sourced from SWIM
/// (`HealthyObserved` adds, `DepartedObserved` removes); `self` is included
/// unconditionally. The set is the cluster-wide "who is in the cluster right
/// now" view: SWIM discovers a peer first, QUIC dials lag, Rabia voter set lags
/// more — sourcing membership from SWIM via NTT gives the freshest count and
/// the freshest provisioning seed-PEERS set.
///
/// **Mechanism only.** NTT is universal — it runs on every node and observes regardless of
/// leader status. The observation-only feature gate ([`NttObservationFlag`]) is enforced
/// upstream by the wiring layer; NTT itself does not consult the flag. The reconcile
/// trigger is intentionally a `Runnable` (no peer payload) because the post-fire reconcile
/// reads cluster state from scratch — the *fact* that a timer fired is the only datum
/// the trigger carries.
///
/// **Inputs.**
/// - [`#onSwimObservation`] —
///   - [`DepartedObserved`]: schedules a per-peer timer (idempotent via `computeIfAbsent`:
///     a duplicate departure for an already-tracked peer is a no-op; the deadline is NOT
///     re-stamped, matching spec §6.2 "first-departure-wins"); removes the peer from
///     `currentMembers`.
///   - [`HealthyObserved`]: adds the peer to `currentMembers` (idempotent via set
///     semantics); cancels any pending NTT timer for that peer. Cancellation parity
///     with QUIC reconnect treats a SWIM rejoin and a QUIC reconnect as equivalent
///     "peer is back" signals.
/// - [`#onQuicReconnect`] — cancels any pending timer for that peer (the resurrection
///   signal per I7).
///
/// **Output.** Timer expiry removes the per-peer map entry and invokes the constructor-
/// injected `Runnable onReconcileNeeded` callback. The callback is expected to use a
/// CAS-debounce pattern (see [`LeaderReconciler#triggerReconcile`]) so a burst of
/// per-peer expiries collapses to at most a handful of reconcile passes.
///
/// **Observability.** [`#pendingTimerCount`] exposes the live timer-map size;
/// [`#currentMemberCount`] / [`#currentMembers`] expose the cluster member set.
///
/// **Concurrency.** Timer state is held in a `ConcurrentHashMap<NodeId, ScheduledFuture<?>>`;
/// member-set state is a `ConcurrentHashMap.newKeySet()`. Every transition is expressed
/// via atomic `computeIfAbsent` / `remove` / `add` primitives so no synchronized block
/// or lock is required.
///
/// **QUIC-reconnect event source.** Stage 6 will adapt this method's `onQuicReconnect(NodeId)`
/// from `org.pragmatica.consensus.net.quic.PeerConnectivityReporter#onPeerConnected`
/// (`integrations/consensus/.../PeerConnectivityReporter.java:40`).
public final class NodeTopologyTracker {
    private static final Runnable NOOP_RECONCILE_TRIGGER = () -> {};

    private final MembershipConfig config;
    private final NodeId self;
    private final NttTimerScheduler scheduler;
    private final Runnable onReconcileNeeded;
    private final Map<NodeId, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final Set<NodeId> currentMembers = ConcurrentHashMap.newKeySet();

    private NodeTopologyTracker(MembershipConfig config,
                                NodeId self,
                                NttTimerScheduler scheduler,
                                Runnable onReconcileNeeded) {
        this.config = config;
        this.self = self;
        this.scheduler = scheduler;
        this.onReconcileNeeded = onReconcileNeeded;
        this.currentMembers.add(self);
    }

    /// Production factory bound to the process-wide [`SharedScheduler`].
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config,
                                                          NodeId self,
                                                          Runnable onReconcileNeeded) {
        return new NodeTopologyTracker(config, self, SharedScheduler::schedule, onReconcileNeeded);
    }

    /// Test factory accepting an explicit scheduler and a no-op reconcile trigger —
    /// used by tests that want to inspect the timer map without exercising a downstream
    /// reconciler.
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config,
                                                          NodeId self,
                                                          NttTimerScheduler scheduler) {
        return new NodeTopologyTracker(config, self, scheduler, NOOP_RECONCILE_TRIGGER);
    }

    /// Test factory accepting both an explicit scheduler and a reconcile-trigger
    /// callback. Required for deterministic timer-fire assertions in unit tests.
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config,
                                                          NodeId self,
                                                          NttTimerScheduler scheduler,
                                                          Runnable onReconcileNeeded) {
        return new NodeTopologyTracker(config, self, scheduler, onReconcileNeeded);
    }

    /// SWIM observation entry point. Routes [`DepartedObserved`] to the departure path
    /// (schedule timer + remove from members) and [`HealthyObserved`] to the join path
    /// (add to members + cancel any pending NTT timer). All other observation kinds
    /// are ignored — NTT only cares about the two converged edges.
    @Contract
    public void onSwimObservation(SwimObservation observation) {
        switch (observation) {
            case DepartedObserved departed -> onDeparted(departed.peer());
            case HealthyObserved healthy -> onHealthy(healthy.peer());
            default -> {}
        }
    }

    /// QUIC reconnect entry point. Cancels any pending timer for `peer`. Atomic
    /// remove — a single map removal cancels the still-armed future. The member-set
    /// remains untouched here (SWIM `HealthyObserved` is the canonical join signal).
    @Contract
    public void onQuicReconnect(NodeId peerId) {
        option(timers.remove(peerId)).onPresent(NodeTopologyTracker::cancelFuture);
    }

    /// Count of currently-tracked entries with a not-yet-fired timer.
    public int pendingTimerCount() {
        return timers.size();
    }

    /// Count of currently-tracked cluster members (includes self).
    public int currentMemberCount() {
        return currentMembers.size();
    }

    /// Read-only snapshot of the currently-tracked cluster member set (includes
    /// self). Used by the leader reconciler for the seed-PEERS list when
    /// provisioning replacements and for drain-victim selection.
    public Set<NodeId> currentMembers() {
        return Set.copyOf(currentMembers);
    }

    @Contract
    private void onDeparted(NodeId peerId) {
        currentMembers.remove(peerId);
        scheduleIfAbsent(peerId);
    }

    @Contract
    private void onHealthy(NodeId peerId) {
        currentMembers.add(peerId);
        option(timers.remove(peerId)).onPresent(NodeTopologyTracker::cancelFuture);
    }

    @Contract
    private void scheduleIfAbsent(NodeId peerId) {
        timers.computeIfAbsent(peerId, this::armTimer);
    }

    private ScheduledFuture<?> armTimer(NodeId peerId) {
        return scheduler.schedule(() -> onTimerFire(peerId), config.nttDepartureTimeout());
    }

    @Contract
    private void onTimerFire(NodeId peerId) {
        timers.remove(peerId);
        onReconcileNeeded.run();
    }

    @Contract
    private static void cancelFuture(ScheduledFuture<?> future) {
        future.cancel(false);
    }
}
