// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.function.Supplier;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Leader-pinned reconciler (membership v2 spec §7.4 — E2 Phase 1.6: state-derived
/// reconciliation sourcing membership from presence sampler). All trigger paths converge on a
/// single CAS-debounced `triggerReconcile(trigger)` entry point; the periodic tick
/// has been removed (the previous tick existed only because surplus had no event
/// signal — now SWIM `HealthyObserved` provides it symmetrically with presence sampler for
/// shortage). `clusterMembershipCount` and the current member set are both sourced
/// from [`PresenceSampler#currentMembers`] / [`PresenceSampler#currentMemberCount`],
/// the single SWIM-fed membership source (freshest "who is in the cluster" signal). The
/// member set always includes `self`.
///
/// **Five trigger paths.**
/// 1. [`#activate()`] on leader gain — schedules a single one-shot delayed
///    [`ReconcileTrigger#LEADER_ACTIVATION`] reconcile at
///    `nttDepartureTimeout × 1.5`. Reasoning: leader churn is invasive; let SWIM gossip
///    + QUIC connections quiesce before reconciling. No immediate reconcile is emitted.
/// 2. [`#onTopologyUnhealthy()`] — wired from presence sampler's timer-fire callback while leader.
///    Non-leader nodes ignore. Trigger: [`ReconcileTrigger#NTT_FIRE`].
/// 3. [`#onQuorumLossIntent(QuorumLossIntent)`] — wired from
///    [`QuorumLossDetector`]. Emitted on every node. Trigger:
///    [`ReconcileTrigger#QUORUM_LOSS`].
/// 4. [`#onSwimMemberHealthy(NodeId, long)`] — wired from SWIM `HealthyObserved`. Catches
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
/// **Reached-full-membership latch (safety-critical — Bug C).** The reconciler must NEVER
/// provision a replacement for a configured core peer that has not yet joined (initial
/// cluster formation / slow join). Provisioning is identity-aware, not count-aware: a
/// deficit (`effective < configuredCoreCount`) is ambiguous on its own. The correct signal
/// for "cold-start is over" is a FACT, not a timer: the cluster has been OBSERVED at full
/// configured size. Before that, a deficit means "configured peers are still climbing" →
/// the reconciler must WAIT (provision nothing). Once `clusterMembershipCount >=
/// configuredCoreCount` is observed (with `configuredCoreCount >= 1`), `reachedFullMembership`
/// latches to `true` (one-way, never reset); thereafter a deficit means "a node that WAS
/// present departed" → provision a replacement (auto-heal), gated ONLY by the deficit-debounce
/// + quorum-safety + the `armedForProvisioning` arm-latch. The previous timer-anchored
/// cold-start grace (`armedAtNanos + nttDepartureTimeout × 1.5`) is GONE as the provisioning
/// suppressor: it was the auto-heal bug — its window was anchored at the FIRST reconcile pass
/// (a node's death, not formation), so it suppressed forever once the deficit was observed and
/// no further pass re-evaluated. Without the latch the count-only reconciler spawns PHANTOM
/// replacement containers for still-joining peers during formation, driving a host-OOM
/// death-spiral (cite membership-unification-spec P5 — identity-aware reconciler). The latch
/// gates provisioning ONLY; draining excess is always safe and is never gated (during formation
/// `effective <= configured` so the drain set is naturally empty).
///
/// **Re-election variant.** A freshly-promoted leader inheriting an already-formed, now-deficient
/// cluster (e.g. 4/5) starts with `reachedFullMembership=false` and would never observe full
/// membership again (the dead peers never return under restart:"no") — wedging in cold-start
/// suppression. To break this, when leadership is gained via RE-ELECTION (the leader term > 1,
/// meaning a prior leader existed and the cluster formed under it), `reachedFullMembership` is set
/// `true` on activation: this is not initial formation, so a sub-full count is a departure, not a
/// slow join. The term is read from the injected `leaderTermSupplier`.
///
/// **Suppressed-deficit re-evaluation tick (Fix 2).** A pass that computes a deficit but suppresses
/// provisioning purely on the deficit-debounce TIME gate (`WITHIN_DEBOUNCE`) schedules a single
/// follow-up reconcile after the remaining debounce window (+ a small margin), so the deficit is
/// acted on when the gate clears instead of waiting for the next presence sampler membership-change event (which
/// never arrives when membership is stable in deficit). At most one such follow-up is pending at a
/// time (deduped via [`#debounceReEvalFutureRef`]); it does not busy-loop.
///
/// **Concurrency.** `isLeader`, `reconcileInFlight`, `rescheduleRequested` are
/// [`AtomicBoolean`]s; `activationFutureRef` is an [`AtomicReference`];
/// `inFlightProvisioning` is a [`ConcurrentHashMap`]. The listener reference is
/// `volatile`. `activate` and `deactivate` are guarded by CAS on `isLeader`.
public final class LeaderReconciler {
    private static final Logger log = LoggerFactory.getLogger(LeaderReconciler.class);
    private static final Consumer<ReconcileIntent> NOOP_LISTENER = intent -> {};
    private static final TimeSpan DEBOUNCE_DELAY = timeSpan(100L).millis();
    /// Sentinel for "not yet recorded" on the two single-shot/edge-triggered nanosecond
    /// timestamps below. `Long.MIN_VALUE` is unreachable by any real `timeSource.nanoTime()`
    /// observation, so a sentinel comparison can never alias a legitimate time.
    private static final long UNSET_NANOS = Long.MIN_VALUE;

