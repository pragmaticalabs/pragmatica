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
import org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Per-member membership FSM manager (membership v2, **Phase 2 LIVE** — the authoritative
/// membership-death decision-maker). It is no longer a passive shadow: it drives one
/// [`MembershipState`] FSM per [`NodeId`] from tapped SWIM / transport / liveness edges and, on
/// every transition into [`MembershipState.Dead`], hard-evicts the dead identity from the
/// [`NodeTopologyTracker`] (`ntt.evict(id)`). The presence-derived
/// `TopologyObserver → MembershipDecision → ClusterEventAggregator` path then emits the
/// `NODE_FAILED` / `NODE_LEFT` event from the resulting `stableMembers` delta — this FSM never
/// emits an event directly; mutating NTT's presence view is the sole side effect.
///
/// **Always-on per-node, consensus-independent, SWIM/liveness-driven.** The FSM is armed from
/// construction on EVERY node (not only the leader): each node drives its OWN per-member FSMs from its
/// tapped SWIM gossip + composite liveness and evicts from its OWN [`NodeTopologyTracker`] view,
/// independent of consensus health — so a dead member is removed even when the death decision must not
/// wait on a consensus round, and even on a follower. Only scaling (`LeaderReconciler`) stays
/// leader-gated; membership tracking and eviction are unconditional.
///
/// **Promotion is edge-driven (up-hysteresis = `UP_HYSTERESIS` = 1).** A member is promoted
/// OBSERVED→MEMBER on the FIRST `onSwimHealthy` observation. This consumes SWIM *edges* — SWIM emits
/// `HealthyObserved` exactly once, on the transition into ALIVE, not as a periodic sample — so a
/// single HealthyObserved IS the promotion signal: it means the node is healthy now. NTT's 2-sample
/// consecutive hysteresis ([`NodeTopologyTracker#K_UP_DEFAULT`]) models periodic samples and does
/// NOT apply to an edge consumer; a 2-sample requirement here would never be met because the edge
/// never fires twice. The healthy streak is still tracked (any doubt event resets it) but the
/// promotion threshold is 1. On reaching the threshold the manager dispatches [`UpHysteresisMet`] to
/// the member's FSM (OBSERVED→MEMBER).
///
/// **One-time seed at boot.** Although the always-on FSM has its SWIM observation taps attached at
/// construction (before SWIM begins emitting), [`#seed`] is kept as a belt-and-suspenders boot
/// bootstrap from the initially-known members (e.g. the configured topology member set): each untracked
/// or still-OBSERVED id is promoted straight to MEMBER. It NEVER touches an id already in
/// MEMBER/SUSPECT/DEPARTING/DEAD — death stays the manager's own SWIM/liveness decision and is never
/// resurrected from the seed snapshot. After the seed, members promote independently via their first
/// `HealthyObserved` edge.
///
/// **Eviction co-confirmation gate (`swimFaulty ∧ livenessGone`).** A member is driven
/// Suspect→Departing→Dead only when BOTH planes confirm death: SWIM has declared it FAULTY
/// (`swimFaultySeen`) AND it is liveness-confirmed-gone (`livenessGoneSeen`). A single plane (bare
/// SWIM-FAULTY, bare liveness-gone) moves the member to SUSPECT (which STILL counts toward effective —
/// the churn cure) but never to DEAD. When the gate is satisfied the manager dispatches
/// [`DownHysteresisMet`] then [`Stopped`] (Suspect→Departing→Dead). A graceful `onSwimDeparted` and an
/// `onJoinGraceExpired` reach DEAD directly without the co-confirmation gate.
///
/// **DEAD → `ntt.evict`.** Entry into DEAD is detected CENTRALLY in [`MemberTracking#dispatch`]:
/// after every dispatch it compares the FSM's pre/post state and, on a fresh edge INTO `Dead` (was
/// not Dead before, is Dead after), invokes the manager's eviction hook. This covers ALL three DEAD
/// paths uniformly (co-confirmed death, graceful departure, join-grace expiry) without scattering the
/// call across the ingress methods. The hook is idempotent — the fresh-edge guard fires once per
/// death, and `ntt.evict` is itself idempotent (a no-op for an id already absent from `stableMembers`).
///
/// **DEAD retention + rejoin.** DEAD FSM entries are KEPT in the map (never removed on death) so a
/// higher-incarnation [`SwimHealthy`] re-arms the same identity (DEAD→OBSERVED, fenced by the
/// incarnation high-water mark in [`MembershipContext`]); a stale (same-or-lower) incarnation leaves
/// it DEAD.
///
/// **Concurrency.** Ingress may be called from the SWIM / transport / liveness tap threads. The FSM
/// map is concurrent; per-member bookkeeping ([`MemberTracking`]) is mutated only under the per-member
/// monitor so the streak / co-confirmation flags stay internally consistent.
public final class MembershipFsm {
    private static final Logger log = LoggerFactory.getLogger(MembershipFsm.class);

    /// FSM kind tag — groups all per-member FSMs under one name for observer dashboards.
    private static final String FSM_KIND = "membership";

    /// Up-hysteresis promotion threshold for this **edge-driven** manager (= 1). SWIM emits
    /// `HealthyObserved` once, on the edge into ALIVE — not as a periodic sample — so the first
    /// observation is the promotion signal. NTT's 2-sample
    /// [`NodeTopologyTracker#K_UP_DEFAULT`] models periodic sampling and does NOT apply here: a
    /// 2-sample requirement would never be met because the edge never fires twice for the same
    /// transition.
    public static final int UP_HYSTERESIS = 1;

    private final FsmObserver<MembershipState, MembershipEvent> observer;
    private final NodeTopologyTracker ntt;
    private final Map<NodeId, MemberTracking> members = new ConcurrentHashMap<>();

    private MembershipFsm(NodeTopologyTracker ntt, FsmObserver<MembershipState, MembershipEvent> observer) {
        this.ntt = ntt;
        this.observer = observer;
    }

    /// Factory with the default no-op transition observer.
    public static MembershipFsm membershipFsm(NodeTopologyTracker ntt) {
        return membershipFsm(ntt, FsmObserver.noop());
    }

    /// Factory with an explicit transition observer (transition logging / metrics).
    public static MembershipFsm membershipFsm(NodeTopologyTracker ntt, FsmObserver<MembershipState, MembershipEvent> observer) {
        return new MembershipFsm(ntt, observer);
    }

    // --- Boot seed ---

    /// One-time boot bootstrap: promote each id in the initially-known member snapshot (e.g. the
    /// configured topology member set) that the always-on FSM has not yet observed healthy. For every
    /// id that is UNTRACKED or currently OBSERVED, promote it straight to MEMBER (creating its FSM if
    /// needed and dispatching [`UpHysteresisMet`]). Ids already in MEMBER/SUSPECT/DEPARTING/DEAD are left
    /// untouched — a dead/suspect node is NEVER resurrected from the seed snapshot; death stays the
    /// manager's own SWIM/liveness decision. Idempotent (re-seeding a MEMBER touches nothing). Because
    /// the SWIM observation taps are attached at construction (before SWIM emits), members also promote
    /// naturally via their first `HealthyObserved` edge — the seed is belt-and-suspenders.
    @Contract
    public void seed(Set<NodeId> initialMembers) {
        initialMembers.forEach(this::seedMember);
    }

    // --- Ingress (live taps feed these; always-on) ---

    /// Upsert the last-wins network descriptor (address + role + source) for `info.id()` from a
    /// NodeInfo-bearing SWIM observation (JoinAnnounced / MemberDiscovered). Leader-gate-free and
    /// orthogonal to the lifecycle FSM: it lazily creates the member's tracking via [`#trackingFor`]
    /// (leaving its state in OBSERVED) and overwrites only the descriptor, so the address/role/source
    /// become known the moment the first NodeInfo lands and a later observation replaces them.
    @Contract
    public void onMemberDescriptor(NodeInfo info) {
        trackingFor(info.id()).updateDescriptor(MemberDescriptor.fromNodeInfo(info));
    }

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

    /// The NTT down-hysteresis threshold was crossed for `id` (sustained absence over the
    /// down-hysteresis window). Routes that crossing INTO this FSM so a sustained-absence SUSPECT
    /// member is bounded by the FSM (SUSPECT→DEPARTING per spec §3.3 / invariant I4), rather than NTT
    /// independently removing the id from its own set. Always-on; ignored in any state but SUSPECT.
    @Contract
    public void onDownHysteresisMet(NodeId id) {
        withMember(id, tracking -> tracking.dispatch(new DownHysteresisMet()));
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

    /// Set form of [`#effective`]: the NodeIds of every tracked member whose current state
    /// [`MembershipState#countsTowardEffective`] is true (MEMBER + SUSPECT). Insertion-ordered
    /// (a [`LinkedHashSet`], matching [`#memberStates`]) for stable iteration. Its size always
    /// equals [`#effective`].
    public Set<NodeId> countedMembers() {
        return members.entrySet()
                      .stream()
                      .filter(entry -> entry.getValue().countsTowardEffective())
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// Provisioning deficit: `max(0, configuredCoreCount - effective())`.
    public int wouldProvision(int configuredCoreCount) {
        return Math.max(0, configuredCoreCount - effective());
    }

    /// Drain surplus: `max(0, effective() - configuredCoreCount)`.
    public int wouldDrain(int configuredCoreCount) {
        return Math.max(0, effective() - configuredCoreCount);
    }

    /// Snapshot of each tracked member's current state name. Insertion-ordered for stable logging.
    public Map<NodeId, String> memberStates() {
        var snapshot = new LinkedHashMap<NodeId, String>();

        members.forEach((id, tracking) -> snapshot.put(id, tracking.stateName()));
        return snapshot;
    }

    // --- Projections (desired connection-set for the transport executor) ---

    /// The core membership set the transport executor should keep mesh-connected: counted members
    /// (MEMBER + SUSPECT) that are NOT explicitly role=worker. An unknown / absent role counts as
    /// included, so an all-core cluster with no role labels yields every counted member. Insertion-
    /// ordered ([`LinkedHashSet`]) for stable iteration.
    public Set<NodeId> coreMembers() {
        return members.entrySet()
                      .stream()
                      .filter(entry -> entry.getValue().isCoreCountedMember())
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// The desired dial-set for the transport executor: [`#coreMembers`] intersected with members whose
    /// address is known, each mapped to a [`PeerTarget`] `(id, address)`. A member whose descriptor has
    /// not yet supplied an address is skipped — it reappears once a NodeInfo observation lands.
    public Set<PeerTarget> desiredConnections() {
        return members.entrySet()
                      .stream()
                      .filter(entry -> entry.getValue().isCoreCountedMember())
                      .flatMap(entry -> entry.getValue().peerTarget(entry.getKey()).stream())
                      .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// Filter `candidates` to those that currently COUNT (MEMBER + SUSPECT) per [`#countedMembers`].
    /// Used by the forward-routing consumer migration to drop candidates the FSM no longer considers
    /// live. Preserves the caller's candidate order.
    public List<NodeId> reachableMembers(List<NodeId> candidates) {
        var counted = countedMembers();

        return candidates.stream()
                         .filter(counted::contains)
                         .toList();
    }

    // --- Per-member transition drivers (NTT promotion + co-confirmation eviction) ---

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

    /// Seed-promote a single id (the one-time formation bootstrap). The (lazily-created) tracking is
    /// promoted OBSERVED→MEMBER only while it is still OBSERVED; an id already past OBSERVED (MEMBER /
    /// SUSPECT / DEPARTING / DEAD) is left untouched, so a dead/suspect node is never resurrected by the
    /// live snapshot. `promoteIfObserved` performs the OBSERVED guard and the [`UpHysteresisMet`]
    /// dispatch atomically under the per-member monitor.
    private void seedMember(NodeId id) {
        trackingFor(id).promoteIfObserved();
    }

    /// Co-confirmation gate: only when BOTH planes confirm death (SWIM-FAULTY ∧ liveness-gone) drive the
    /// confirmed-eviction edge Suspect→Departing→Dead via [`DownHysteresisMet`] then [`Stopped`]. A
    /// single-plane signal leaves the member in SUSPECT (still counts) — the churn cure against a
    /// single-plane false positive.
    private void maybeConfirmDeparture(MemberTracking tracking) {
        if (tracking.coConfirmedDead()) {
            tracking.dispatch(new DownHysteresisMet());
            tracking.dispatch(new Stopped());
            tracking.clearConfirmedDeath();
        }
    }

    /// Hard-evict hook invoked CENTRALLY on every fresh edge into DEAD (detected in
    /// [`MemberTracking#dispatch`]). Idempotent — the fresh-edge guard fires once per death and
    /// [`NodeTopologyTracker#evict`] is itself idempotent. Mutating NTT's presence view is the sole side
    /// effect; the presence-derived TopologyObserver path emits the resulting NODE_FAILED / NODE_LEFT
    /// event.
    @Contract
    private void onEnteredDead(NodeId id) {
        log.info("MembershipFsm evicting co-confirmed-dead member {} from NTT", id);
        ntt.evict(id);
    }

    // --- Dispatch frame ---

    /// Run `action` against the (lazily-created) tracking for `id`, dispatching to the member's FSM.
    /// JBCT code returns errors as values (`Result`/`Option`), never throws, so no try/catch is needed.
    @Contract
    private void withMember(NodeId id, Consumer<MemberTracking> action) {
        action.accept(trackingFor(id));
    }

    /// Lazily create the per-member tracking on first observation. A DEAD entry is KEPT, so this
    /// returns the existing tracking and a higher-incarnation SwimHealthy re-arms it in place.
    private MemberTracking trackingFor(NodeId id) {
        return members.computeIfAbsent(id, this::newTracking);
    }

    private MemberTracking newTracking(NodeId id) {
        var fsm = Fsm.fsm(FSM_KIND, id.id(), initialStateFactory(id), observer);
        return new MemberTracking(id, fsm, this::onEnteredDead);
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
    /// stay internally consistent under concurrent tap threads. Every [`#dispatch`] detects a fresh
    /// edge into DEAD (was-not-Dead → is-Dead) and fires the eviction hook exactly once per death.
    private static final class MemberTracking {
        private final NodeId id;
        private final Fsm<MembershipState, MembershipEvent> fsm;
        private final Consumer<NodeId> onEnteredDead;
        private int healthyStreak = 0;
        private boolean swimFaultySeen = false;
        private boolean livenessGoneSeen = false;
        private MemberDescriptor descriptor = MemberDescriptor.UNKNOWN;

        private MemberTracking(NodeId id, Fsm<MembershipState, MembershipEvent> fsm, Consumer<NodeId> onEnteredDead) {
            this.id = id;
            this.fsm = fsm;
            this.onEnteredDead = onEnteredDead;
        }

        /// Dispatch `event` to the FSM and, on a FRESH edge into DEAD (was not Dead before, is Dead
        /// after), fire the eviction hook exactly once. Centralized here so ALL DEAD paths (co-confirmed
        /// death, graceful departure, join-grace expiry) are covered uniformly without per-ingress
        /// scattering.
        synchronized void dispatch(MembershipEvent event) {
            var wasDead = isDead();

            fsm.dispatch(event);
            if (!wasDead && isDead()) {
                onEnteredDead.accept(id);
            }
        }

        private boolean isDead() {
            return fsm.current() instanceof MembershipState.Dead;
        }

        /// Seed-promotion guard: dispatch [`UpHysteresisMet`] (OBSERVED→MEMBER) only when the FSM is
        /// still in OBSERVED. An id already past OBSERVED (MEMBER / SUSPECT / DEPARTING / DEAD) is left
        /// untouched, so the live-snapshot seed never resurrects a dead/suspect node. Guard + dispatch
        /// are atomic under the per-member monitor.
        synchronized void promoteIfObserved() {
            if (fsm.current() instanceof MembershipState.Observed) {
                fsm.dispatch(new UpHysteresisMet());
            }
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

        /// Last-wins upsert of the network descriptor from a NodeInfo observation. Orthogonal to the
        /// lifecycle FSM — never touches the FSM state.
        synchronized void updateDescriptor(MemberDescriptor next) {
            descriptor = next;
        }

        boolean countsTowardEffective() {
            return fsm.current().countsTowardEffective();
        }

        /// Whether this member belongs in the core dial-set: it currently counts (MEMBER + SUSPECT)
        /// AND its descriptor role is not the explicit literal `worker` (unknown role = included).
        synchronized boolean isCoreCountedMember() {
            return countsTowardEffective() && descriptor.isCore();
        }

        /// The [`PeerTarget`] for this member iff its descriptor has a known address; empty otherwise
        /// (the dial-set skips address-unknown members until a NodeInfo observation lands).
        synchronized Option<NodeAddress> address() {
            return descriptor.address();
        }

        Option<PeerTarget> peerTarget(NodeId memberId) {
            return address().map(addr -> new PeerTarget(memberId, addr));
        }

        String stateName() {
            return fsm.current().getClass().getSimpleName();
        }
    }
}
