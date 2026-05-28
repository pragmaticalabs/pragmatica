// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Leader-pinned reconciler (membership v2 spec §7.4). Combines the leader-activation
/// orchestration with the periodic reconciliation tick and the live event ingresses
/// for NTT [`TopologyUnhealthyEvent`] and [`LocalQuorumWatcher`] [`QuorumLossIntent`].
///
/// **Four trigger paths** converge on a single idempotent `reconcile(trigger)` call:
/// 1. [`#activate()`] on leader gain — drains the NTT map, emits one
///    [`ReconcileTrigger#LEADER_ACTIVATION`] intent per drained event, then a final
///    backstop activation intent (so a leader that activates against an empty NTT map
///    still does one reconciliation pass). Schedules the first periodic tick.
/// 2. [`#onTopologyUnhealthy(TopologyUnhealthyEvent)`] — wired by Stage 6 as a callback
///    from NTT's claim/timer-fire path while leader. Ignored on non-leader nodes.
/// 3. [`#onQuorumLossIntent(QuorumLossIntent)`] — wired by Stage 6 as a
///    [`LocalQuorumWatcher`] listener. At E1 every node emits the intent (Stage 6 may
///    add leader-only gating); only the leader's reconciler would trigger CTM-side
///    action, but the observation-only log path runs everywhere.
/// 4. Periodic tick at `provisioningTimeout × 1.5` — auto-rescheduled per fire while
///    leader, cancelled on `deactivate()`.
///
/// **E1 observation-only.** The registered listener just logs. `peersToProvision` and
/// `peersToDrain` are intentionally empty placeholders; Stage 6 wires actual KSUID
/// generation, drain-selection, and CTM dispatch.
///
/// **In-flight provisioning bookkeeping.** When Stage 6+ enables actual provisioning the
/// reconciler tracks each provisioned peer with its provisioning-start timestamp. Per
/// tick, entries past `provisioningTimeout × 1.5` are evicted (assumed failed) so they
/// no longer mask the "underprovisioned" signal. At E1 the map starts and stays empty
/// (no entries are ever inserted yet), but the eviction code path runs on every tick.
///
/// **Concurrency.** `isLeader` is an [`AtomicBoolean`]; `scheduledTickRef` is an
/// [`AtomicReference`]; `inFlightProvisioning` is a [`ConcurrentHashMap`]. The listener
/// reference is `volatile`. `activate` and `deactivate` are guarded by CAS on `isLeader`
/// so concurrent toggles converge.
///
/// **Tick re-arming.** The injected [`NttTimerScheduler`] is one-shot (matching
/// [`NodeTopologyTracker`] and [`LocalQuorumWatcher`]); each tick re-arms the next while
/// `isLeader` is true.
///
/// **Source citations.**
/// - `provisioningTimeout` default + accessor:
///   `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/AutoHealConfig.java:18,26`
///   (`AutoHealConfig.provisioningTimeout()`, default 60s). Injected as
///   `TimeSpan provisioningTimeout` to keep aether-deployment decoupled from
///   environment-integration. Stage 6 wires from `AutoHealConfig`.
/// - `clusterMembershipCount` source: injected as `IntSupplier` (Stage 6 binds it to
///   a SWIM-converged member-set count; today's running code reads cluster size via
///   `TopologyManager.clusterSize()` consumed by `ClusterTopologyManagerRecord`).
public final class LeaderReconciler {
    private static final Consumer<ReconcileIntent> NOOP_LISTENER = intent -> {};

    private final MembershipConfig membershipConfig;
    private final TimeSpan provisioningTimeout;
    private final TimeSpan tickPeriod;
    private final NodeTopologyTracker ntt;
    private final LocalQuorumWatcher localQuorumWatcher;
    private final IntSupplier clusterMembershipCountSupplier;
    private final IntSupplier configuredCoreCountSupplier;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> scheduledTickRef = new AtomicReference<>();
    private final ConcurrentHashMap<NodeId, Long> inFlightProvisioning = new ConcurrentHashMap<>();
    private volatile Consumer<ReconcileIntent> reconcileListener = NOOP_LISTENER;

