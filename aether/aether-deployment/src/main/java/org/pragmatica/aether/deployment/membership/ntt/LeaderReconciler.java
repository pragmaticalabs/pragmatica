// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
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
/// from [`NodeTopologyTracker#currentMembers`] / [`NodeTopologyTracker#currentMemberCount`],
/// the single SWIM-fed membership source (freshest "who is in the cluster" signal). The
/// member set always includes `self`.
///
/// **Five trigger paths.**
/// 1. [`#activate()`] on leader gain — schedules a single one-shot delayed
///    [`ReconcileTrigger#LEADER_ACTIVATION`] reconcile at
///    `nttDepartureTimeout × 1.5`. Reasoning: leader churn is invasive; let SWIM gossip
///    + QUIC connections quiesce before reconciling. No immediate reconcile is emitted.
/// 2. [`#onTopologyUnhealthy()`] — wired from NTT's timer-fire callback while leader.
///    Non-leader nodes ignore. Trigger: [`ReconcileTrigger#NTT_FIRE`].
/// 3. [`#onQuorumLossIntent(QuorumLossIntent)`] — wired from
///    [`QuorumLossDetector`]. Emitted on every node. Trigger:
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
/// `nttDepartureTimeout × 3` are evicted (assumed failed) so they no longer mask the
/// "underprovisioned" signal. This expiry is decoupled from — and intentionally longer than —
/// the `× 1.5` leader-activation quiesce delay: it must exceed a provisioned node's worst-case
/// time-to-stable-membership (container boot + JVM + SWIM up-hysteresis) so the reconciler
/// does not forget an in-flight node and re-provision a phantom replacement (the auto-heal
/// provisioning storm). The map is internal — exposed only via observability accessors.
///
/// **Arm-after-first-full-membership latch (safety-critical — Bug C).** The reconciler
/// must NEVER provision a replacement for a configured core peer that has not yet joined
/// (initial cluster formation / slow join). Provisioning is identity-aware, not
/// count-aware: a deficit (`effective < configuredCoreCount`) is ambiguous on its own.
/// Before the cluster has reached full configured membership at least once, a deficit
/// means "configured peers are still joining" → the reconciler must WAIT (provision
/// nothing). Only after the cluster has been observed at full configured membership
/// (`clusterMembershipCount >= configuredCoreCount`, with `configuredCoreCount >= 1`) does
/// `armedForProvisioning` latch to `true` (set-only, never reset); after arming, a deficit
/// means "a node that WAS present departed" → provision a replacement (auto-heal). Without
/// this latch the count-only reconciler spawns PHANTOM replacement containers for
/// still-joining peers during formation, driving a host-OOM death-spiral (cite
/// membership-unification-spec P5 — identity-aware reconciler). The latch gates
/// provisioning ONLY; draining excess is always safe and is never gated (during formation
/// `effective <= configured` so the drain set is naturally empty).
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
    private final NodeTopologyTracker ntt;
    private final IntSupplier configuredCoreCountSupplier;
    private final ClusterTopologyManager ctm;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicBoolean reconcileInFlight = new AtomicBoolean(false);
    private final AtomicBoolean rescheduleRequested = new AtomicBoolean(false);
    private final AtomicBoolean armedForProvisioning = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> activationFutureRef = new AtomicReference<>();
    private final AtomicReference<Option<ReconcileTrigger>> pendingTriggerRef = new AtomicReference<>(none());
    private final ConcurrentHashMap<NodeId, Long> inFlightProvisioning = new ConcurrentHashMap<>();
    private volatile Consumer<ReconcileIntent> reconcileListener = NOOP_LISTENER;

    private LeaderReconciler(MembershipConfig membershipConfig,
                             NodeTopologyTracker ntt,
                             IntSupplier configuredCoreCountSupplier,
                             ClusterTopologyManager ctm,
                             TimeSource timeSource,
                             NttTimerScheduler scheduler) {
        this.membershipConfig = membershipConfig;
        this.leaderActivationDelay = computeQuiesceDelay(membershipConfig.nttDepartureTimeout());
        this.inFlightExpiry = computeInFlightExpiry(membershipConfig.nttDepartureTimeout());
        this.ntt = ntt;
        this.configuredCoreCountSupplier = configuredCoreCountSupplier;
        this.ctm = ctm;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`] and the system clock.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    NodeTopologyTracker ntt,
                                                    IntSupplier configuredCoreCountSupplier,
                                                    ClusterTopologyManager ctm) {
        return new LeaderReconciler(membershipConfig,
                                    ntt,
                                    configuredCoreCountSupplier,
                                    ctm,
                                    TimeSource.system(),
                                    SharedScheduler::schedule);
    }

    /// Test factory accepting explicit [`TimeSource`] and [`NttTimerScheduler`] —
    /// required for deterministic activation/debounce assertions.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    NodeTopologyTracker ntt,
                                                    IntSupplier configuredCoreCountSupplier,
                                                    ClusterTopologyManager ctm,
                                                    TimeSource timeSource,
                                                    NttTimerScheduler scheduler) {
        return new LeaderReconciler(membershipConfig,
                                    ntt,
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

    /// Live-event ingress for [`QuorumLossDetector`] [`QuorumLossIntent`]. At E1
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

    /// Observability — whether the provisioning latch has armed. Starts `false`; latches
    /// `true` the first reconcile pass that observes the cluster at full configured
    /// membership (`clusterMembershipCount >= configuredCoreCount`, `configuredCoreCount >=
    /// 1`); never resets. While unarmed, provisioning is suppressed (Bug C cold-start
    /// guard — membership-unification-spec P5).
    public boolean isArmedForProvisioning() {
        return armedForProvisioning.get();
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

    /// Single reconcile pass. Derives `clusterMembershipCount` and the current member set
    /// from [`NodeTopologyTracker#currentMembers`], arms the provisioning latch on first full
    /// configured membership, then gates provisioning behind `quorumSafe &&
    /// armedForProvisioning` (identity-aware rule — membership-unification-spec P5).
    /// Provisioning is suppressed until the latch arms so a deficit during initial
    /// formation / slow join never spawns phantom replacements for still-joining configured
    /// peers (Bug C). Draining is independently protected by a HARD FLOOR in
    /// [`#computePeersToDrain`]: it never drains confirmed members below `configuredCoreCount`
    /// in a single pass, so an `effective` count inflated by in-flight placeholders (a
    /// requested-but-not-joined replacement) can never trigger a quorum-dissolving drain of a
    /// healthy core node.
    @Contract
    private void runReconcileBody(ReconcileTrigger trigger) {
        var now = timeSource.nanoTime();

        evictExpiredInFlightEntries(now);

        var currentMembers = ntt.currentMembers();
        var clusterMembershipCount = currentMembers.size();
        var configuredCoreCount = configuredCoreCountSupplier.getAsInt();
        // Effective capacity is the SIZE OF THE UNION of confirmed members and in-flight
        // provisioning placeholders — NOT their sum (safety-critical). A just-provisioned
        // node can appear in BOTH buckets simultaneously: present in the SWIM-gossiped
        // membership view AND still tracked in `inFlightProvisioning` until it expires or is
        // explicitly removed. Summing the two sizes double-counts that node, inflating
        // `effective` above the configured core count and tricking the drain path into
        // believing there is a surplus — which then drains a healthy core node, drops the
        // cluster below quorum, and dissolves it. Deduplicating by NodeId counts each node
        // exactly once.
        var effective = effectiveCapacity(currentMembers);

        // Arm-after-first-full-membership latch (Bug C; membership-unification-spec P5 —
        // identity-aware reconciler). Latch true the first time the cluster is observed at
        // full configured membership; never resets. configuredCoreCount must be >= 1 to be
        // armable (an unset/0 configured count is never a real "full" target).
        if (configuredCoreCount >= 1 && clusterMembershipCount >= configuredCoreCount) {
            armedForProvisioning.set(true);
        }

        // Quorum-safety guard (spec §7.2, §I5; sub-quorum-must-dissolve). A sub-quorum
        // leader cannot distinguish "the majority died" from "I am the isolated minority",
        // so it MUST NOT provision replacements — a partitioned minority that provisioned
        // would spawn a phantom split-brain cluster. Below confirmed quorum the leader does
        // nothing (no provision AND no drain); QuorumLossDetector's §8 self-drain dissolves
        // the minority. The observability intent is still emitted so operators see the
        // suppressed pass.
        // TODO(2c-α.3): tighten the quorum signal to the QUIC-confirmed
        //   QuorumLossDetector.isBelowThreshold() once ClusterConfigValue.coreCount is wired
        //   into the watcher (currently dormant — configuredCoreCount unset). The SWIM
        //   membership count used here has a brief stale window post-partition (spec §11
        //   residual edge, self-corrected by self-drain).
        var quorumSafe = clusterMembershipCount >= quorumThreshold(configuredCoreCount);
        var peersToProvision = (quorumSafe && armedForProvisioning.get()) ? computePeersToProvision(configuredCoreCount, effective) : Set.<NodeId>of();
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

    /// Effective cluster capacity = the size of the UNION of confirmed members and in-flight
    /// provisioning placeholders, deduplicated by [`NodeId`]. A node that has joined SWIM but
    /// whose in-flight placeholder has not yet expired is present in both sets; counting it
    /// once (via the union) prevents the inflated-surplus → spurious-drain → quorum-loss
    /// dissolution. Never a sum.
    private int effectiveCapacity(Set<NodeId> currentMembers) {
        var union = new LinkedHashSet<>(currentMembers);

        union.addAll(inFlightProvisioning.keySet());

        return union.size();
    }

    /// Simple-majority quorum threshold over the configured core size — same formula as
    /// [`QuorumLossDetector`] and `ClusterTopologyManagerRecord.quorumThreshold`
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

    /// Pick drain victims from the observed member set using a stable iteration-order
    /// heuristic, gated by a HARD FLOOR (safety-critical, symmetric with the provisioning
    /// arm-gate). The reconciler must NEVER drain a confirmed core member below
    /// `configuredCoreCount` in a single pass — doing so drops the cluster below quorum and
    /// dissolves it. The nominal surplus is `effective - configured`, but drain victims can
    /// only ever come from CONFIRMED members (`currentMembers`); the maximum number safely
    /// drainable is `currentMembers.size() - configuredCoreCount` (clamped at zero). When
    /// `effective` is inflated above the confirmed-member count by in-flight provisioning
    /// placeholders (a replacement that was requested but has not joined), the floor binds
    /// and the drain set shrinks — down to nothing if confirmed members are already at or
    /// below the configured floor. Internal — observers see only the count via
    /// [`ReconcileIntent#drainCount`].
    private Set<NodeId> computePeersToDrain(Set<NodeId> currentMembers, int configuredCoreCount, int effective) {
        if (effective <= configuredCoreCount) {
            return Set.of();
        }
        var nominalExcess = effective - configuredCoreCount;
        var floorHeadroom = Math.max(0, currentMembers.size() - configuredCoreCount);
        var drainCount = Math.min(nominalExcess, floorHeadroom);

        if (drainCount == 0) {
            return Set.of();
        }
        var ordered = new LinkedHashSet<NodeId>();

        currentMembers.stream().sorted(Comparator.comparing(NodeId::id).reversed()).limit(drainCount).forEach(ordered::add);

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

    /// In-flight provisioning entries expire at `nttDepartureTimeout × 3`. This window is
    /// decoupled from (and deliberately longer than) the `× 1.5` leader-activation quiesce
    /// delay: an in-flight entry must outlive a freshly provisioned node's WORST-CASE
    /// time-to-stable-membership (container boot + JVM start + SWIM up-hysteresis), which can
    /// run to tens of seconds. If the entry expired first, the reconciler would forget the
    /// node it is still waiting on, re-observe the same deficit, and re-provision a phantom
    /// replacement — the auto-heal provisioning storm this expiry exists to prevent.
    private static TimeSpan computeInFlightExpiry(TimeSpan nttDepartureTimeout) {
        return timeSpan(nttDepartureTimeout.nanos() * 3).nanos();
    }

    /// Observability — the [`MembershipConfig`] this reconciler was constructed with.
    public MembershipConfig membershipConfig() {
        return membershipConfig;
    }

    /// Observability — the [`NodeTopologyTracker`] collaborator the reconciler reads the
    /// current member set from (unified SWIM-fed membership source; includes self).
    public NodeTopologyTracker ntt() {
        return ntt;
    }
}
