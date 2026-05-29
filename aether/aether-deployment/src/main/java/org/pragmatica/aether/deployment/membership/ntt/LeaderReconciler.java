// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Leader-pinned reconciler (membership v2 spec §7.4 — E2 Phase 1.6: state-derived
/// reconciliation sourcing membership from NTT). All trigger paths converge on a
/// single CAS-debounced `triggerReconcile(trigger)` entry point; the periodic tick
/// has been removed (the previous tick existed only because surplus had no event
/// signal — now SWIM `HealthyObserved` provides it symmetrically with NTT for
/// shortage). `clusterMembershipCount` and the current member set are both sourced
/// from the unified [`MembershipView#coreMemberIds`], which derives them from the
/// single SWIM-fed membership source (freshest "who is in the cluster" signal).
///
/// **Five trigger paths.**
/// 1. [`#activate()`] on leader gain — schedules a single one-shot delayed
///    [`ReconcileTrigger#LEADER_ACTIVATION`] reconcile at
///    `nttDepartureTimeout × 1.5`. Reasoning: leader churn is invasive; let SWIM gossip
///    + QUIC connections quiesce before reconciling. No immediate reconcile is emitted.
/// 2. [`#onTopologyUnhealthy()`] — wired from NTT's timer-fire callback while leader.
///    Non-leader nodes ignore. Trigger: [`ReconcileTrigger#NTT_FIRE`].
/// 3. [`#onQuorumLossIntent(QuorumLossIntent)`] — wired from
///    [`LocalQuorumWatcher`]. Emitted on every node. Trigger:
///    [`ReconcileTrigger#QUORUM_LOSS`].
/// 4. [`#onSwimMemberHealthy(NodeId)`] — wired from SWIM `HealthyObserved`. Catches
///    the "surplus appeared" case (a peer became reachable; the leader may need to
///    drain excess). Trigger: [`ReconcileTrigger#MEMBER_APPEARED`].
/// 5. [`#onConfigChange()`] — placeholder entry point for KV-subscribed config changes
///    (e.g., `coreCount`). Phase 2 hooks the actual subscription. Trigger:
///    [`ReconcileTrigger#CONFIG_CHANGE`].
///
/// **CAS-debounce.** A burst of trigger events collapses to at most two reconcile
/// passes via the standard "in-flight + reschedule-requested" pair of [`AtomicBoolean`]s.
/// First event sets `reconcileInFlight=true` and schedules the reconcile; subsequent
/// events while reconcile is in flight set `rescheduleRequested=true`; when the in-flight
/// reconcile completes, the flag is cleared and if `rescheduleRequested` was set, one
/// follow-up reconcile is scheduled.
///
/// **In-flight provisioning bookkeeping.** Per reconcile pass, entries past
/// `nttDepartureTimeout × 1.5` are evicted (assumed failed) so they no longer mask the
/// "underprovisioned" signal. The map is internal — exposed only via observability
/// accessors.
///
/// **Concurrency.** `isLeader`, `reconcileInFlight`, `rescheduleRequested` are
/// [`AtomicBoolean`]s; `activationFutureRef` is an [`AtomicReference`];
/// `inFlightProvisioning` is a [`ConcurrentHashMap`]. The listener reference is
/// `volatile`. `activate` and `deactivate` are guarded by CAS on `isLeader`.
public final class LeaderReconciler {
    private static final Consumer<ReconcileIntent> NOOP_LISTENER = intent -> {};
    private static final TimeSpan DEBOUNCE_DELAY = timeSpan(100L).millis();

    private final MembershipConfig membershipConfig;
    private final TimeSpan leaderActivationDelay;
    private final TimeSpan inFlightExpiry;
    private final MembershipView membershipView;
    private final LocalQuorumWatcher localQuorumWatcher;
    private final IntSupplier configuredCoreCountSupplier;
    private final ClusterTopologyManager ctm;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicBoolean reconcileInFlight = new AtomicBoolean(false);
    private final AtomicBoolean rescheduleRequested = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> activationFutureRef = new AtomicReference<>();
    private final AtomicReference<Option<ReconcileTrigger>> pendingTriggerRef = new AtomicReference<>(none());
    private final ConcurrentHashMap<NodeId, Long> inFlightProvisioning = new ConcurrentHashMap<>();
    private volatile Consumer<ReconcileIntent> reconcileListener = NOOP_LISTENER;

