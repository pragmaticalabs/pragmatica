// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.DownHysteresisMet;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.JoinGraceExpiredNeverHealthy;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.LivenessGone;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.PeerConnected;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.PeerDisconnected;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.Stopped;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimDeparted;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimFaulty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimHealthy;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimSuspect;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimUnknown;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.UpHysteresisMet;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/// Per-member shadow membership FSM manager (membership v2, **Phase 1 SHADOW** — observes only, acts
/// on NOTHING). Drives one [`MembershipState`] FSM per [`NodeId`] from tapped live events and computes
/// the cluster aggregate (effective / would-provision / would-drain) the divergence reporter (next
/// task) will diff against the live [`org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler`]
/// decision. This component performs NO provisioning, NO draining, NO eviction — it is a passive
/// model that lets us compare the FSM's verdict against the live path before any cut-over.
///
/// **Promotion mirrors NTT (up-hysteresis = `K_UP_DEFAULT` = 2).** A member is promoted
/// OBSERVED→MEMBER after [`#UP_HYSTERESIS`] consecutive `onSwimHealthy` observations — the same fast
/// admit threshold [`org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker#K_UP_DEFAULT`]
/// uses. The healthy streak is per-member; any doubt event resets it, so a flap never accumulates
/// toward promotion across directions. On reaching the threshold the manager dispatches
/// [`UpHysteresisMet`] to the member's FSM (OBSERVED→MEMBER).
///
/// **Eviction mirrors the LeaderReconciler co-confirmation gate (`swimFaulty ∧ livenessGone`).** A
/// member is driven Suspect→Departing→Dead only when BOTH planes confirm death: SWIM has declared it
/// FAULTY (`swimFaultySeen`) AND it is liveness-confirmed-gone (`livenessGoneSeen`) — exactly the
/// `evictIfConfirmedDead` gate in the live reconciler. A single plane (bare SWIM-FAULTY, bare
/// liveness-gone) moves the member to SUSPECT (which STILL counts toward effective — the churn cure)
/// but never to DEAD. When the gate is satisfied the manager dispatches [`DownHysteresisMet`] then
/// [`Stopped`] (Suspect→Departing→Dead — the shadow's view of a confirmed eviction). The debounce is
/// kept deliberately simple: co-confirmation is the gate, no time window — transient divergences from
/// the live (timer-debounced) path are expected and acceptable in shadow mode.
///
/// **Purely observational.** The manager runs CHAINED behind a live consumer (the read-only tap) and
/// only reads/records — it never provisions, drains, or evicts. JBCT code returns errors as values
/// (`Result`/`Option`) rather than throwing, so an ingress call cannot propagate an exception into the
/// live path; no try/catch isolation is needed.
///
/// **Leader-gating.** Like the live reconciler, the manager only tracks while it holds the leader
/// lease. [`#activate`] arms it; [`#deactivate`] clears the FSM map and all bookkeeping (a new leader
/// term starts fresh). All ingress methods no-op while inactive.
///
/// **DEAD retention + rejoin.** DEAD FSM entries are KEPT in the map (never removed on death) so a
/// higher-incarnation [`SwimHealthy`] re-arms the same identity (DEAD→OBSERVED, fenced by the
/// incarnation high-water mark in [`MembershipContext`]); a stale (same-or-lower) incarnation leaves
/// it DEAD.
///
/// **Concurrency.** Ingress may be called from the SWIM / transport / liveness tap threads. The FSM
/// map and the leader flag are concurrent; per-member bookkeeping ([`MemberTracking`]) is mutated only
/// under the per-member monitor so the streak / co-confirmation flags stay internally consistent.
public final class ShadowMembershipFsm {
    private static final Logger log = LoggerFactory.getLogger(ShadowMembershipFsm.class);

    /// FSM kind tag — groups all per-member shadow FSMs under one name for observer dashboards.
    private static final String FSM_KIND = "shadow-membership";

    /// Up-hysteresis promotion threshold, mirroring
    /// [`org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker#K_UP_DEFAULT`] (= 2):
    /// the consecutive-healthy-sample streak at which OBSERVED is promoted to MEMBER.
    public static final int UP_HYSTERESIS = 2;

    private final FsmObserver<MembershipState, MembershipEvent> observer;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Map<NodeId, MemberTracking> members = new ConcurrentHashMap<>();

    private ShadowMembershipFsm(FsmObserver<MembershipState, MembershipEvent> observer) {
        this.observer = observer;
    }

    /// Factory with the default no-op transition observer.
    public static ShadowMembershipFsm shadowMembershipFsm() {
        return shadowMembershipFsm(FsmObserver.noop());
    }

    /// Factory with an explicit transition observer (transition logging / metrics).
    public static ShadowMembershipFsm shadowMembershipFsm(FsmObserver<MembershipState, MembershipEvent> observer) {
        return new ShadowMembershipFsm(observer);
    }

    // --- Leader gating ---