    private final MembershipConfig membershipConfig;
    private final TimeSpan leaderActivationDelay;
    private final TimeSpan provisioningGraceWindow;
    private final TimeSpan deficitDebounceWindow;
    private final TimeSpan inFlightExpiry;
    private final PresenceSampler presenceSampler;
    /// Authoritative membership-count source (membership v2 cutover, #68/#94). The reconciler
    /// COUNTS this FSM's [`MembershipFsm#countedMembers`] (MEMBER + SUSPECT) as its base membership
    /// set — NOT presence sampler's presence set. A SUSPECT member still counts here, so a single-plane false
    /// positive no longer opens a phantom deficit that over-provisions; a genuinely-gone node drops
    /// from the count via co-confirmed death or the routed down-hysteresis crossing. presence sampler is retained
    /// for its monotonic [`PresenceSampler#peakMembershipCount`] cold-start latch and trigger
    /// wiring.
    private final MembershipFsm membershipFsm;
    private final IntSupplier configuredCoreCountSupplier;
    /// Leader-term supplier (monotonic, incremented once per election). A value `> 1` on
    /// activation means a prior leader existed → this leadership was gained via RE-ELECTION
    /// (the cluster formed under a prior leader), so [`#reachedFullMembership`] is pre-latched
    /// on activation to avoid wedging in cold-start suppression on an already-formed cluster.
    private final Supplier<Long> leaderTermSupplier;
    private final ClusterTopologyManager ctm;
    /// Cluster-name supplier (config-derived; empty string when not yet readable pre-formation).
    /// Used only to scope auto-heal replacement node ids to the cluster (`aether-<cluster>-node-<ulid>`)
    /// so a replacement is shape-identical to its compose-seeded siblings (NodeId == container name).
    private final Supplier<String> clusterNameSupplier;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicBoolean reconcileInFlight = new AtomicBoolean(false);
    private final AtomicBoolean rescheduleRequested = new AtomicBoolean(false);
    private final AtomicBoolean armedForProvisioning = new AtomicBoolean(false);
    /// One-way latch: `true` once the cluster has been OBSERVED at full configured membership
    /// (`clusterMembershipCount >= configuredCoreCount`, `configuredCoreCount >= 1`), or pre-set on
    /// a RE-ELECTION activation (leader term > 1). Provisioning is suppressed while this is `false`
    /// (still cold-starting / climbing — a deficit may be a slow-joining configured node; never
    /// spawn a phantom). Once `true` the cold-start grace NO LONGER applies — a deficit is a
    /// departure and provisioning is gated only by the deficit-debounce + quorum-safety +
    /// [`#armedForProvisioning`]. Replaces the buggy timer-anchored grace as the cold-start
    /// suppressor (Bug C; membership-unification-spec P5).
    private final AtomicBoolean reachedFullMembership = new AtomicBoolean(false);
    /// Nanosecond timestamp of the leading edge of the current deficit run (deficit debounce
    /// anchor). Set when a deficit (`effective < configuredCoreCount`) is first observed after a
    /// non-deficit pass; reset to `UNSET_NANOS` whenever `effective >= configuredCoreCount`. A
    /// transient reconnect dip resets it; a genuine departure lets it age past the debounce
    /// window so auto-heal fires.
    private volatile long deficitSinceNanos = UNSET_NANOS;
    private final AtomicReference<ScheduledFuture<?>> activationFutureRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> inFlightSweepFutureRef = new AtomicReference<>();
    /// At most one pending suppressed-deficit re-evaluation follow-up (Fix 2). A `WITHIN_DEBOUNCE`
    /// pass schedules one reconcile after the remaining debounce window; concurrent attempts to
    /// schedule a second are deduped (a non-null ref short-circuits the schedule), and the ref is
    /// nulled when the follow-up fires so the next debounce window can arm a fresh one.
    private final AtomicReference<ScheduledFuture<?>> debounceReEvalFutureRef = new AtomicReference<>();
    private final AtomicReference<Option<ReconcileTrigger>> pendingTriggerRef = new AtomicReference<>(none());
    private final ConcurrentHashMap<NodeId, Long> inFlightProvisioning = new ConcurrentHashMap<>();
    /// Provisioning-stickiness fix — supplier of the STICKILY-retained set of in-flight provisioning
    /// ids the prior leader broadcast (via the metrics ping's `dispatchedNodes`, retained term-fenced
    /// by `ClusterSyncCollector`). On leadership GAIN ([`#activate()`]) this set seeds
    /// `inFlightProvisioning` for ids not already members, so a new leader inherits the prior leader's
    /// dispatches instead of re-dispatching them (over-provisioning). Default `() -> Set.of()` (no
    /// seed) so existing construction / tests keep working unchanged; `AetherNode` wires it to
    /// `ClusterSyncCollector::retainedDispatchedNodes`.
    private final AtomicReference<Supplier<Set<NodeId>>> retainedDispatchedSupplier = new AtomicReference<>(Set::of);
    private volatile Consumer<ReconcileIntent> reconcileListener = NOOP_LISTENER;