    private LeaderReconciler(MembershipConfig membershipConfig,
                             TimeSpan provisioningTimeout,
                             NodeTopologyTracker ntt,
                             LocalQuorumWatcher localQuorumWatcher,
                             IntSupplier clusterMembershipCountSupplier,
                             IntSupplier configuredCoreCountSupplier,
                             TimeSource timeSource,
                             NttTimerScheduler scheduler) {
        this.membershipConfig = membershipConfig;
        this.provisioningTimeout = provisioningTimeout;
        this.tickPeriod = computeTickPeriod(provisioningTimeout);
        this.ntt = ntt;
        this.localQuorumWatcher = localQuorumWatcher;
        this.clusterMembershipCountSupplier = clusterMembershipCountSupplier;
        this.configuredCoreCountSupplier = configuredCoreCountSupplier;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`] and the system clock.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    TimeSpan provisioningTimeout,
                                                    NodeTopologyTracker ntt,
                                                    LocalQuorumWatcher localQuorumWatcher,
                                                    IntSupplier clusterMembershipCountSupplier,
                                                    IntSupplier configuredCoreCountSupplier) {
        return new LeaderReconciler(membershipConfig,
                                    provisioningTimeout,
                                    ntt,
                                    localQuorumWatcher,
                                    clusterMembershipCountSupplier,
                                    configuredCoreCountSupplier,
                                    TimeSource.system(),
                                    SharedScheduler::schedule);
    }

    /// Test factory accepting explicit [`TimeSource`] and [`NttTimerScheduler`] —
    /// required for deterministic tick/fire without wall-clock advancement.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    TimeSpan provisioningTimeout,
                                                    NodeTopologyTracker ntt,
                                                    LocalQuorumWatcher localQuorumWatcher,
                                                    IntSupplier clusterMembershipCountSupplier,
                                                    IntSupplier configuredCoreCountSupplier,
                                                    TimeSource timeSource,
                                                    NttTimerScheduler scheduler) {
        return new LeaderReconciler(membershipConfig,
                                    provisioningTimeout,
                                    ntt,
                                    localQuorumWatcher,
                                    clusterMembershipCountSupplier,
                                    configuredCoreCountSupplier,
                                    timeSource,
                                    scheduler);
    }

    /// Activate the leader-pinned reconciler. Idempotent — if already active, returns
    /// without altering state.
    ///
    /// On the leader-edge transition:
    /// 1. Drain accumulated NTT fired events; emit one [`ReconcileTrigger#LEADER_ACTIVATION`]
    ///    intent per drained event.
    /// 2. Emit one final backstop [`ReconcileTrigger#LEADER_ACTIVATION`] intent (so a
    ///    leader that activates against an empty NTT map still does one reconciliation
    ///    pass — the spec §7.4 "freshly-elected leader must immediately reconcile"
    ///    requirement).
    /// 3. Schedule the first periodic tick at `tickPeriod`.
    @Contract
    public void activate() {
        if (!isLeader.compareAndSet(false, true)) {
            return;
        }
        ntt.drainAllFiredEvents().forEach(event -> reconcile(ReconcileTrigger.LEADER_ACTIVATION));
        reconcile(ReconcileTrigger.LEADER_ACTIVATION);
        scheduleNextTick();
    }

    /// Deactivate the leader-pinned reconciler. Idempotent. Cancels the pending periodic
    /// tick and clears the in-flight provisioning map (the new leader will rebuild it from
    /// observed state). Does NOT drain the NTT map — NTT runs universally on every node.
    @Contract
    public void deactivate() {
        if (!isLeader.compareAndSet(true, false)) {
            return;
        }
        cancelPendingTick();
        inFlightProvisioning.clear();
    }

    /// Live-event ingress for NTT [`TopologyUnhealthyEvent`]. Stage 6 wires this from
    /// the NTT claim/timer-fire path on the leader's process. Non-leader nodes ignore.
    @Contract
    public void onTopologyUnhealthy(TopologyUnhealthyEvent event) {
        if (!isLeader.get()) {
            return;
        }
        reconcile(ReconcileTrigger.NTT_DRAIN);
    }

    /// Live-event ingress for [`LocalQuorumWatcher`] [`QuorumLossIntent`]. Stage 6 wires
    /// this from `LocalQuorumWatcher.setQuorumLossListener(...)` on every node. At E1
    /// observation-only — every node emits the intent so the divergence-logger in Stage 5
    /// can compare across nodes; only the leader would trigger actual §8 drain action in
    /// later stages.
    @Contract
    public void onQuorumLossIntent(QuorumLossIntent intent) {
        reconcile(ReconcileTrigger.QUORUM_LOSS);
    }

    /// Register the consumer that receives every emitted [`ReconcileIntent`]. At E1 the
    /// wiring layer's consumer just logs; Stage 6+ replaces it with the actual CTM
    /// provisioning / drain dispatcher.
    @Contract
    public void setReconcileListener(Consumer<ReconcileIntent> newListener) {
        reconcileListener = newListener;
    }

    /// Observability — whether this instance currently holds the leader lease.
    public boolean isLeader() {
        return isLeader.get();
    }

    /// Observability — number of in-flight provisioning records this leader is tracking.
    public int inFlightProvisioningCount() {
        return inFlightProvisioning.size();
    }

    /// Observability — the periodic tick period, computed once as
    /// `provisioningTimeout × 1.5`.
    public TimeSpan tickPeriod() {
        return tickPeriod;
    }

    /// Observability — read-only snapshot of the in-flight provisioning map. Stage 6 will
    /// surface this through metrics.
    public Map<NodeId, Long> inFlightProvisioningSnapshot() {
        return Map.copyOf(inFlightProvisioning);
    }

    @Contract
    private void reconcile(ReconcileTrigger trigger) {
        var now = timeSource.nanoTime();

        evictExpiredInFlightEntries(now);

        var clusterMembershipCount = clusterMembershipCountSupplier.getAsInt();
        var configuredCoreCount = configuredCoreCountSupplier.getAsInt();
        var intent = ReconcileIntent.reconcileIntent(now,
                                                     trigger,
                                                     clusterMembershipCount,
                                                     configuredCoreCount,
                                                     Set.of(),
                                                     Set.of(),
                                                     inFlightProvisioning.size());

        reconcileListener.accept(intent);
    }

    @Contract
    private void evictExpiredInFlightEntries(long nowNanos) {
        var expiryThresholdNanos = tickPeriod.nanos();

        inFlightProvisioning.entrySet().removeIf(entry -> nowNanos - entry.getValue() > expiryThresholdNanos);
    }

    @Contract
    private void scheduleNextTick() {
        if (!isLeader.get()) {
            return;
        }
        var future = scheduler.schedule(this::onTickFire, tickPeriod);

        scheduledTickRef.set(future);
    }

    @Contract
    private void onTickFire() {
        if (!isLeader.get()) {
            return;
        }
        reconcile(ReconcileTrigger.PERIODIC_TICK);
        scheduleNextTick();
    }

    @Contract
    private void cancelPendingTick() {
        var prev = scheduledTickRef.getAndSet(null);

        if (prev != null) {
            prev.cancel(false);
        }
    }

    private static TimeSpan computeTickPeriod(TimeSpan provisioningTimeout) {
        return timeSpan(provisioningTimeout.nanos() * 3 / 2).nanos();
    }

    /// Observability — the [`MembershipConfig`] this reconciler was constructed with.
    /// Stage 6+ uses this for runtime-config inspection (drain-threshold etc.).
    public MembershipConfig membershipConfig() {
        return membershipConfig;
    }

    /// Observability — the originally-injected `provisioningTimeout` (the canonical
    /// source of truth from which `tickPeriod` is derived as `× 1.5`).
    public TimeSpan provisioningTimeout() {
        return provisioningTimeout;
    }

    /// Observability — the [`NodeTopologyTracker`] collaborator this reconciler drains
    /// on activation.
    public NodeTopologyTracker ntt() {
        return ntt;
    }

    /// Observability — the [`LocalQuorumWatcher`] collaborator. Stage 6 will register
    /// `setQuorumLossListener(this::onQuorumLossIntent)` against this instance during
    /// wiring; at E1 it is held for that future wire-up.
    public LocalQuorumWatcher localQuorumWatcher() {
        return localQuorumWatcher;
    }
}