    private LeaderReconciler(MembershipConfig membershipConfig,
                             MembershipView membershipView,
                             LocalQuorumWatcher localQuorumWatcher,
                             IntSupplier configuredCoreCountSupplier,
                             ClusterTopologyManager ctm,
                             TimeSource timeSource,
                             NttTimerScheduler scheduler) {
        this.membershipConfig = membershipConfig;
        this.leaderActivationDelay = computeQuiesceDelay(membershipConfig.nttDepartureTimeout());
        this.inFlightExpiry = leaderActivationDelay;
        this.membershipView = membershipView;
        this.localQuorumWatcher = localQuorumWatcher;
        this.configuredCoreCountSupplier = configuredCoreCountSupplier;
        this.ctm = ctm;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`] and the system clock.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    MembershipView membershipView,
                                                    LocalQuorumWatcher localQuorumWatcher,
                                                    IntSupplier configuredCoreCountSupplier,
                                                    ClusterTopologyManager ctm) {
        return new LeaderReconciler(membershipConfig,
                                    membershipView,
                                    localQuorumWatcher,
                                    configuredCoreCountSupplier,
                                    ctm,
                                    TimeSource.system(),
                                    SharedScheduler::schedule);
    }

    /// Test factory accepting explicit [`TimeSource`] and [`NttTimerScheduler`] —
    /// required for deterministic activation/debounce assertions.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    MembershipView membershipView,
                                                    LocalQuorumWatcher localQuorumWatcher,
                                                    IntSupplier configuredCoreCountSupplier,
                                                    ClusterTopologyManager ctm,
                                                    TimeSource timeSource,
                                                    NttTimerScheduler scheduler) {
        return new LeaderReconciler(membershipConfig,
                                    membershipView,
                                    localQuorumWatcher,
                                    configuredCoreCountSupplier,
                                    ctm,
                                    timeSource,
                                    scheduler);
    }

    /// Activate the leader-pinned reconciler. Idempotent — if already active, returns
    /// without altering state.
    ///
    /// On the leader-edge transition: schedule a single one-shot delayed reconcile at
    /// `nttDepartureTimeout × 1.5`. No immediate reconcile is emitted — the delay lets
    /// SWIM gossip and QUIC connections quiesce before the first reconcile pass runs.
    @Contract
    public void activate() {
        if (!isLeader.compareAndSet(false, true)) {
            return;
        }
        var future = scheduler.schedule(this::onActivationDelayFire, leaderActivationDelay);

        activationFutureRef.set(future);
    }

    /// Deactivate the leader-pinned reconciler. Idempotent. Cancels the pending one-shot
    /// activation reconcile and clears the in-flight provisioning map (the new leader
    /// will rebuild it from observed state).
    @Contract
    public void deactivate() {
        if (!isLeader.compareAndSet(true, false)) {
            return;
        }
        cancelPendingActivation();
        inFlightProvisioning.clear();
    }

    /// Live-event ingress for NTT timer-expiry. Stage 6 wires this from the NTT
    /// reconcile-trigger callback. Non-leader nodes ignore.
    @Contract
    public void onTopologyUnhealthy() {
        if (!isLeader.get()) {
            return;
        }
        triggerReconcile(ReconcileTrigger.NTT_FIRE);
    }

    /// Live-event ingress for [`LocalQuorumWatcher`] [`QuorumLossIntent`]. At E1
    /// observation-only — every node emits the intent so the divergence-logger can
    /// compare across nodes; only the leader would trigger actual §8 drain action in
    /// later stages.
    @Contract
    public void onQuorumLossIntent(QuorumLossIntent intent) {
        triggerReconcile(ReconcileTrigger.QUORUM_LOSS);
    }

    /// Live-event ingress for SWIM `HealthyObserved`. Catches the "surplus appeared"
    /// case symmetrically with [`#onTopologyUnhealthy()`] catching shortage. Wired from
    /// the SWIM observation listener filter. Non-leader nodes ignore.
    @Contract
    public void onSwimMemberHealthy(NodeId peerId) {
        if (!isLeader.get()) {
            return;
        }
        triggerReconcile(ReconcileTrigger.MEMBER_APPEARED);
    }

    /// Live-event ingress for KV-subscribed config changes (e.g., `coreCount`). Phase
    /// 1.5 wires the entry point; Phase 2 hooks the actual subscription. Non-leader
    /// nodes ignore.
    @Contract
    public void onConfigChange() {
        if (!isLeader.get()) {
            return;
        }
        triggerReconcile(ReconcileTrigger.CONFIG_CHANGE);
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

    /// Observability — the one-shot leader-activation delay, computed once as
    /// `nttDepartureTimeout × 1.5`.
    public TimeSpan leaderActivationDelay() {
        return leaderActivationDelay;
    }

    /// Observability — read-only snapshot of the in-flight provisioning map. Stage 6
    /// will surface this through metrics.
    public Map<NodeId, Long> inFlightProvisioningSnapshot() {
        return Map.copyOf(inFlightProvisioning);
    }

    /// CAS-debounce entry point. First trigger schedules a short-debounced reconcile;
    /// concurrent triggers during reconcile-in-flight flag a single follow-up pass.
    @Contract
    private void triggerReconcile(ReconcileTrigger trigger) {
        if (!reconcileInFlight.compareAndSet(false, true)) {
            rescheduleRequested.set(true);
            return;
        }
        pendingTriggerRef.set(some(trigger));
        scheduler.schedule(this::runDebouncedReconcile, DEBOUNCE_DELAY);
    }

    @Contract
    private void runDebouncedReconcile() {
        var trigger = pendingTriggerRef.getAndSet(none()).or(ReconcileTrigger.NTT_FIRE);

        runReconcileBody(trigger);
        reconcileInFlight.set(false);
        if (rescheduleRequested.compareAndSet(true, false)) {
            triggerReconcile(ReconcileTrigger.NTT_FIRE);
        }
    }

    @Contract
    private void onActivationDelayFire() {
        if (!isLeader.get()) {
            return;
        }
        activationFutureRef.set(null);
        runReconcileBody(ReconcileTrigger.LEADER_ACTIVATION);
    }

    @Contract
    private void runReconcileBody(ReconcileTrigger trigger) {
        var now = timeSource.nanoTime();

        evictExpiredInFlightEntries(now);

        var currentMembers = membershipView.coreMemberIds();
        var clusterMembershipCount = currentMembers.size();
        var configuredCoreCount = configuredCoreCountSupplier.getAsInt();
        var effective = clusterMembershipCount + inFlightProvisioning.size();

        // Quorum-safety guard (spec §7.2, §I5; sub-quorum-must-dissolve). A sub-quorum
        // leader cannot distinguish "the majority died" from "I am the isolated minority",
        // so it MUST NOT provision replacements — a partitioned minority that provisioned
        // would spawn a phantom split-brain cluster. Below confirmed quorum the leader does
        // nothing (no provision AND no drain); LocalQuorumWatcher's §8 self-drain dissolves
        // the minority. The observability intent is still emitted so operators see the
        // suppressed pass.
        // TODO(2c-α.3): tighten the quorum signal to the QUIC-confirmed
        //   LocalQuorumWatcher.isBelowThreshold() once ClusterConfigValue.coreCount is wired
        //   into the watcher (currently dormant — configuredCoreCount unset). The SWIM
        //   membership count used here has a brief stale window post-partition (spec §11
        //   residual edge, self-corrected by self-drain).
        var quorumSafe = clusterMembershipCount >= quorumThreshold(configuredCoreCount);
        var peersToProvision = quorumSafe ? computePeersToProvision(configuredCoreCount, effective) : Set.<NodeId>of();
        var peersToDrain = quorumSafe ? computePeersToDrain(currentMembers, configuredCoreCount, effective) : Set.<NodeId>of();

        dispatchProvisionActions(now, peersToProvision, currentMembers);
        dispatchDrainActions(peersToDrain);

        var intent = ReconcileIntent.reconcileIntent(now,
                                                     trigger,
                                                     clusterMembershipCount,
                                                     configuredCoreCount,
                                                     peersToProvision.size(),
                                                     peersToDrain.size(),
                                                     inFlightProvisioning.size());

        reconcileListener.accept(intent);
    }

    /// Simple-majority quorum threshold over the configured core size — same formula as
    /// [`LocalQuorumWatcher`] and `ClusterTopologyManagerRecord.quorumThreshold`
    /// (`configured / 2 + 1`). A configured count `< 1` is treated as `1` (a single-node
    /// cluster is its own quorum) so the guard never blocks the trivial bootstrap case.
    private static int quorumThreshold(int configuredCoreCount) {
        return configuredCoreCount < 1 ? 1 : configuredCoreCount / 2 + 1;
    }

    /// Compute the set of synthetic placeholder NodeIds representing each missing slot
    /// the reconciler should request a provision for. The reconciler owns peer selection
    /// — observers only see the count via [`ReconcileIntent#provisionCount`].
    private Set<NodeId> computePeersToProvision(int configuredCoreCount, int effective) {
        if (effective >= configuredCoreCount) {
            return Set.of();
        }
        var gap = configuredCoreCount - effective;
        var placeholders = new LinkedHashSet<NodeId>();

        for (var i = 0; i < gap; i++) {
            placeholders.add(NodeId.randomNodeId());
        }
        return Set.copyOf(placeholders);
    }

    /// Pick `(effective - configured)` drain victims from the observed member set using a
    /// stable iteration-order heuristic. Internal — observers see only the count via
    /// [`ReconcileIntent#drainCount`].
    private Set<NodeId> computePeersToDrain(Set<NodeId> currentMembers, int configuredCoreCount, int effective) {
        if (effective <= configuredCoreCount) {
            return Set.of();
        }
        var excess = effective - configuredCoreCount;
        var ordered = new LinkedHashSet<NodeId>();

        currentMembers.stream().sorted(Comparator.comparing(NodeId::id).reversed()).limit(excess).forEach(ordered::add);

        return Set.copyOf(ordered);
    }

    @Contract
    private void dispatchProvisionActions(long nowNanos, Set<NodeId> peersToProvision, Set<NodeId> currentMembers) {
        peersToProvision.forEach(placeholder -> dispatchSingleProvision(nowNanos, placeholder, currentMembers));
    }

    @Contract
    private void dispatchSingleProvision(long nowNanos, NodeId placeholder, Set<NodeId> currentMembers) {
        inFlightProvisioning.put(placeholder, nowNanos);
        ctm.provisionReplacement(Option.none(), currentMembers).onFailure(cause -> inFlightProvisioning.remove(placeholder));
    }

    @Contract
    private void dispatchDrainActions(Set<NodeId> peersToDrain) {
        peersToDrain.forEach(this::dispatchSingleDrain);
    }

    @Contract
    private void dispatchSingleDrain(NodeId peerId) {
        ctm.drainNode(peerId, DrainReason.OVERPROVISION_PARTITION_HEAL);
    }

    @Contract
    private void evictExpiredInFlightEntries(long nowNanos) {
        var expiryThresholdNanos = inFlightExpiry.nanos();

        inFlightProvisioning.entrySet().removeIf(entry -> nowNanos - entry.getValue() > expiryThresholdNanos);
    }

    @Contract
    private void cancelPendingActivation() {
        var prev = activationFutureRef.getAndSet(null);

        if (prev != null) {
            prev.cancel(false);
        }
    }

    private static TimeSpan computeQuiesceDelay(TimeSpan nttDepartureTimeout) {
        return timeSpan(nttDepartureTimeout.nanos() * 3 / 2).nanos();
    }

    /// Observability — the [`MembershipConfig`] this reconciler was constructed with.
    public MembershipConfig membershipConfig() {
        return membershipConfig;
    }

    /// Observability — the [`MembershipView`] collaborator the reconciler reads the
    /// current core-member set from (unified SWIM-fed membership source).
    public MembershipView membershipView() {
        return membershipView;
    }

    /// Observability — the [`LocalQuorumWatcher`] collaborator. Wired upstream via
    /// `setQuorumLossListener(this::onQuorumLossIntent)`.
    public LocalQuorumWatcher localQuorumWatcher() {
        return localQuorumWatcher;
    }
}