    private LeaderReconciler(MembershipConfig membershipConfig,
                             PresenceSampler presenceSampler,
                             MembershipFsm membershipFsm,
                             IntSupplier configuredCoreCountSupplier,
                             Supplier<Long> leaderTermSupplier,
                             ClusterTopologyManager ctm,
                             Supplier<String> clusterNameSupplier,
                             TimeSource timeSource,
                             NttTimerScheduler scheduler) {
        this.membershipConfig = membershipConfig;
        this.leaderActivationDelay = computeQuiesceDelay(membershipConfig.nttDepartureTimeout());
        // Cold-start grace = nttDepartureTimeout × 1.5 (== leaderActivationDelay): the class doc
        // (arm-after-first-quorum latch) names this exact window as the bound by which formation
        // has completed. Reuse the already-computed quiesce delay so the two stay identical.
        this.provisioningGraceWindow = this.leaderActivationDelay;
        // Deficit debounce = nttDepartureTimeout (1×). A genuine departure's deficit persists well
        // beyond it (the dead peer never returns under restart:"no"), so auto-heal fires within
        // ~one departure-timeout; a cold-start reconnect dip resolves within it (QUIC view-change
        // reconnect is seconds, far below the 15s default), so the transient deficit is debounced
        // away. Reusing nttDepartureTimeout keeps the debounce consistent with the single
        // membership-timing constant rather than introducing a new literal.
        this.deficitDebounceWindow = membershipConfig.nttDepartureTimeout();
        this.inFlightExpiry = computeInFlightExpiry(membershipConfig.nttDepartureTimeout());
        this.presenceSampler = presenceSampler;
        this.membershipFsm = membershipFsm;
        this.configuredCoreCountSupplier = configuredCoreCountSupplier;
        this.leaderTermSupplier = leaderTermSupplier;
        this.ctm = ctm;
        this.clusterNameSupplier = clusterNameSupplier;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`] and the system clock.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    PresenceSampler presenceSampler,
                                                    MembershipFsm membershipFsm,
                                                    IntSupplier configuredCoreCountSupplier,
                                                    Supplier<Long> leaderTermSupplier,
                                                    ClusterTopologyManager ctm,
                                                    Supplier<String> clusterNameSupplier) {
        return new LeaderReconciler(membershipConfig,
                                    presenceSampler,
                                    membershipFsm,
                                    configuredCoreCountSupplier,
                                    leaderTermSupplier,
                                    ctm,
                                    clusterNameSupplier,
                                    TimeSource.system(),
                                    SharedScheduler::schedule);
    }

    /// Test factory accepting explicit [`TimeSource`] and [`NttTimerScheduler`] —
    /// required for deterministic activation/debounce assertions.
    public static LeaderReconciler leaderReconciler(MembershipConfig membershipConfig,
                                                    PresenceSampler presenceSampler,
                                                    MembershipFsm membershipFsm,
                                                    IntSupplier configuredCoreCountSupplier,
                                                    Supplier<Long> leaderTermSupplier,
                                                    ClusterTopologyManager ctm,
                                                    Supplier<String> clusterNameSupplier,
                                                    TimeSource timeSource,
                                                    NttTimerScheduler scheduler) {
        return new LeaderReconciler(membershipConfig,
                                    presenceSampler,
                                    membershipFsm,
                                    configuredCoreCountSupplier,
                                    leaderTermSupplier,
                                    ctm,
                                    clusterNameSupplier,
                                    timeSource,
                                    scheduler);
    }

    /// Activate the leader-pinned reconciler. Idempotent — if already active, returns
    /// without altering state.
    ///
    /// On the leader-edge transition: if leadership was gained via RE-ELECTION (leader term > 1,
    /// meaning a prior leader existed and the cluster already formed under it), pre-latch
    /// [`#reachedFullMembership`] so a new leader inheriting an already-formed, now-deficient
    /// cluster (e.g. 4/5) is not wedged in cold-start suppression by never re-observing full
    /// membership. Then schedule a single one-shot delayed reconcile at `nttDepartureTimeout × 1.5`.
    /// No immediate reconcile is emitted — the delay lets SWIM gossip and QUIC connections quiesce
    /// before the first reconcile pass runs.
    @Contract
    public void activate() {
        if (!isLeader.compareAndSet(false, true)) {
            return;
        }
        if (leaderTermSupplier.get() > 1L) {
            reachedFullMembership.set(true);
            log.info("LeaderReconciler re-election activation (leaderTerm={}) — reachedFullMembership pre-latched", leaderTermSupplier.get());
        }
        seedInFlightFromRetainedDispatched();
        var future = scheduler.schedule(this::onActivationDelayFire, leaderActivationDelay);

        activationFutureRef.set(future);
    }

    /// Provisioning-stickiness fix — on leadership GAIN, seed `inFlightProvisioning` from the prior
    /// leader's STICKILY-retained dispatched set (the metrics-ping `dispatchedNodes`, retained
    /// term-fenced by `ClusterSyncCollector`). Each retained id that is NOT already a current member
    /// is recorded in-flight (stamped with the same `timeSource.nanoTime()` clock the normal dispatch
    /// path uses, so the ×3 expiry backstop ages it identically), via `putIfAbsent` so a concurrent
    /// genuine dispatch is never clobbered. This makes the new leader INHERIT the prior leader's
    /// in-flight provisions instead of re-dispatching them (the over-provisioning / "6 cores instead
    /// of 5" bug). Ids that are already members are skipped — the provision already fulfilled.
    private void seedInFlightFromRetainedDispatched() {
        var retained = retainedDispatchedSupplier.get().get();
        if (retained.isEmpty()) {
            return;
        }
        var now = timeSource.nanoTime();
        var currentMembers = membershipFsm.countedMembers();
        for (var id : retained) {
            if (currentMembers.contains(id)) {
                continue;
            }
            inFlightProvisioning.putIfAbsent(id, now);
        }
        log.info("LeaderReconciler seeded in-flight provisioning from retained dispatched set (retained={}, inFlight={})",
                 retained.size(), inFlightProvisioning.size());
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
        cancelInFlightSweep();
        cancelDebounceReEval();
        inFlightProvisioning.clear();
        deficitSinceNanos = UNSET_NANOS;
        reachedFullMembership.set(false);
    }

    /// Live-event ingress for presence sampler timer-expiry. Stage 6 wires this from the presence sampler
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
    /// the SWIM observation listener filter. Non-leader nodes ignore. The `incarnation` is
    /// threaded for wiring consistency with the other SWIM-edge ingress points; this entry
    /// fires an unconditional reconcile trigger and does not itself fence — a fenced
    /// (lower-incarnation) peer is not in stableMembers, so triggering a reconcile is harmless.
    @Contract
    public void onSwimMemberHealthy(NodeId peerId, long incarnation) {
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
    /// `true` the first reconcile pass that observes the cluster at configured quorum
    /// (`clusterMembershipCount >= quorumThreshold(configuredCoreCount)`, `configuredCoreCount >=
    /// 1`); never resets within a leader term. While unarmed, provisioning is suppressed.
    public boolean isArmedForProvisioning() {
        return armedForProvisioning.get();
    }

    /// Observability — whether the reached-full-membership latch has set. Starts `false`; latches
    /// `true` the first reconcile pass that observes the cluster at FULL configured membership
    /// (`clusterMembershipCount >= configuredCoreCount`, `configuredCoreCount >= 1`), or on a
    /// RE-ELECTION activation (leader term > 1); never resets within a leader term. While `false`
    /// provisioning is suppressed (Bug C cold-start guard — replaces the buggy timer-anchored
    /// grace; membership-unification-spec P5).
    public boolean isReachedFullMembership() {
        return reachedFullMembership.get();
    }

    /// Observability — number of in-flight provisioning records this leader is tracking.
    public int inFlightProvisioningCount() {
        return inFlightProvisioning.size();
    }

    /// Provisioning-stickiness fix — the set of in-flight provisioning ids this leader currently
    /// tracks. Fed to the metrics ping's `dispatchedNodes` (via
    /// `ClusterSyncContext.setDispatchedNodesSupplier`) so followers retain it and a new leader can
    /// seed from it. Read-only snapshot.
    public Set<NodeId> inFlightProvisioningKeys() {
        return Set.copyOf(inFlightProvisioning.keySet());
    }

    /// Provisioning-stickiness fix — inject the supplier of the prior leader's STICKILY-retained
    /// dispatched set, consulted on leadership gain ([`#activate()`]) to seed `inFlightProvisioning`.
    /// `AetherNode` wires this to `ClusterSyncCollector::retainedDispatchedNodes`. `null` resets to
    /// the empty-set default (no seed).
    @Contract
    public void setRetainedDispatchedSupplier(Supplier<Set<NodeId>> supplier) {
        retainedDispatchedSupplier.set(supplier == null
                                       ? Set::of
                                       : supplier);
    }

    /// Observability — the one-shot leader-activation delay, computed once as
    /// `nttDepartureTimeout × 1.5`.
    public TimeSpan leaderActivationDelay() {
        return leaderActivationDelay;
    }

    /// Observability — the cold-start provisioning grace window (`nttDepartureTimeout × 1.5`).
    /// Provisioning is suppressed until this long after the arm-latch first armed.
    public TimeSpan provisioningGraceWindow() {
        return provisioningGraceWindow;
    }

    /// Observability — the deficit debounce window (`nttDepartureTimeout`). A deficit must persist
    /// at least this long (past the grace window) before provisioning fires.
    public TimeSpan deficitDebounceWindow() {
        return deficitDebounceWindow;
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
        log.info("LeaderReconciler activated (leadership gained) at nanoTime={}", timeSource.nanoTime());
        runReconcileBody(ReconcileTrigger.LEADER_ACTIVATION);
    }

    /// Single reconcile pass. Derives `clusterMembershipCount` and the current member set
    /// from [`PresenceSampler#currentMembers`], latches [`#reachedFullMembership`] on first
    /// observed full configured membership, then gates provisioning behind `quorumSafe &&
    /// armedForProvisioning && reachedFullMembership && deficitDebounced` (identity-aware rule —
    /// membership-unification-spec P5). Provisioning is suppressed until the latch reaches full
    /// membership so a deficit during initial formation / slow join never spawns phantom
    /// replacements for still-joining configured peers (Bug C); once full, the only remaining
    /// time-gate is the deficit-debounce. Draining is independently protected by a HARD FLOOR in
    /// [`#computePeersToDrain`]: it never drains confirmed members below `configuredCoreCount`
    /// in a single pass, so an `effective` count inflated by in-flight placeholders (a
    /// requested-but-not-joined replacement) can never trigger a quorum-dissolving drain of a
    /// healthy core node.
    @Contract
    private void runReconcileBody(ReconcileTrigger trigger) {
        var now = timeSource.nanoTime();

        evictExpiredInFlightEntries(now);

        var currentMembers = membershipFsm.countedMembers();
        // Identity-match clear (provision-fulfillment signal). With the completed
        // "boot under the leader-supplied id" contract, the in-flight provisioning key is the
        // EXACT id the replacement boots under, so once that id appears in `currentMembers` the
        // provision is fulfilled — the node joined under its minted identity and is now a
        // confirmed member. Removing it here keeps the in-flight map promptly accurate (it no
        // longer lingers in the union until TTL), so membership presence is the authoritative
        // fulfillment signal; the `× 3` TTL eviction above remains a pure backstop for provisions
        // that never arrive (failed boot). This runs BEFORE computing `effective`/effectiveCapacity
        // so a fulfilled id is never double-counted in the union.
        currentMembers.forEach(inFlightProvisioning::remove);

        var clusterMembershipCount = currentMembers.size();
        var configuredCoreCount = configuredCoreCountSupplier.getAsInt();
        // Effective capacity is the SIZE OF THE UNION of confirmed members and in-flight
        // provisioning placeholders — NOT their sum (safety-critical). The identity-match clear
        // above already removes any in-flight key that has become a confirmed member, so a
        // just-booted replacement no longer lingers in both buckets; the union-dedup remains as
        // belt-and-suspenders for the brief window between the membership view and this clear.
        // Summing the two sizes would double-count, inflating `effective` above the configured
        // core count and tricking the drain path into believing there is a surplus — which then
        // drains a healthy core node, drops the cluster below quorum, and dissolves it.
        // Deduplicating by NodeId counts each node exactly once.
        var effective = effectiveCapacity(currentMembers);

        // Arm-after-first-quorum latch (Bug C; membership-unification-spec P5 — identity-aware
        // reconciler, approximated by a quorum latch). Latch true the first time the cluster is
        // observed at configured QUORUM (not full membership); never resets. Arming at quorum
        // rather than full `configuredCoreCount` lets a quorum-holding leader auto-heal after a
        // multi-node kill where only the survivors remain and the dead peers (restart:"no") never
        // return — the process never re-observes full membership, so a full-count latch wedges
        // forever. A quorum-holding elected leader has by definition passed cold-start formation,
        // so a sustained sub-full deficit means departure, not slow-join. The cold-start phantom-
        // provisioning window is bounded by the leader-activation reconcile delay
        // (nttDepartureTimeout × 1.5), by which time formation has completed. configuredCoreCount
        // must be >= 1 to be armable.
        if (configuredCoreCount >= 1 && clusterMembershipCount >= quorumThreshold(configuredCoreCount)) {
            if (armedForProvisioning.compareAndSet(false, true)) {
                log.info("Provisioning ARMED at nanoTime={} (clusterMembershipCount={}, configuredCoreCount={}, quorumThreshold={})",
                         now, clusterMembershipCount, configuredCoreCount, quorumThreshold(configuredCoreCount));
            }
        }

        // Reached-full-membership latch (Bug C — cold-start-over is a FACT, not a timer). Sourced
        // from presence sampler's INDEPENDENT high-water mark ([`PresenceSampler#peakMembershipCount`]), NOT
        // the per-pass `clusterMembershipCount`: the reconciler is departure-triggered, so its FIRST
        // pass runs at the post-departure count (e.g. 4/5) and never during the full-membership
        // window — a per-pass `>= configured` check could therefore never latch (live Docker trace
        // proved it). presence sampler updates the peak on the 1→full formation growth regardless of reconcile
        // timing, so the first pass at 4/5 sees peak=5 → latches. Latch true once; never resets.
        // While false the cluster never reached full — a deficit may be a slow-joining configured
        // peer, so provisioning is suppressed (COLD_START_NOT_FULL). Once true the cold-start guard
        // no longer applies: a deficit is a departure, gated only by deficit-debounce +
        // quorum-safety + the arm latch.
        if (configuredCoreCount >= 1 && presenceSampler.peakMembershipCount() >= configuredCoreCount) {
            if (reachedFullMembership.compareAndSet(false, true)) {
                log.info("Reached full membership at nanoTime={} (peakMembershipCount={}, configuredCoreCount={})",
                         now, presenceSampler.peakMembershipCount(), configuredCoreCount);
            }
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
        // Deficit-edge tracking (debounce anchor). Update BEFORE the provisioning decision so the
        // gate sees the freshest run-length: set the leading-edge timestamp when a deficit first
        // appears, clear it the moment the deficit resolves. A transient reconnect dip clears the
        // anchor; a genuine departure lets it age.
        updateDeficitAnchor(now, effective, configuredCoreCount);

        var quorumSafe = clusterMembershipCount >= quorumThreshold(configuredCoreCount);
        var provisioningPermitted = quorumSafe && provisioningAllowed(now, effective, configuredCoreCount);
        logProvisioningDecision(now, trigger, currentMembers, effective, configuredCoreCount, quorumSafe, provisioningPermitted);
        var peersToProvision = provisioningPermitted ? computePeersToProvision(configuredCoreCount, effective) : Set.<NodeId>of();
        var peersToDrain = quorumSafe ? computePeersToDrain(currentMembers, configuredCoreCount, effective) : Set.<NodeId>of();

        dispatchProvisionActions(now, peersToProvision, currentMembers);
        dispatchDrainActions(peersToDrain);

        // Fix 2 — suppressed-deficit re-evaluation tick. A pass that has a quorum-safe, armed,
        // full-membership deficit suppressed ONLY by the deficit-debounce time gate schedules a
        // single follow-up reconcile after the remaining debounce window so the deficit is acted on
        // when the gate clears — instead of waiting for a presence sampler membership-change event that never
        // arrives while membership is stable in deficit. Deduped + bounded; does not busy-loop.
        scheduleDebounceReEvalIfNeeded(now, effective, configuredCoreCount, quorumSafe, provisioningPermitted);

        // Restart the debounce clock once we ACT on a deficit. The just-dispatched in-flight
        // placeholder makes `effective` meet target on the next pass (anchor would reset anyway),
        // but if the placeholder dies and the raw deficit re-opens with NO intervening at-target
        // pass to reset the anchor, a stale anchor would let the re-opened deficit provision
        // instantly — re-introducing the storm the debounce guards against. Resetting here forces
        // every fresh deficit run to re-age past the debounce window before the next provision.
        if (!peersToProvision.isEmpty()) {
            deficitSinceNanos = UNSET_NANOS;
        }

        var intent = ReconcileIntent.reconcileIntent(now,
                                                     trigger,
                                                     clusterMembershipCount,
                                                     configuredCoreCount,
                                                     peersToProvision.size(),
                                                     peersToDrain.size(),
                                                     inFlightProvisioning.size());

        reconcileListener.accept(intent);
    }

    /// Maintain the deficit-run leading-edge timestamp ([`#deficitSinceNanos`]). On the
    /// non-deficit→deficit edge, record `now` (start of a candidate deficit run). Whenever the
    /// deficit is absent (`effective >= configuredCoreCount`), reset to [`#UNSET_NANOS`] so a
    /// transient dip never accumulates debounce age across an intervening recovery.
    @Contract
    private void updateDeficitAnchor(long now, int effective, int configuredCoreCount) {
        if (effective < configuredCoreCount) {
            if (deficitSinceNanos == UNSET_NANOS) {
                deficitSinceNanos = now;
            }
        } else {
            deficitSinceNanos = UNSET_NANOS;
        }
    }

    /// Provisioning gate (three AND-ed conditions; provisioning fires only when ALL hold):
    /// (1) the arm-after-first-quorum latch has armed (Bug C — never provision for a configured
    /// peer still joining during formation); (2) the cluster has been OBSERVED at full configured
    /// membership ([`#reachedFullMembership`]) — the cold-start-over signal is a FACT, not a timer.
    /// While the cluster is still climbing to target during formation a deficit may be a
    /// slow-joining configured peer, so provisioning is suppressed; once full has been seen (or this
    /// leader was re-elected onto an already-formed cluster), a deficit is a departure;
    /// (3) the deficit has persisted past the debounce window (`now - deficitSinceNanos >=
    /// deficitDebounceWindow`, == nttDepartureTimeout) — a single transient pass below configured
    /// never provisions, only a sustained deficit (a real departure) does. The latch is one-time
    /// (never reset within a leader term); debounce is per-deficit-run (reset on recovery), so
    /// genuine later auto-heal is never permanently suppressed.
    private boolean provisioningAllowed(long now, int effective, int configuredCoreCount) {
        return armedForProvisioning.get()
               && reachedFullMembership.get()
               && deficitDebounced(now, effective, configuredCoreCount);
    }

    private boolean deficitDebounced(long now, int effective, int configuredCoreCount) {
        return effective < configuredCoreCount
               && deficitSinceNanos != UNSET_NANOS
               && now - deficitSinceNanos >= deficitDebounceWindow.nanos();
    }

    /// Operator-facing INFO trace (one line per reconcile pass that has a deficit OR a permitted
    /// provision; healthy at-target no-op passes are NOT logged, bounding volume). Records the full
    /// provisioning-decision context so a Docker run can pin exactly why no replacement is
    /// provisioned after a kill: trigger, membership count + member ids, effective capacity,
    /// configured core count, the arm latch, the reached-full-membership latch, deficit age, quorum
    /// safety, and the precise suppression REASON. Pure logging — computes deficitAgeMs locally,
    /// mutates no state, alters no control flow.
    @Contract
    private void logProvisioningDecision(long now,
                                         ReconcileTrigger trigger,
                                         Set<NodeId> currentMembers,
                                         int effective,
                                         int configuredCoreCount,
                                         boolean quorumSafe,
                                         boolean provisioningPermitted) {
        var hasDeficit = effective < configuredCoreCount;

        if (!hasDeficit && !provisioningPermitted) {
            return;
        }
        log.info("LeaderReconciler pass: trigger={} clusterMembershipCount={} peakMembershipCount={} members={} effective={} configuredCoreCount={} armedForProvisioning={} reachedFullMembership={} deficitAgeMs={} quorumSafe={} reason={}",
                 trigger,
                 currentMembers.size(),
                 presenceSampler.peakMembershipCount(),
                 currentMembers,
                 effective,
                 configuredCoreCount,
                 armedForProvisioning.get(),
                 reachedFullMembership.get(),
                 deficitAgeMs(now),
                 quorumSafe,
                 suppressionReason(effective, configuredCoreCount, quorumSafe, provisioningPermitted));
    }

    /// Age in milliseconds of the current deficit run (`now - deficitSinceNanos`), or `-1` when no
    /// deficit run is in progress. Pure logging computation.
    private long deficitAgeMs(long now) {
        if (deficitSinceNanos == UNSET_NANOS) {
            return -1L;
        }
        return (now - deficitSinceNanos) / 1_000_000L;
    }

    /// Precise suppression reason for the decision log. `NONE_PROVISIONING` when a provision is
    /// permitted; otherwise the first failing gate in evaluation order. Pure logging computation.
    private String suppressionReason(int effective,
                                     int configuredCoreCount,
                                     boolean quorumSafe,
                                     boolean provisioningPermitted) {
        if (provisioningPermitted) {
            return "NONE_PROVISIONING";
        }
        if (effective >= configuredCoreCount) {
            return "NO_DEFICIT";
        }
        if (!quorumSafe) {
            return "NOT_QUORUM_SAFE";
        }
        if (!armedForProvisioning.get()) {
            return "NOT_ARMED";
        }
        if (!reachedFullMembership.get()) {
            return "COLD_START_NOT_FULL";
        }
        return "WITHIN_DEBOUNCE";
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
            placeholders.add(NodeId.randomNodeId(replacementNodeIdPrefix()));
        }
        return Set.copyOf(placeholders);
    }

    /// Cluster-scoped prefix for auto-heal replacement ids so a replacement carries the
    /// same `aether-<cluster>-node-` form as its compose-seeded siblings (NodeId ==
    /// container name). Delegates unconditionally to the blank-defensive
    /// [`ProvisionContext#coreNodeNamePrefix`], which collapses a blank cluster name to the
    /// canonical `aether-node` prefix — never the bare `node` prefix, which would drop the
    /// replacement out of the `aether-<cluster>-node-` family the harness filters key on.
    private String replacementNodeIdPrefix() {
        return ProvisionContext.coreNodeNamePrefix(clusterNameSupplier.get());
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
        if (!peersToProvision.isEmpty()) {
            log.info("LeaderReconciler dispatching provision for {} peer(s): {}", peersToProvision.size(), peersToProvision);
        }
        peersToProvision.forEach(placeholder -> dispatchSingleProvision(nowNanos, placeholder, currentMembers));
    }

    @Contract
    private void dispatchSingleProvision(long nowNanos, NodeId placeholder, Set<NodeId> currentMembers) {
        inFlightProvisioning.put(placeholder, nowNanos);
        armInFlightSweep();
        // Pass the SAME minted placeholder as the new node's intended identity: the provisioned
        // node boots under exactly this id (CTM threads it into ProvisionContext.nodeId()), so the
        // in-flight key equals the booted node's id and membership presence is the authoritative
        // fulfillment signal (cleared in runReconcileBody when the id appears in currentMembers).
        ctm.provisionReplacement(placeholder, none(), currentMembers).onFailure(cause -> inFlightProvisioning.remove(placeholder));
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

    /// Arm a single self-rescheduling one-shot sweep that purges expired in-flight provisioning
    /// entries. The periodic full-reconcile tick was deliberately removed (it caused over-
    /// provisioning storms); this sweep is NOT that tick — it fires ONLY while in-flight entries
    /// exist and self-cancels once the map drains. It exists for the case where a provisioned
    /// replacement boots then dies before reaching READY: the SWIM/QUIC churn that would re-enter
    /// the event-triggered reconcile stops, so without this sweep the placeholder never expires and
    /// the deficit is never re-seen. Idempotent: at most one outstanding sweep future via the
    /// null-check + CAS guard, so a concurrent `dispatchSingleProvision` cannot orphan a timer.
    @Contract
    private void armInFlightSweep() {
        if (inFlightSweepFutureRef.get() != null) {
            return;
        }
        var future = scheduler.schedule(this::runInFlightSweep, inFlightExpiry);

        if (!inFlightSweepFutureRef.compareAndSet(null, future)) {
            future.cancel(false);
        }
    }

    /// Sweep tick: purge expired in-flight entries, re-trigger reconcile if a slot was reclaimed
    /// (so the deficit is re-evaluated), and re-arm only while in-flight entries remain. Re-arms
    /// via [`#armInFlightSweep`] (NOT a bare ref set) so a concurrently-armed future is never
    /// orphaned — the ref is nulled at the top, then `armInFlightSweep` arms cleanly or no-ops if a
    /// concurrent dispatch already armed.
    @Contract
    private void runInFlightSweep() {
        inFlightSweepFutureRef.set(null);
        if (!isLeader.get()) {
            return;
        }
        var before = inFlightProvisioning.size();

        evictExpiredInFlightEntries(timeSource.nanoTime());
        if (inFlightProvisioning.size() < before) {
            triggerReconcile(ReconcileTrigger.NTT_FIRE);
        }
        if (!inFlightProvisioning.isEmpty()) {
            armInFlightSweep();
        }
    }

    /// Fix 2 — schedule a single suppressed-deficit re-evaluation follow-up when this pass had a
    /// quorum-safe, armed, full-membership deficit suppressed ONLY by the deficit-debounce time gate
    /// (the `WITHIN_DEBOUNCE` reason). The follow-up fires after the remaining debounce window plus a
    /// small margin, re-entering the reconcile path so the now-aged deficit can provision — instead
    /// of waiting for a presence sampler membership-change event that never arrives while membership is stable in
    /// deficit. Deduped: a non-null [`#debounceReEvalFutureRef`] short-circuits (at most one pending),
    /// the CAS-arm cancels a lost race, so it never busy-loops or unbounded-schedules.
    @Contract
    private void scheduleDebounceReEvalIfNeeded(long now,
                                                int effective,
                                                int configuredCoreCount,
                                                boolean quorumSafe,
                                                boolean provisioningPermitted) {
        var suppressedByDebounceOnly = !provisioningPermitted
                                       && quorumSafe
                                       && armedForProvisioning.get()
                                       && reachedFullMembership.get()
                                       && effective < configuredCoreCount
                                       && deficitSinceNanos != UNSET_NANOS;

        if (!suppressedByDebounceOnly || debounceReEvalFutureRef.get() != null) {
            return;
        }
        var future = scheduler.schedule(this::runDebounceReEval, debounceReEvalDelay(now));

        if (!debounceReEvalFutureRef.compareAndSet(null, future)) {
            future.cancel(false);
        }
    }

    /// Remaining time until the deficit-debounce window elapses for the current deficit run, plus the
    /// short debounce margin so the follow-up reconcile observes the gate already cleared. Clamped to
    /// at least the margin so a near-elapsed deficit still defers by one short tick rather than firing
    /// inline.
    private TimeSpan debounceReEvalDelay(long now) {
        var elapsed = now - deficitSinceNanos;
        var remaining = deficitDebounceWindow.nanos() - elapsed;
        var delayNanos = Math.max(remaining, 0L) + DEBOUNCE_DELAY.nanos();

        return timeSpan(delayNanos).nanos();
    }

    /// Re-evaluation follow-up tick (Fix 2): clear the pending ref and re-trigger a reconcile so the
    /// sustained deficit is re-evaluated now that the debounce window has elapsed. Non-leader nodes
    /// no-op (a deposed leader's follow-up must not act).
    @Contract
    private void runDebounceReEval() {
        debounceReEvalFutureRef.set(null);
        if (!isLeader.get()) {
            return;
        }
        triggerReconcile(ReconcileTrigger.NTT_FIRE);
    }

    @Contract
    private void cancelDebounceReEval() {
        var prev = debounceReEvalFutureRef.getAndSet(null);

        if (prev != null) {
            prev.cancel(false);
        }
    }

    @Contract
    private void cancelPendingActivation() {
        var prev = activationFutureRef.getAndSet(null);

        if (prev != null) {
            prev.cancel(false);
        }
    }

    @Contract
    private void cancelInFlightSweep() {
        var sweep = inFlightSweepFutureRef.getAndSet(null);

        if (sweep != null) {
            sweep.cancel(false);
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

    /// Observability — the [`PresenceSampler`] collaborator the reconciler reads the
    /// current member set from (unified SWIM-fed membership source; includes self).
    public PresenceSampler presenceSampler() {
        return presenceSampler;
    }
}
