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
import org.pragmatica.aether.deployment.membership.ntt.PresenceSampler;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/// Per-member membership FSM manager (membership v2, **Phase 2 LIVE** — the authoritative
/// membership-death decision-maker). It is no longer a passive shadow: it drives one
/// [`MembershipState`] FSM per [`NodeId`] from tapped SWIM / transport / liveness edges and, on
/// every transition into [`MembershipState.Dead`], hard-evicts the dead identity from the
/// [`PresenceSampler`] (`presenceSampler.evict(id)`). The presence-derived
/// `TopologyObserver → MembershipDecision → ClusterEventAggregator` path then emits the
/// `NODE_FAILED` / `NODE_LEFT` event from the resulting `stableMembers` delta — this FSM never
/// emits an event directly; mutating presence sampler's presence view is the sole side effect.
///
/// **Always-on per-node, consensus-independent, SWIM/liveness-driven.** The FSM is armed from
/// construction on EVERY node (not only the leader): each node drives its OWN per-member FSMs from its
/// tapped SWIM gossip + composite liveness and evicts from its OWN [`PresenceSampler`] view,
/// independent of consensus health — so a dead member is removed even when the death decision must not
/// wait on a consensus round, and even on a follower. Only scaling (`LeaderReconciler`) stays
/// leader-gated; membership tracking and eviction are unconditional.
///
/// **Promotion is edge-driven (up-hysteresis = `UP_HYSTERESIS` = 1).** A member is promoted
/// OBSERVED→MEMBER on the FIRST `onSwimHealthy` observation. This consumes SWIM *edges* — SWIM emits
/// `HealthyObserved` exactly once, on the transition into ALIVE, not as a periodic sample — so a
/// single HealthyObserved IS the promotion signal: it means the node is healthy now. presence sampler's 2-sample
/// consecutive hysteresis ([`PresenceSampler#K_UP_DEFAULT`]) models periodic samples and does
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
/// **DEAD → `presenceSampler.evict`.** Entry into DEAD is detected CENTRALLY in [`MemberTracking#dispatch`]:
/// after every dispatch it compares the FSM's pre/post state and, on a fresh edge INTO `Dead` (was
/// not Dead before, is Dead after), invokes the manager's eviction hook. This covers ALL three DEAD
/// paths uniformly (co-confirmed death, graceful departure, join-grace expiry) without scattering the
/// call across the ingress methods. The hook is idempotent — the fresh-edge guard fires once per
/// death, and `presenceSampler.evict` is itself idempotent (a no-op for an id already absent from `stableMembers`).
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
    /// observation is the promotion signal. presence sampler's 2-sample
    /// [`PresenceSampler#K_UP_DEFAULT`] models periodic sampling and does NOT apply here: a
    /// 2-sample requirement would never be met because the edge never fires twice for the same
    /// transition.
    public static final int UP_HYSTERESIS = 1;

    private final FsmObserver<MembershipState, MembershipEvent> observer;
    private final PresenceSampler presenceSampler;
    private final Map<NodeId, MemberTracking> members = new ConcurrentHashMap<>();

    /// Wall-clock source (ms) used to stamp every fresh SUSPECT-inducing doubt and to age the
    /// quiesce SUSPECTED health-hint out after [`#suspectHintTtlMs`]. Injectable so tests can drive a
    /// controllable clock; production defaults to `System::currentTimeMillis`.
    private final LongSupplier wallClockMs;

    /// TTL (ms) after which a stale one-shot SWIM-suspect decays OUT of the quiesce health-hint
    /// (#68 — restores the parity the FSM migration dropped vs the legacy
    /// [`org.pragmatica.aether.deployment.generation.SwimHintsRegistry#currentTtlFiltered`]). The
    /// member STAYS in FSM SUSPECT and in [`#countedMembers`] — only the quiesce HINT decays.
    /// `Long.MAX_VALUE` (the default-factory value) means NEVER decay, byte-identical to the
    /// pre-#68 behaviour; AetherNode wires the real auto-heal SWIM-hints TTL.
    private final long suspectHintTtlMs;

    /// Confirmed-departure listener invoked ONCE per fresh edge into DEAD — at the SAME central
    /// chokepoint ([`MemberTracking#dispatch`]) as the presence-sampler eviction, for ALL three DEAD
    /// paths (co-confirmed death, graceful departure, join-grace expiry). Default no-op
    /// (production-inert): a later wave wires it to the transport executor's `departurePermanent` so the
    /// dead peer's QUIC link is dropped promptly on the death edge instead of waiting ~14s for SWIM to
    /// time the link out. Reset to the no-op by passing `null` to [`#onConfirmedDeparture`].
    private volatile Consumer<NodeId> onConfirmedDeparture = ignored -> {};

    private MembershipFsm(PresenceSampler presenceSampler,
                          FsmObserver<MembershipState, MembershipEvent> observer,
                          LongSupplier wallClockMs,
                          long suspectHintTtlMs) {
        this.presenceSampler = presenceSampler;
        this.observer = observer;
        this.wallClockMs = wallClockMs;
        this.suspectHintTtlMs = suspectHintTtlMs;
    }

    /// Factory with the default no-op transition observer, the system wall clock, and NO hint decay
    /// (TTL = `Long.MAX_VALUE`) — byte-identical to the pre-#68 behaviour for every existing
    /// caller/fixture.
    public static MembershipFsm membershipFsm(PresenceSampler presenceSampler) {
        return membershipFsm(presenceSampler, FsmObserver.noop());
    }

    /// Factory with an explicit transition observer (transition logging / metrics), the system wall
    /// clock, and NO hint decay (TTL = `Long.MAX_VALUE`).
    public static MembershipFsm membershipFsm(PresenceSampler presenceSampler, FsmObserver<MembershipState, MembershipEvent> observer) {
        return new MembershipFsm(presenceSampler, observer, System::currentTimeMillis, Long.MAX_VALUE);
    }

    /// Factory with an explicit SUSPECTED-hint decay TTL (ms) on the system wall clock and the
    /// default no-op observer. AetherNode uses this overload to wire the auto-heal SWIM-hints TTL so
    /// a stale one-shot SWIM-suspect on a still-present node decays out of the quiesce gate (#68).
    public static MembershipFsm membershipFsm(PresenceSampler presenceSampler, long suspectHintTtlMs) {
        return new MembershipFsm(presenceSampler, FsmObserver.noop(), System::currentTimeMillis, suspectHintTtlMs);
    }

    /// Full factory: explicit observer, injectable wall clock (ms), and SUSPECTED-hint decay TTL
    /// (ms). The clock injection lets tests advance time deterministically to exercise the #68 hint
    /// decay; a TTL of `Long.MAX_VALUE` disables decay.
    public static MembershipFsm membershipFsm(PresenceSampler presenceSampler,
                                              FsmObserver<MembershipState, MembershipEvent> observer,
                                              LongSupplier wallClockMs,
                                              long suspectHintTtlMs) {
        return new MembershipFsm(presenceSampler, observer, wallClockMs, suspectHintTtlMs);
    }

    /// Register the confirmed-departure listener invoked ONCE per fresh edge into DEAD — at the SAME
    /// central chokepoint as the presence-sampler eviction, for ALL three DEAD paths (co-confirmed
    /// death, graceful departure, join-grace expiry). A later wave wires this to the transport
    /// executor's `departurePermanent` so the dead peer's QUIC link is dropped promptly on the death
    /// edge instead of waiting ~14s for SWIM to time the link out. A `null` argument resets it to the
    /// no-op.
    @Contract
    public void onConfirmedDeparture(Consumer<NodeId> listener) {
        this.onConfirmedDeparture = listener == null ? ignored -> {} : listener;
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

    /// Transport reported a peer connection dropped for `id`. Drives MEMBER→SUSPECT, so it is also a
    /// fresh-doubt edge: stamp the doubt time so the resulting SUSPECT's quiesce hint ages out under
    /// the same TTL as the SWIM-driven doubts (#68 — no path into SUSPECT leaves an unstamped member).
    @Contract
    public void onPeerDisconnected(NodeId id) {
        withMember(id, this::peerDisconnected);
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

    /// The presence sampler down-hysteresis threshold was crossed for `id` (sustained absence over the
    /// down-hysteresis window). Routes that crossing INTO this FSM so a sustained-absence SUSPECT
    /// member is bounded by the FSM (SUSPECT→DEPARTING per spec §3.3 / invariant I4), rather than presence sampler
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

    /// FSM-derived health-hint projection for the cluster-quiescence gate
    /// ([`org.pragmatica.aether.deployment.generation.ClusterGenerationProjector#deriveClusterQuiescence`]).
    /// Mirrors the semantics of the SWIM-hints map it replaces: only a downgrade is carried, a
    /// HEALTHY member is OMITTED (the projector's `deriveHealthHint` defaults an absent entry to
    /// HEALTHY). DEAD → [`HealthHint#FAULTY`], SUSPECT → [`HealthHint#SUSPECTED`] ONLY while the last
    /// doubt is within [`#suspectHintTtlMs`] — a stale one-shot SWIM-suspect decays to healthy after
    /// the TTL (#68 parity with the legacy `SwimHintsRegistry#currentTtlFiltered`); every other state
    /// (OBSERVED / MEMBER / DEPARTING) is healthy-by-construction and contributes no entry.
    /// Insertion-ordered ([`LinkedHashMap`]) for stable iteration, matching [`#memberStates`].
    public Map<NodeId, HealthHint> healthHints() {
        var snapshot = new LinkedHashMap<NodeId, HealthHint>();
        var nowMs = wallClockMs.getAsLong();

        members.forEach((id, tracking) -> tracking.healthHint(nowMs, suspectHintTtlMs).onPresent(hint -> snapshot.put(id, hint)));
        return snapshot;
    }

    /// The stored last-wins network descriptor (address + role + source) for `id`, or `none()` if the
    /// id is untracked. Works in ANY lifecycle state INCLUDING DEAD — DEAD members are retained in the
    /// map, and a dead node's `source` is needed to provision its same-source replacement, so this
    /// reads from the retained [`MemberTracking`] (not from [`#countedMembers`]).
    public Option<MemberDescriptor> memberDescriptor(NodeId id) {
        return Option.option(members.get(id)).map(MemberTracking::descriptor);
    }

    /// Insertion-ordered snapshot of every tracked member's descriptor (for status / observability
    /// surfaces). Matches the snapshot style of [`#memberStates`]; includes DEAD members.
    public Map<NodeId, MemberDescriptor> memberDescriptors() {
        var snapshot = new LinkedHashMap<NodeId, MemberDescriptor>();

        members.forEach((id, tracking) -> snapshot.put(id, tracking.descriptor()));
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

    /// The consensus-broadcast target set: every tracked member whose current state is NOT
    /// terminally DEAD (i.e. OBSERVED + MEMBER + SUSPECT + DEPARTING). NO worker/role filter —
    /// broadcast carries more than consensus, so role-aware targeting is a later concern (#241);
    /// this projection only strips the storm's lingering zombie.
    ///
    /// Distinct from [`#coreMembers`] (MEMBER + SUSPECT, role-filtered): consensus must keep
    /// reaching joining/suspected peers — a replacement catching up is OBSERVED, not yet MEMBER,
    /// and excluding it would delay the #68 auto-heal recovery path. Only a co-confirmed-DEAD
    /// peer — the storm's lingering zombie still cached as a CONNECTED transport link — is
    /// excluded. Insertion-ordered ([`LinkedHashSet`]) for stable iteration, matching
    /// [`#coreMembers`]/[`#memberStates`].
    public Set<NodeId> broadcastEligibleMembers() {
        // HashSet, not LinkedHashSet: the result is consumed only for membership `contains()` on
        // the consensus broadcast hot path, so insertion ordering is unused — skip the linked overhead.
        return members.entrySet()
                      .stream()
                      .filter(entry -> entry.getValue().notDead())
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toCollection(HashSet::new));
    }

    /// The desired dial-set for the transport executor: [`#coreMembers`] intersected with members whose
    /// address is known, each mapped to a [`PeerTarget`] `(id, address)`. A member whose descriptor has
    /// not yet supplied an address is skipped — it reappears once a NodeInfo observation lands.
    ///
    /// The is-core decision AND the address read are captured ATOMICALLY under a single per-member
    /// monitor acquisition via [`MemberTracking#coreDialTarget`]: a concurrent tap thread can no longer
    /// interleave between the is-core filter and the address map and pair a stale is-core decision with a
    /// newer/inconsistent address. Insertion ordering is preserved ([`LinkedHashSet`]).
    public Set<PeerTarget> desiredConnections() {
        return members.entrySet()
                      .stream()
                      .flatMap(entry -> entry.getValue().coreDialTarget(entry.getKey()).stream())
                      .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// Filter `candidates` to the best-effort SERVING set: every candidate that is NOT terminally
    /// DEAD per [`#broadcastEligibleMembers`] (NOT-DEAD = OBSERVED + MEMBER + SUSPECT + DEPARTING).
    /// This answers "can this node hand a request to that target right now", which is DISTINCT from
    /// [`#countedMembers`] (the membership/quorum count, MEMBER + SUSPECT). An OBSERVED joining
    /// replacement or a DEPARTING drainer is still UP and may serve a forward/DHT op, and the
    /// best-effort callers retry on failure (forward-router) / carry a per-op timeout (DHT), so only
    /// a genuinely co-confirmed-DEAD target is excluded. Preserves the caller's candidate order.
    public List<NodeId> reachableMembers(List<NodeId> candidates) {
        var notDead = broadcastEligibleMembers();

        return candidates.stream()
                         .filter(notDead::contains)
                         .toList();
    }

    // --- Per-member transition drivers (presence sampler promotion + co-confirmation eviction) ---

    private void healthy(MemberTracking tracking, long incarnation) {
        tracking.dispatch(new SwimHealthy(incarnation));
        tracking.clearConfirmedDeath();
        if (tracking.bumpHealthyStreakReachedThreshold()) {
            tracking.dispatch(new UpHysteresisMet());
        }
    }

    private void suspect(MemberTracking tracking, long incarnation) {
        tracking.resetHealthyStreak();
        tracking.stampDoubt(wallClockMs.getAsLong());
        tracking.dispatch(new SwimSuspect(incarnation));
    }

    private void faulty(MemberTracking tracking, long incarnation) {
        tracking.resetHealthyStreak();
        tracking.stampDoubt(wallClockMs.getAsLong());
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
        tracking.stampDoubt(wallClockMs.getAsLong());
        tracking.dispatch(new LivenessGone());
        tracking.markLivenessGone();
        maybeConfirmDeparture(tracking);
    }

    /// Transport-disconnect doubt path (MEMBER→SUSPECT on [`PeerDisconnected`]): stamp the doubt
    /// time so the SUSPECT's quiesce hint decays under the #68 TTL, then dispatch. A no-op in any
    /// state with no MEMBER→SUSPECT edge — the stamp is harmless there (the hint stays `none()`).
    private void peerDisconnected(MemberTracking tracking) {
        tracking.stampDoubt(wallClockMs.getAsLong());
        tracking.dispatch(new PeerDisconnected());
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
    /// [`PresenceSampler#evict`] is itself idempotent. Mutating presence sampler's presence view is the sole side
    /// effect; the presence-derived TopologyObserver path emits the resulting NODE_FAILED / NODE_LEFT
    /// event. Alongside the eviction it notifies the [`#onConfirmedDeparture`] listener (default no-op)
    /// at this same single chokepoint, so the transport executor can drop the dead peer's link promptly.
    @Contract
    private void onEnteredDead(NodeId id) {
        log.info("MembershipFsm evicting co-confirmed-dead member {} from presence sampler", id);
        presenceSampler.evict(id);
        onConfirmedDeparture.accept(id);
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
        private long lastDoubtAtMs = 0L;
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

        private synchronized boolean isDead() {
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

        /// Stamp the wall-clock time (ms) of the latest fresh doubt that drives / keeps this member in
        /// SUSPECT. Every fresh doubt re-stamps (matching the legacy `observedAt` semantics), so the
        /// quiesce SUSPECTED hint ages out only after `suspectHintTtlMs` of NO new doubt.
        synchronized void stampDoubt(long nowMs) {
            lastDoubtAtMs = nowMs;
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

        /// The stored last-wins descriptor (address + role + source). Retained across DEAD so a dead
        /// node's `source` stays queryable for same-source replacement provisioning.
        synchronized MemberDescriptor descriptor() {
            return descriptor;
        }

        synchronized boolean countsTowardEffective() {
            return fsm.current().countsTowardEffective();
        }

        /// Whether this member is still in the lifecycle (NOT terminally DEAD): true for
        /// OBSERVED / MEMBER / SUSPECT / DEPARTING, false only for DEAD. Used by
        /// [`#broadcastEligibleMembers`] — consensus must keep reaching joining/suspected
        /// peers, only a co-confirmed-DEAD zombie is excluded.
        synchronized boolean notDead() {
            return !isDead();
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
            return address().map(addr -> PeerTarget.peerTarget(memberId, addr));
        }

        /// Atomic dial-target read for [`#desiredConnections`]: under ONE monitor acquisition, returns
        /// `Some(PeerTarget(id, addr))` iff this member currently counts toward effective (MEMBER +
        /// SUSPECT) AND its descriptor role is core (non-explicit-worker) AND its descriptor has a known
        /// address; `none()` otherwise. Folding the is-core decision and the address read into a single
        /// `synchronized` method closes the window where a concurrent tap thread could mutate the
        /// descriptor / FSM state between two separate acquisitions and pair a stale is-core decision with
        /// a newer/inconsistent address.
        synchronized Option<PeerTarget> coreDialTarget(NodeId memberId) {
            return isCoreCountedMember()
                   ? descriptor.address().map(addr -> PeerTarget.peerTarget(memberId, addr))
                   : Option.none();
        }

        synchronized String stateName() {
            return fsm.current().getClass().getSimpleName();
        }

        /// FSM-state → quiescence health-hint projection. DEAD → FAULTY (unconditional); SUSPECT →
        /// SUSPECTED ONLY while the last doubt is within `ttlMs` (`nowMs - lastDoubtAtMs <= ttlMs`),
        /// else `none()` — the stale one-shot doubt has decayed to healthy (#68 parity with the
        /// legacy `SwimHintsRegistry#currentTtlFiltered`; the member STAYS in FSM SUSPECT and in
        /// `countedMembers`, only this quiesce HINT decays). Every other state is
        /// healthy-by-construction and yields `none()` (the projector defaults an absent entry to
        /// HEALTHY). Pure read of the current FSM state under the per-member monitor.
        synchronized Option<HealthHint> healthHint(long nowMs, long ttlMs) {
            return switch (fsm.current()) {
                case MembershipState.Dead _ -> Option.some(HealthHint.FAULTY);
                case MembershipState.Suspect _ -> suspectHint(nowMs, ttlMs);
                case MembershipState.Observed _, MembershipState.Member _, MembershipState.Departing _ -> Option.none();
            };
        }

        /// SUSPECTED iff the last doubt is still fresh (within `ttlMs`); otherwise the doubt has aged
        /// out and the member projects healthy (omitted hint).
        private Option<HealthHint> suspectHint(long nowMs, long ttlMs) {
            return (nowMs - lastDoubtAtMs) <= ttlMs
                   ? Option.some(HealthHint.SUSPECTED)
                   : Option.none();
        }
    }
}