    /// Arm the shadow manager on leader gain. Idempotent.
    @Contract
    public void activate() {
        active.set(true);
    }

    /// Disarm the shadow manager on leader loss. Idempotent. Clears the per-member FSM map and all
    /// bookkeeping so a new leader term starts from a clean model.
    @Contract
    public void deactivate() {
        active.set(false);
        members.clear();
    }

    /// Observability — whether the shadow manager currently holds the leader lease.
    public boolean isActive() {
        return active.get();
    }

    // --- Ingress (read-only taps feed these; each is leader-gated) ---

    /// SWIM reported `id` ALIVE at `incarnation`. Records the incarnation, bumps the consecutive-
    /// healthy streak, and promotes OBSERVED→MEMBER once the streak reaches [`#UP_HYSTERESIS`].
    /// Clears the co-confirmation flags (a healthy sample retracts both death signals).
    @Contract
    public void onSwimHealthy(NodeId id, long incarnation) {
        withMember(id, tracking -> healthy(tracking, incarnation));
    }

    /// SWIM reported `id` SUSPECT at `incarnation`. Moves MEMBER→SUSPECT (which still counts toward
    /// effective) and resets the healthy streak. A bare SUSPECT does not arm the co-confirmation gate.
    @Contract
    public void onSwimSuspect(NodeId id, long incarnation) {
        withMember(id, tracking -> suspect(tracking, incarnation));
    }

    /// SWIM reported `id` FAULTY at `incarnation`. Moves toward SUSPECT, resets the healthy streak,
    /// sets the SWIM-FAULTY co-confirmation flag, and confirms departure iff liveness is also gone.
    @Contract
    public void onSwimFaulty(NodeId id, long incarnation) {
        withMember(id, tracking -> faulty(tracking, incarnation));
    }

    /// SWIM reported `id` DEPARTED gracefully at `incarnation`. Drives Departing then Dead (clean
    /// leave): a graceful departure needs no co-confirmation.
    @Contract
    public void onSwimDeparted(NodeId id, long incarnation) {
        withMember(id, tracking -> departed(tracking, incarnation));
    }

    /// SWIM reported `id` in the UNKNOWN bucket at `incarnation`. The FSM ignores it; only the
    /// incarnation high-water mark is recorded.
    @Contract
    public void onSwimUnknown(NodeId id, long incarnation) {
        withMember(id, tracking -> tracking.dispatch(new SwimUnknown(incarnation)));
    }

    /// Transport reported a peer connection established for `id`.
    @Contract
    public void onPeerConnected(NodeId id) {
        withMember(id, tracking -> tracking.dispatch(new PeerConnected()));
    }

    /// Transport reported a peer connection dropped for `id`.
    @Contract
    public void onPeerDisconnected(NodeId id) {
        withMember(id, tracking -> tracking.dispatch(new PeerDisconnected()));
    }

    /// Composite liveness signal for `id` lost (no probe-ack within the liveness window). Moves toward
    /// SUSPECT, sets the liveness-gone co-confirmation flag, and confirms departure iff SWIM has also
    /// declared the peer FAULTY.
    @Contract
    public void onLivenessGone(NodeId id) {
        withMember(id, this::livenessGone);
    }

    /// The join-grace window expired for `id` without it ever reaching healthy. Drives OBSERVED→DEAD
    /// (never silently counted); a no-op once the member has progressed past OBSERVED.
    @Contract
    public void onJoinGraceExpired(NodeId id) {
        withMember(id, tracking -> tracking.dispatch(new JoinGraceExpiredNeverHealthy()));
    }

    // --- Aggregate (spec §3.4) ---

    /// Effective on-duty count = number of tracked members whose current state
    /// [`MembershipState#countsTowardEffective`] is true (MEMBER + SUSPECT — SUSPECT still counts, the
    /// churn cure).
    public int effective() {
        return (int) members.values()
                            .stream()
                            .filter(MemberTracking::countsTowardEffective)
                            .count();
    }

    /// Provisioning deficit the live reconciler WOULD act on: `max(0, configuredCoreCount -
    /// effective())`. Shadow-only — never dispatched anywhere.
    public int wouldProvision(int configuredCoreCount) {
        return Math.max(0, configuredCoreCount - effective());
    }

    /// Drain surplus the live reconciler WOULD act on: `max(0, effective() - configuredCoreCount)`.
    /// Shadow-only — never dispatched anywhere.
    public int wouldDrain(int configuredCoreCount) {
        return Math.max(0, effective() - configuredCoreCount);
    }

    /// Snapshot of each tracked member's current state name, for the divergence reporter to diff
    /// against the live membership view. Insertion-ordered for stable logging.
    public Map<NodeId, String> memberStates() {
        var snapshot = new LinkedHashMap<NodeId, String>();

        members.forEach((id, tracking) -> snapshot.put(id, tracking.stateName()));
        return snapshot;
    }

    // --- Per-member transition drivers (mirror NTT promotion + LeaderReconciler co-confirmation) ---

    private void healthy(MemberTracking tracking, long incarnation) {
        tracking.dispatch(new SwimHealthy(incarnation));
        tracking.clearConfirmedDeath();
        if (tracking.bumpHealthyStreakReachedThreshold()) {
            tracking.dispatch(new UpHysteresisMet());
        }
    }

    private void suspect(MemberTracking tracking, long incarnation) {
        tracking.resetHealthyStreak();
        tracking.dispatch(new SwimSuspect(incarnation));
    }

    private void faulty(MemberTracking tracking, long incarnation) {
        tracking.resetHealthyStreak();
        tracking.dispatch(new SwimFaulty(incarnation));
        tracking.markSwimFaulty();
        maybeConfirmDeparture(tracking);
    }

    private void departed(MemberTracking tracking, long incarnation) {
        tracking.resetHealthyStreak();
        tracking.dispatch(new SwimDeparted(incarnation));
        tracking.dispatch(new Stopped());
    }

    private void livenessGone(MemberTracking tracking) {
        tracking.dispatch(new LivenessGone());
        tracking.markLivenessGone();
        maybeConfirmDeparture(tracking);
    }

    /// Co-confirmation gate mirroring [`LeaderReconciler#evictIfConfirmedDead`]: only when BOTH planes
    /// confirm death (SWIM-FAULTY ∧ liveness-gone) drive the shadow's confirmed-eviction edge
    /// Suspect→Departing→Dead via [`DownHysteresisMet`] then [`Stopped`]. A single-plane signal leaves
    /// the member in SUSPECT (still counts) — the churn cure against a single-plane false positive.
    private void maybeConfirmDeparture(MemberTracking tracking) {
        if (tracking.coConfirmedDead()) {
            tracking.dispatch(new DownHysteresisMet());
            tracking.dispatch(new Stopped());
            tracking.clearConfirmedDeath();
        }
    }

    // --- Leader-gated dispatch frame ---

    /// Run `action` against the (lazily-created) tracking for `id`, leader-gated: a no-op while
    /// inactive, otherwise dispatch to the member's FSM. JBCT code returns errors as values
    /// (`Result`/`Option`), never throws, so no try/catch is needed; the shadow only reads/records.
    @Contract
    private void withMember(NodeId id, Consumer<MemberTracking> action) {
        if (!active.get()) {
            return;
        }
        action.accept(trackingFor(id));
    }

    /// Lazily create the per-member tracking on first observation. A DEAD entry is KEPT, so this
    /// returns the existing tracking and a higher-incarnation SwimHealthy re-arms it in place.
    private MemberTracking trackingFor(NodeId id) {
        return members.computeIfAbsent(id, this::newTracking);
    }

    private MemberTracking newTracking(NodeId id) {
        var fsm = Fsm.fsm(FSM_KIND, id.id(), initialStateFactory(id), observer);
        return new MemberTracking(fsm);
    }

    /// Explicitly-typed initial-state factory so the [`Fsm#fsm`] constructor-driven overload is
    /// selected unambiguously (the bare lambda is ambiguous against the plain `S initial` overload).
    /// Builds the per-member [`MembershipContext`] bound to the partially-constructed FSM and returns
    /// its OBSERVED initial state.
    private static Function<Fsm<MembershipState, MembershipEvent>, MembershipState> initialStateFactory(NodeId id) {
        return holder -> new MembershipContext(holder, id).observed();
    }

    /// Mutable per-member bookkeeping: the member's FSM plus the promotion streak counter and the two
    /// co-confirmation flags. All mutation is guarded by the instance monitor so the streak and flags
    /// stay internally consistent under concurrent tap threads.
    private static final class MemberTracking {
        private final Fsm<MembershipState, MembershipEvent> fsm;
        private int healthyStreak = 0;
        private boolean swimFaultySeen = false;
        private boolean livenessGoneSeen = false;

        private MemberTracking(Fsm<MembershipState, MembershipEvent> fsm) {
            this.fsm = fsm;
        }

        synchronized void dispatch(MembershipEvent event) {
            fsm.dispatch(event);
        }

        synchronized boolean bumpHealthyStreakReachedThreshold() {
            healthyStreak++;
            return healthyStreak >= UP_HYSTERESIS;
        }

        synchronized void resetHealthyStreak() {
            healthyStreak = 0;
        }

        synchronized void markSwimFaulty() {
            swimFaultySeen = true;
        }

        synchronized void markLivenessGone() {
            livenessGoneSeen = true;
        }

        synchronized boolean coConfirmedDead() {
            return swimFaultySeen && livenessGoneSeen;
        }

        synchronized void clearConfirmedDeath() {
            swimFaultySeen = false;
            livenessGoneSeen = false;
        }

        boolean countsTowardEffective() {
            return fsm.current().countsTowardEffective();
        }

        String stateName() {
            return fsm.current().getClass().getSimpleName();
        }
    }
}
