// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.DownHysteresisMet;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.DrainRequested;
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
import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Per-member membership FSM manager (membership v2, **Phase 2 LIVE** — the authoritative
/// membership-death decision-maker). It is no longer a passive shadow: it drives one
/// [`MembershipState`] FSM per [`NodeId`] from tapped SWIM / transport / liveness edges. Wave 7
/// (cluster-topology-overhaul spec): the FSM is the SOLE membership authority — the legacy
/// FSM→`PresenceSampler` eviction call is GONE (the sampler is a pure debounce sensor that only
/// feeds `Up/DownHysteresisMet` edges INTO this FSM; nothing flows back out). Wave 4: the FSM
/// emits a typed [`MembershipDeltaEdge`] on its own counted-set lifecycle
/// edges (first OBSERVED→MEMBER promotion = JOINED; fresh edge into DEAD of a previously-JOINED
/// member = REMOVED), consumed by the pure [`MembershipDeltaProjector`] projection — the sole
/// emitter of `MembershipDecision`. The `ClusterEventAggregator` `NODE_FAILED` / `NODE_LEFT`
/// events derive from that decision stream; this FSM's death edge is a pure notification whose
/// only fan-out is the [`#onConfirmedDeparture`] listener.
///
/// **Always-on per-node, consensus-independent, SWIM/liveness-driven.** The FSM is armed from
/// construction on EVERY node (not only the leader): each node drives its OWN per-member FSMs from its
/// tapped SWIM gossip + composite liveness, independent of consensus health — so a dead member is
/// removed even when the death decision must not
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
/// **DEAD edge fan-out.** Entry into DEAD is detected CENTRALLY in [`MemberTracking#dispatch`]:
/// after every dispatch it compares the FSM's pre/post state and, on a fresh edge INTO `Dead` (was
/// not Dead before, is Dead after), invokes the manager's death hook. This covers ALL DEAD
/// paths uniformly (co-confirmed death, graceful departure, join-grace expiry, DEPARTING timeout)
/// without scattering the call across the ingress methods. The hook is idempotent — the fresh-edge
/// guard fires once per death. Wave 7: the legacy `presenceSampler.evict` side effect is REMOVED;
/// the hook now only notifies the [`#onConfirmedDeparture`] listener.
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

    /// M4 not-yet-wired sentinel (cluster-topology-overhaul Wave 9 item 5). The CDM
    /// `coreCountedMembers` supplier returns THIS identity-distinguished set during the boot
    /// window between CDM construction and `MembershipFsm` wiring (the holder is null). It is
    /// distinct (by reference identity) from a genuinely-empty core set, so a stale-entry
    /// cleanup that runs in that window can no-op instead of mass-classifying every
    /// KV-known member as departed (the M4 mass-cleanup hazard). Consumers test it with
    /// `set == MembershipFsm.MEMBERSHIP_NOT_WIRED` (reference identity), never `isEmpty()`.
    public static final Set<NodeId> MEMBERSHIP_NOT_WIRED = Collections.unmodifiableSet(new HashSet<>());

    /// Default terminal-eviction backstop window (#131 Model C) for the legacy factory overloads that
    /// predate the configured value: the membership-config `splitTimeout` (default 15s) — the SAME
    /// value the minority's quorum-loss self-drain uses, kept as a single source of truth. AetherNode
    /// wires the live `MembershipConfig.splitTimeout()` through the dedicated overload.
    private static final TimeSpan DEFAULT_EVICTION_BACKSTOP = MembershipConfig.membershipConfig().splitTimeout();

    /// Default DEPARTING timeout window (H2, cluster-topology-overhaul Wave 7): a member that
    /// entered DEPARTING (drain / down-hysteresis / graceful-leave intent) and then went SILENT
    /// still terminalizes to DEAD after this window. Sourced from
    /// `MembershipConfig.splitTimeout` (default 15s) — THE membership departure-debounce constant
    /// (the same one presence sampler derives its down-hysteresis from), deliberately reused
    /// instead of introducing a new knob: "silent in DEPARTING for as long as a sustained absence
    /// needs to be confirmed" is the same epistemic budget. AetherNode wires the live configured
    /// value through the dedicated overload.
    private static final TimeSpan DEFAULT_DEPARTURE_TIMEOUT = MembershipConfig.membershipConfig().splitTimeout();

    /// Default join-grace window (M10, cluster-topology-overhaul Wave 7): a tracked member that
    /// NEVER reaches MEMBER within this window of its first observation is reaped OBSERVED→DEAD
    /// via [`JoinGraceExpiredNeverHealthy`] (never silently counted, never a permanent ghost in
    /// `broadcastEligibleMembers`). Sourced from `MembershipConfig.splitTimeout` (default 15s) —
    /// the single membership timing constant, comfortably above SWIM's own ~12s join grace so
    /// SWIM gets first say. Boot-seeded configured members are promoted to MEMBER immediately
    /// (the seed), so the reaper only ever fires for a discovered joiner that never went healthy.
    private static final TimeSpan DEFAULT_JOIN_GRACE = MembershipConfig.membershipConfig().splitTimeout();

    private final FsmObserver<MembershipState, MembershipEvent> observer;
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

    /// Confirmed-departure listener invoked ONCE per fresh edge into DEAD — at the central
    /// chokepoint ([`MemberTracking#dispatch`]), for ALL DEAD
    /// paths (co-confirmed death, graceful departure, join-grace expiry, DEPARTING timeout). Default no-op
    /// (production-inert): AetherNode wires it to the transport executor's `departurePermanent` so the
    /// dead peer's QUIC link is dropped promptly on the death edge instead of waiting ~14s for SWIM to
    /// time the link out, and chains its heal/quorum nudge (Wave 7 — the prompt nudge the deleted
    /// sampler eviction used to provide). Reset to the no-op by passing `null` to [`#onConfirmedDeparture`].
    private volatile Consumer<NodeId> onConfirmedDeparture = ignored -> {};

    /// Wave-1 transition journal feed (cluster-topology-overhaul spec, Enrichment A): invoked
    /// with one [MembershipTransitionRecord] per ACTUAL per-member state change (dispatches that
    /// leave the state unchanged emit nothing), from the SAME central chokepoint
    /// ([`MemberTracking#dispatch`]) that detects the DEAD edge. Diagnostic-only — the default
    /// no-op keeps journaling opt-in at wiring time and emission has no control-flow effect.
    /// Reset to the no-op by passing `null` to [#onTransition].
    private volatile Consumer<MembershipTransitionRecord> onTransition = ignored -> {};

    /// Wave-4 membership-delta listener (cluster-topology-overhaul spec): invoked with one
    /// [`MembershipDeltaEdge`] per counted-set lifecycle edge — JOINED on the member's FIRST
    /// entry into MEMBER, REMOVED on a fresh edge into DEAD of a previously-JOINED member —
    /// from the SAME central chokepoint ([`MemberTracking#dispatch`]) as the journal feed and
    /// the confirmed-departure hook. Fired under the per-member monitor with a cheap payload;
    /// implementations must be cheap and non-blocking (the production
    /// [`MembershipDeltaProjector`] enqueues and returns). Default no-op; reset to the no-op
    /// by passing `null` to [#onMembershipDelta]. Per spec §3.1 (hard constraint) this edge is
    /// fully decoupled from `TopologyObserver.evaluateQuorumState` — nothing on it may route
    /// into the quorum evaluator.
    private volatile Consumer<MembershipDeltaEdge> onMembershipDelta = ignored -> {};

    /// Terminal-eviction backstop window (#131 Model C). When BOTH death planes confirm
    /// (`swimFaulty ∧ livenessGone`) the member is NO LONGER marched straight to DEAD; instead a
    /// per-member backstop timer is armed for this window and the member stays SUSPECT (counted,
    /// recoverable). Terminal DEAD is reached only when this backstop fires OR via the existing
    /// confirmed-departure paths (graceful `SwimDeparted`, join-grace expiry). A network partition
    /// shorter than this window heals while the node is SUSPECT (a `SwimHealthy` recovery edge cancels
    /// the backstop via `clearConfirmedDeath`), so the node rejoins via SUSPECT→MEMBER and is never
    /// fenced. Sourced from `MembershipConfig.splitTimeout` — the SAME value the minority's
    /// quorum-loss self-drain uses (single source of truth); defaults to 15s for the legacy factories.
    private final TimeSpan evictionBackstop;

    /// DEPARTING timeout window (H2, Wave 7) — see [`#DEFAULT_DEPARTURE_TIMEOUT`]. Armed centrally
    /// on every fresh edge INTO `Departing`, cancelled on every edge OUT of it; a firing timer
    /// re-checks the state under the per-member monitor before terminalizing (the
    /// `cancel(false)`-cannot-stop-a-running-task race, same discipline as the eviction backstop).
    private final TimeSpan departureTimeout;

    /// Join-grace window (M10, Wave 7) — see [`#DEFAULT_JOIN_GRACE`]. Armed on tracking creation
    /// and re-armed on a fenced rejoin (DEAD→OBSERVED); cancelled on promotion to MEMBER and on
    /// death. A firing timer dispatches [`JoinGraceExpiredNeverHealthy`], which the state table
    /// makes a no-op in every state but OBSERVED — the reaper can never kill a promoted member.
    /// Gate RCA fix (2026-06-11): the reap is additionally TRANSPORT-VETOED — a never-healthy
    /// member whose transport connection is live is deferred (timer re-armed for the same
    /// window), never reaped on FSM state alone. See [`MemberTracking#expireJoinGrace`].
    private final TimeSpan joinGrace;

    private MembershipFsm(FsmObserver<MembershipState, MembershipEvent> observer,
                          LongSupplier wallClockMs,
                          long suspectHintTtlMs,
                          TimeSpan evictionBackstop,
                          TimeSpan departureTimeout,
                          TimeSpan joinGrace) {
        this.observer = observer;
        this.wallClockMs = wallClockMs;
        this.suspectHintTtlMs = suspectHintTtlMs;
        this.evictionBackstop = evictionBackstop;
        this.departureTimeout = departureTimeout;
        this.joinGrace = joinGrace;
    }

    /// Factory with the default no-op transition observer, the system wall clock, and NO hint decay
    /// (TTL = `Long.MAX_VALUE`) — byte-identical to the pre-#68 behaviour for every existing
    /// caller/fixture.
    public static MembershipFsm membershipFsm() {
        return membershipFsm(FsmObserver.noop());
    }

    /// Factory with an explicit transition observer (transition logging / metrics), the system wall
    /// clock, and NO hint decay (TTL = `Long.MAX_VALUE`).
    public static MembershipFsm membershipFsm(FsmObserver<MembershipState, MembershipEvent> observer) {
        return new MembershipFsm(observer,
                                 System::currentTimeMillis,
                                 Long.MAX_VALUE,
                                 DEFAULT_EVICTION_BACKSTOP,
                                 DEFAULT_DEPARTURE_TIMEOUT,
                                 DEFAULT_JOIN_GRACE);
    }

    /// Factory with an explicit SUSPECTED-hint decay TTL (ms) on the system wall clock and the
    /// default no-op observer. Wires the auto-heal SWIM-hints TTL so
    /// a stale one-shot SWIM-suspect on a still-present node decays out of the quiesce gate (#68).
    public static MembershipFsm membershipFsm(long suspectHintTtlMs) {
        return new MembershipFsm(FsmObserver.noop(),
                                 System::currentTimeMillis,
                                 suspectHintTtlMs,
                                 DEFAULT_EVICTION_BACKSTOP,
                                 DEFAULT_DEPARTURE_TIMEOUT,
                                 DEFAULT_JOIN_GRACE);
    }

    /// Production factory (AetherNode): explicit SUSPECTED-hint decay TTL (ms), the terminal-eviction
    /// backstop window (#131 Model C, from `MembershipConfig.splitTimeout`), and the
    /// Wave-7 DEPARTING-timeout / join-grace windows (both from `MembershipConfig.splitTimeout`
    /// — the single membership timing constant, see [`#DEFAULT_DEPARTURE_TIMEOUT`] /
    /// [`#DEFAULT_JOIN_GRACE`]). System wall clock, no-op observer.
    public static MembershipFsm membershipFsm(long suspectHintTtlMs,
                                              TimeSpan evictionBackstop,
                                              TimeSpan departureTimeout,
                                              TimeSpan joinGrace) {
        return new MembershipFsm(FsmObserver.noop(),
                                 System::currentTimeMillis,
                                 suspectHintTtlMs,
                                 evictionBackstop,
                                 departureTimeout,
                                 joinGrace);
    }

    /// Full factory: explicit observer, injectable wall clock (ms), and SUSPECTED-hint decay TTL
    /// (ms). The clock injection lets tests advance time deterministically to exercise the #68 hint
    /// decay; a TTL of `Long.MAX_VALUE` disables decay. The terminal-eviction backstop (#131) defaults
    /// to `splitTimeout` (default 15s) — use [`#membershipFsm(FsmObserver,LongSupplier,long,TimeSpan)`]
    /// to override it.
    public static MembershipFsm membershipFsm(FsmObserver<MembershipState, MembershipEvent> observer,
                                              LongSupplier wallClockMs,
                                              long suspectHintTtlMs) {
        return new MembershipFsm(observer,
                                 wallClockMs,
                                 suspectHintTtlMs,
                                 DEFAULT_EVICTION_BACKSTOP,
                                 DEFAULT_DEPARTURE_TIMEOUT,
                                 DEFAULT_JOIN_GRACE);
    }

    /// Full factory with an explicit terminal-eviction backstop window (#131 Model C): explicit
    /// observer, injectable wall clock (ms), SUSPECTED-hint decay TTL (ms), and the co-confirmed-death
    /// backstop window. Lets tests drive the backstop deterministically alongside the injected clock.
    public static MembershipFsm membershipFsm(FsmObserver<MembershipState, MembershipEvent> observer,
                                              LongSupplier wallClockMs,
                                              long suspectHintTtlMs,
                                              TimeSpan evictionBackstop) {
        return new MembershipFsm(observer,
                                 wallClockMs,
                                 suspectHintTtlMs,
                                 evictionBackstop,
                                 DEFAULT_DEPARTURE_TIMEOUT,
                                 DEFAULT_JOIN_GRACE);
    }

    /// Deepest factory (Wave 7): everything explicit, including the DEPARTING-timeout and
    /// join-grace windows — lets the deterministic FSM-simulation tests drive the H2/M10 timers
    /// with near-zero windows.
    public static MembershipFsm membershipFsm(FsmObserver<MembershipState, MembershipEvent> observer,
                                              LongSupplier wallClockMs,
                                              long suspectHintTtlMs,
                                              TimeSpan evictionBackstop,
                                              TimeSpan departureTimeout,
                                              TimeSpan joinGrace) {
        return new MembershipFsm(observer, wallClockMs, suspectHintTtlMs, evictionBackstop, departureTimeout, joinGrace);
    }

    /// Register the confirmed-departure listener invoked ONCE per fresh edge into DEAD — at the
    /// central dispatch chokepoint, for ALL DEAD paths (co-confirmed
    /// death, graceful departure, join-grace expiry, DEPARTING timeout). AetherNode wires this to the transport
    /// executor's `departurePermanent` (plus the Wave-7 heal/quorum nudge) so the dead peer's QUIC link is dropped promptly on the death
    /// edge instead of waiting ~14s for SWIM to time the link out. A `null` argument resets it to the
    /// no-op.
    @Contract
    public void onConfirmedDeparture(Consumer<NodeId> listener) {
        this.onConfirmedDeparture = listener == null
                                    ? ignored -> {}
                                    : listener;
    }

    /// Register the Wave-1 transition-journal listener invoked once per ACTUAL per-member state
    /// change, at the central dispatch chokepoint. The listener is called under the per-member
    /// monitor — implementations must be cheap and non-blocking (the journal ring-buffer append
    /// is). Diagnostic-only; a `null` argument resets it to the no-op.
    @Contract
    public void onTransition(Consumer<MembershipTransitionRecord> listener) {
        this.onTransition = listener == null
                            ? ignored -> {}
                            : listener;
    }

    /// Forwarding sink handed to each [`MemberTracking`] — reads the volatile listener at EACH
    /// invocation, so wiring installed after a member's tracking was created still observes its
    /// transitions.
    @Contract
    private void emitTransition(MembershipTransitionRecord record) {
        onTransition.accept(record);
    }

    /// Register the Wave-4 membership-delta listener invoked once per counted-set lifecycle
    /// edge (JOINED / REMOVED, see [`MembershipDeltaEdge`]) at the central dispatch chokepoint.
    /// The listener is called under the per-member monitor — implementations must be cheap and
    /// non-blocking (the production [`MembershipDeltaProjector`] enqueues into its FIFO and
    /// returns). A `null` argument resets it to the no-op.
    @Contract
    public void onMembershipDelta(Consumer<MembershipDeltaEdge> listener) {
        this.onMembershipDelta = listener == null
                                 ? ignored -> {}
                                 : listener;
    }

    /// Forwarding sink handed to each [`MemberTracking`] — reads the volatile listener at EACH
    /// invocation, so wiring installed after a member's tracking was created still observes its
    /// delta edges (the AetherNode projector wiring is installed before the boot seed, but a
    /// tap-created tracking may predate it).
    @Contract
    private void emitMembershipDelta(MembershipDeltaEdge edge) {
        onMembershipDelta.accept(edge);
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
    /// Upsert the network descriptor (address + role + source) for `info.id()` from a
    /// NodeInfo-bearing SWIM observation (JoinAnnounced / MemberDiscovered). Leader-gate-free and
    /// orthogonal to the lifecycle FSM: it lazily creates the member's tracking via [`#trackingFor`]
    /// (leaving its state in OBSERVED) and overwrites only the descriptor, so the address/role/source
    /// become known the moment the first NodeInfo lands. Field-level updates are guarded against
    /// blank-downgrade ([`MemberTracking#updateDescriptor`]): an information-less observation never
    /// erases a known address / role / source, while a non-blank incoming value still replaces it.
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
    ///
    /// **Doc-truth (cluster-topology-overhaul §6.3):** `onLivenessGone` is the composite-liveness
    /// signal CO-CONFIRMED with SWIM-FAULTY before DEAD — it is NOT a bare QUIC-disconnect death.
    /// Transport never promotes to ALIVE (death-ward A1); it may only bias toward death, and a
    /// transport-only blip without SWIM-FAULTY co-confirmation never terminalizes a member here.
    @Contract
    public void onLivenessGone(NodeId id) {
        withMember(id, this::livenessGone);
    }

    /// The join-grace window expired for `id` without it ever reaching healthy. Drives OBSERVED→DEAD
    /// (never silently counted); a no-op once the member has progressed past OBSERVED. Production
    /// caller (M10, Wave 7): the per-member join-grace timer armed on tracking creation; this public
    /// ingress is retained for tests and external grace sources.
    @Contract
    public void onJoinGraceExpired(NodeId id) {
        withMember(id, tracking -> tracking.dispatch(new JoinGraceExpiredNeverHealthy()));
    }

    /// Operator / controller requested `id` drain gracefully (M10, cluster-topology-overhaul
    /// Wave 7 — the drain command is routed THROUGH the FSM instead of bypassing it). Drives
    /// OBSERVED/MEMBER/SUSPECT → DEPARTING: the target stops counting toward effective and the
    /// DEPARTING timeout (H2) terminalizes it if it goes silent; a `SwimHealthy` at a strictly
    /// higher incarnation recovers it mid-drain. Ignored in DEPARTING/DEAD.
    @Contract
    public void onDrainRequested(NodeId id) {
        withMember(id, tracking -> tracking.dispatch(new DrainRequested()));
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
    ///
    /// ROLE-BLIND (includes workers). Quorum / heal-deficit / role-assignment consumers must NOT
    /// count this set — they read the role-scoped [`#coreCountedMembers`] instead
    /// (cluster-topology-overhaul spec, Wave 2 / invariant A8: one core denominator).
    public Set<NodeId> countedMembers() {
        return members.entrySet()
                      .stream()
                      .filter(entry -> entry.getValue()
                                            .countsTowardEffective())
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// Provisioning deficit: `max(0, configuredCoreCount - effective())`. ROLE-BLIND aggregate —
    /// no production heal consumer reads it (the `LeaderReconciler` deficit reads
    /// [`#coreCountedMembers`], Wave 2 / W2); retained for FSM-level observability and tests.
    public int wouldProvision(int configuredCoreCount) {
        return Math.max(0, configuredCoreCount - effective());
    }

    /// Drain surplus: `max(0, effective() - configuredCoreCount)`. ROLE-BLIND aggregate — same
    /// status as [`#wouldProvision`] (observability/tests only).
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
    /// ([`org.pragmatica.aether.deployment.generation.ClusterQuiescenceEvaluator#evaluateCluster`]).
    /// Mirrors the semantics of the SWIM-hints map it replaces: only a downgrade is carried, a
    /// HEALTHY member is OMITTED (the evaluator defaults an absent entry to
    /// HEALTHY). SUSPECT → [`HealthHint#SUSPECTED`] ONLY while the last doubt is within
    /// [`#suspectHintTtlMs`] — a stale one-shot SWIM-suspect decays to healthy after the TTL (#68
    /// parity with the legacy `SwimHintsRegistry#currentTtlFiltered`); every other live state
    /// (OBSERVED / MEMBER / DEPARTING) is healthy-by-construction and contributes no entry.
    ///
    /// Terminally-DEAD members are EXCLUDED from this projection (same [`MemberTracking#notDead`]
    /// predicate as [`#countedMembers`] / [`#broadcastEligibleMembers`]): a DEAD member is retained
    /// in the map only for incarnation-fenced rejoin, is already OUT of the membership count, and
    /// its unconditional DEAD → FAULTY per-member hint would otherwise pin cluster quiescence at
    /// DEGRADED forever once chaos-killed ghosts accumulate (#68). The per-member DEAD → FAULTY
    /// projection itself ([`MemberTracking#healthHint`]) is unchanged — only this iteration skips
    /// DEAD members. Insertion-ordered ([`LinkedHashMap`]) for stable iteration, matching
    /// [`#memberStates`].
    public Map<NodeId, HealthHint> healthHints() {
        var snapshot = new LinkedHashMap<NodeId, HealthHint>();
        var nowMs = wallClockMs.getAsLong();

        members.forEach((id, tracking) -> projectLiveHint(snapshot, id, tracking, nowMs));

        return snapshot;
    }

    /// Add `tracking`'s downgrade hint to `snapshot` iff it is still live (NOT terminally DEAD) and
    /// projects a present hint. A terminally-DEAD member contributes nothing — its retained
    /// (incarnation-fenced) tombstone must not poison cluster quiescence (#68). Side-effecting
    /// accumulator step for [`#healthHints`].
    @Contract
    private void projectLiveHint(Map<NodeId, HealthHint> snapshot, NodeId id, MemberTracking tracking, long nowMs) {
        tracking.liveHealthHint(nowMs, suspectHintTtlMs).onPresent(hint -> snapshot.put(id, hint));
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

    /// Wave-1 diagnostic snapshot (cluster-topology-overhaul spec, item 6): each tracked
    /// member's SWIM-incarnation high-water mark ([`MembershipContext#lastSeenIncarnation`]).
    /// Insertion-ordered, matching [`#memberStates`] / [`#memberDescriptors`]; includes DEAD
    /// members (their terminal incarnation stays queryable). Pure read.
    public Map<NodeId, Long> memberIncarnations() {
        var snapshot = new LinkedHashMap<NodeId, Long>();

        members.forEach((id, tracking) -> snapshot.put(id, tracking.incarnation()));

        return snapshot;
    }

    /// Age (ms) of `id`'s membership tracking: wall-clock time since this manager FIRST began
    /// tracking the member (its [`MemberTracking`] creation on first observation), on the
    /// manager's injected wall clock — `none()` for an untracked id. The creation stamp is
    /// retained across DEAD/rejoin (the tracking is never recreated), which is deliberate: the
    /// descriptor is retained too, so a rejoining member's role is already known and the
    /// role-propagation race this age guards against does not re-open. Consumer: the
    /// `LeaderReconciler` drain-safety grace (cluster-topology-overhaul Wave 2) — a surplus-drain
    /// victim younger than the grace window is never selected, closing the window where a
    /// just-joined worker whose role labels are still propagating reads as a blank-role core
    /// surplus and is drained.
    public Option<Long> memberAgeMs(NodeId id) {
        return Option.option(members.get(id))
                     .map(tracking -> wallClockMs.getAsLong() - tracking.firstTrackedAtMs());
    }

    // --- Projections (desired connection-set for the transport executor) ---
    /// The core membership set the transport executor should keep mesh-connected: counted members
    /// (MEMBER + SUSPECT) that are NOT explicitly role=worker. An unknown / absent role counts as
    /// included, so an all-core cluster with no role labels yields every counted member. Insertion-
    /// ordered ([`LinkedHashSet`]) for stable iteration.
    public Set<NodeId> coreMembers() {
        return members.entrySet()
                      .stream()
                      .filter(entry -> entry.getValue()
                                            .isCoreCountedMember())
                      .map(Map.Entry::getKey)
                      .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /// The role-scoped COUNTING projection (cluster-topology-overhaul spec, Wave 2 / invariant
    /// A8 — one core denominator): counted members (MEMBER + SUSPECT) whose descriptor role is
    /// not the explicit literal `worker`. An unknown / absent role counts as core (conservative,
    /// matching [`#coreMembers`]'s documented rule), so an all-core cluster with no role labels
    /// yields every counted member.
    ///
    /// Built on — and today identical to — [`#coreMembers`], but deliberately a SEPARATE name:
    /// [`#coreMembers`] is the transport dial-set projection (what the executor keeps
    /// mesh-connected), this is the counting denominator for quorum / heal-deficit /
    /// role-assignment consumers. If the dial-set ever diverges (e.g. worker dial topology,
    /// #241), counting consumers stay pinned to this projection. Insertion-ordered.
    public Set<NodeId> coreCountedMembers() {
        return coreMembers();
    }

    /// The strict quorum numerator for the `QuorumLossDetector` (membership-fsm-unification §6,
    /// adopted by cluster-topology-overhaul Wave 2 / W1): members whose current state is exactly
    /// MEMBER (SUSPECT excluded — strict) AND whose descriptor role is core (unknown / absent
    /// role counts as core, same conservative rule as [`#coreCountedMembers`]). The detector's
    /// `splitTimeout` window debounces a transient SUSPECT dip, so the strict
    /// numerator does not cause premature self-drain on a single flap.
    public int strictCoreMemberCount() {
        return (int) members.values()
                            .stream()
                            .filter(MemberTracking::isStrictCoreMember)
                            .count();
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
                      .filter(entry -> entry.getValue()
                                            .notDead())
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
                      .flatMap(entry -> entry.getValue()
                                             .coreDialTarget(entry.getKey())
                                             .stream())
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

    /// Co-confirmation gate (#131 Model C — DEFERRED terminal). When BOTH planes confirm death
    /// (SWIM-FAULTY ∧ liveness-gone) the member is NO LONGER marched straight to DEAD. Instead a
    /// per-member terminal-eviction backstop timer is armed for [`#evictionBackstop`]; the member stays
    /// SUSPECT (still counts toward effective, still recoverable) for the window. If a brief network
    /// partition heals within the window, a `SwimHealthy` recovery edge fires
    /// [`MemberTracking#clearConfirmedDeath`] which cancels the backstop and the node rejoins via
    /// SUSPECT→MEMBER — never fenced. Only if the backstop FIRES does the terminal march run, and even
    /// then only if the member is STILL co-confirmed dead at firing time
    /// ([`MemberTracking#evictIfStillConfirmedDead`], re-checked under the per-member monitor to close
    /// the `cancel(false)`-cannot-stop-a-running-task race). IDEMPOTENT: re-entered on every
    /// SwimFaulty/livenessGone while co-confirmed, but [`MemberTracking#armEvictionBackstop`] no-ops
    /// while a backstop is already armed.
    private void maybeConfirmDeparture(MemberTracking tracking) {
        if (tracking.coConfirmedDead()) {
            tracking.armEvictionBackstop(tracking::evictIfStillConfirmedDead, evictionBackstop);
        }
    }

    /// Death hook invoked CENTRALLY on every fresh edge into DEAD (detected in
    /// [`MemberTracking#dispatch`]). Idempotent — the fresh-edge guard fires once per death. Wave 7
    /// (cluster-topology-overhaul): the legacy `presenceSampler.evict` side effect is REMOVED — the
    /// FSM is the sole membership authority and nothing flows back into the debounce sensor. The
    /// hook notifies the [`#onConfirmedDeparture`] listener (default no-op) at this single
    /// chokepoint, so the transport executor drops the dead peer's link promptly and the node
    /// wiring can nudge heal/quorum consumers on the death edge.
    @Contract
    private void onEnteredDead(NodeId id) {
        log.info("MembershipFsm member {} reached terminal DEAD", id);
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
        var tracking = new MemberTracking(id,
                                          fsm,
                                          this::onEnteredDead,
                                          this::emitTransition,
                                          this::emitMembershipDelta,
                                          wallClockMs.getAsLong(),
                                          departureTimeout,
                                          joinGrace);

        // M10 (Wave 7): the join-grace reaper — armed on first observation; cancelled on promotion
        // to MEMBER / on death; re-armed on a fenced rejoin (all inside the dispatch chokepoint).
        tracking.armJoinGrace();

        return tracking;
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
        /// Wave-1 transition journal sink — receives one [`MembershipTransitionRecord`] per
        /// ACTUAL state change from [`#dispatch`]. Diagnostic-only (default no-op upstream).
        private final Consumer<MembershipTransitionRecord> transitionSink;
        /// Wave-4 membership-delta sink — receives one [`MembershipDeltaEdge`] per counted-set
        /// lifecycle edge from [`#dispatch`] (JOINED on first entry into MEMBER, REMOVED on a
        /// fresh DEAD edge of a previously-JOINED member). Cheap payload, fired under this
        /// monitor; the upstream listener must be non-blocking.
        private final Consumer<MembershipDeltaEdge> deltaSink;
        /// Wall-clock stamp (ms, the manager's injected clock) of this tracking's creation —
        /// the member's first observation. Immutable: retained across DEAD/rejoin (the tracking
        /// is never recreated), matching the descriptor-retention rationale documented on
        /// [`MembershipFsm#memberAgeMs`]. Final → safe to read without the per-member monitor.
        private final long firstTrackedAtMs;
        private int healthyStreak = 0;
        private boolean swimFaultySeen = false;
        private boolean livenessGoneSeen = false;
        /// Wave-4 exactly-once JOINED/REMOVED pairing (the spec's tangential consideration):
        /// set on the JOINED edge (first entry into MEMBER), cleared when the REMOVED edge is
        /// emitted on death. A member whose `everJoined` is false emits NO delta on death (it
        /// never counted — OBSERVED→DEAD via join-grace expiry / drain-before-promotion); a
        /// fenced rejoin (DEAD→OBSERVED→MEMBER) re-fires JOINED because the flag was cleared.
        /// Mutated only under this monitor, like the co-confirmation flags.
        private boolean everJoined = false;
        /// Transport-connectivity flag for the join-grace reaper gate (gate RCA fix,
        /// 2026-06-11): flipped by the [`PeerConnected`] / [`PeerDisconnected`] events already
        /// dispatched into this FSM (the flag is bookkeeping ONLY — the state table's handling
        /// of those events is untouched; in particular the Suspect-state PeerConnected ignore,
        /// the ratified death-ward, stays as-is). Consulted at reaper fire time: a
        /// never-healthy member is reaped ONLY when this is `false` (true ghost = never
        /// SWIM-healthy AND no live transport connection — the co-confirmation pattern, same
        /// family as `swimFaultySeen ∧ livenessGoneSeen`). Transport may VETO a death; it
        /// never promotes anyone toward MEMBER. Mutated only under this monitor.
        private boolean transportConnected = false;
        private long lastDoubtAtMs = 0L;
        private MemberDescriptor descriptor = MemberDescriptor.UNKNOWN;
        /// Pending terminal-eviction backstop (#131 Model C). `Some(future)` while a co-confirmed-death
        /// backstop is armed and not yet fired/cancelled; `none()` otherwise. Held null-safe via
        /// [`Option`] and mutated only under the per-member monitor (consistent with the rest of this
        /// class). Cancelled on recovery ([`#clearConfirmedDeath`]) and on any fresh edge into DEAD
        /// ([`#dispatch`]).
        private Option<ScheduledFuture<?>> evictionBackstopHandle = Option.none();
        /// Pending DEPARTING timeout (H2, Wave 7). `Some(future)` while the member is in DEPARTING;
        /// armed on the fresh edge INTO `Departing`, cancelled on any edge OUT of it (recovery or
        /// death via another path). The firing task re-checks the state under this monitor
        /// ([`#terminalizeIfStillDeparting`]) so a recovery racing the fired-but-not-yet-run task
        /// can never be killed. Mutated only under the per-member monitor.
        private Option<ScheduledFuture<?>> departureTimeoutHandle = Option.none();
        /// Pending join-grace reaper (M10, Wave 7). Armed on tracking creation and on a fenced
        /// rejoin (DEAD→OBSERVED edge); cancelled on promotion to MEMBER and on death. The firing
        /// task dispatches [`JoinGraceExpiredNeverHealthy`], a state-table no-op everywhere but
        /// OBSERVED. Mutated only under the per-member monitor.
        private Option<ScheduledFuture<?>> joinGraceHandle = Option.none();
        /// DEPARTING timeout window — the manager's [`MembershipFsm#departureTimeout`], captured at
        /// construction. Final → monitor-free read.
        private final TimeSpan departureTimeout;
        /// Join-grace window — the manager's [`MembershipFsm#joinGrace`], captured at construction.
        /// Final → monitor-free read.
        private final TimeSpan joinGrace;

        private MemberTracking(NodeId id,
                               Fsm<MembershipState, MembershipEvent> fsm,
                               Consumer<NodeId> onEnteredDead,
                               Consumer<MembershipTransitionRecord> transitionSink,
                               Consumer<MembershipDeltaEdge> deltaSink,
                               long firstTrackedAtMs,
                               TimeSpan departureTimeout,
                               TimeSpan joinGrace) {
            this.id = id;
            this.fsm = fsm;
            this.onEnteredDead = onEnteredDead;
            this.transitionSink = transitionSink;
            this.deltaSink = deltaSink;
            this.firstTrackedAtMs = firstTrackedAtMs;
            this.departureTimeout = departureTimeout;
            this.joinGrace = joinGrace;
        }

        /// Creation stamp (ms) of this tracking — the member's first observation on the
        /// manager's wall clock. Final field; monitor-free read.
        long firstTrackedAtMs() {
            return firstTrackedAtMs;
        }

        /// Dispatch `event` to the FSM and, on a FRESH edge into DEAD (was not Dead before, is Dead
        /// after), fire the eviction hook exactly once. Centralized here so ALL DEAD paths (co-confirmed
        /// death, graceful departure, join-grace expiry) are covered uniformly without per-ingress
        /// scattering. Wave-1 Enrichment A: this same single chokepoint feeds the transition journal —
        /// when the dispatch produced an ACTUAL state change, one [`MembershipTransitionRecord`] is
        /// emitted (with the event type as cause and the post-dispatch incarnation/role) BEFORE the
        /// DEAD-edge side effects run, so the journal entry precedes the departure fan-out.
        ///
        /// Wave-4 membership-delta edges (cluster-topology-overhaul spec) fire from this same
        /// chokepoint:
        /// - JOINED on the member's FIRST entry into MEMBER (`!everJoined` + post-state MEMBER;
        ///   by FSM construction this is exactly the OBSERVED→MEMBER promotion — `Observed`
        ///   never moves to SUSPECT, and SUSPECT→MEMBER recovers an already-joined member, so
        ///   `everJoined` makes the JOINED edge exactly-once between REMOVALs).
        /// - REMOVED on the fresh DEAD edge, ONLY when `everJoined` (an OBSERVED→DEAD member
        ///   that never counted emits nothing — the spec's tangential consideration); the flag
        ///   is cleared so a fenced rejoin re-emits JOINED. Emitted AFTER `onEnteredDead`
        ///   (transport `departurePermanent` + node-wiring fan-out), mirroring the
        ///   pre-Wave-4 ordering where the link drop preceded the delta re-evaluation.
        ///
        /// Wave-7 edges fire from this same chokepoint:
        /// - H3 symmetric death-flag clearing: EVERY fresh entry into MEMBER (SwimHealthy recovery,
        ///   UpHysteresisMet recovery, H2 DEPARTING→MEMBER recovery) runs [`#clearConfirmedDeath`]
        ///   — a recovered member's next death needs FRESH co-confirmation evidence, never stale
        ///   `swimFaultySeen`/`livenessGoneSeen` flags — and cancels the join-grace reaper.
        /// - H2 DEPARTING timeout: armed on the fresh edge INTO `Departing`, cancelled on any edge
        ///   OUT of it.
        /// - M10 join-grace re-arm: a fenced rejoin (was DEAD, now OBSERVED) re-arms the reaper for
        ///   the new tenure; death cancels it.
        synchronized void dispatch(MembershipEvent event) {
            trackTransportConnectivity(event);
            var wasDead = isDead();
            var wasDeparting = isDeparting();
            var from = stateName();

            fsm.dispatch(event);

            var to = stateName();
            if (!from.equals(to)) {
                transitionSink.accept(new MembershipTransitionRecord(id,
                                                                     from,
                                                                     to,
                                                                     event.getClass().getSimpleName(),
                                                                     incarnation(),
                                                                     descriptor.role(),
                                                                     System.currentTimeMillis()));
            }
            if (!everJoined && fsm.current() instanceof MembershipState.Member) {
                everJoined = true;
                deltaSink.accept(new MembershipDeltaEdge(id, MembershipDeltaEdge.Kind.JOINED, incarnation(), descriptor.role()));
            }
            if (!from.equals(to) && fsm.current() instanceof MembershipState.Member) {
                enteredMember();
            }
            if (!wasDeparting && isDeparting()) {
                armDepartureTimeout();
            }
            if (wasDeparting && !isDeparting()) {
                cancelDepartureTimeout();
            }
            if (wasDead && fsm.current() instanceof MembershipState.Observed) {
                armJoinGrace();
            }
            if (!wasDead && isDead()) {
                enteredDead();
            }
        }

        /// Track per-member transport connectivity off the transport events flowing through this
        /// chokepoint (gate RCA fix, 2026-06-11). Pure bookkeeping for the join-grace reaper
        /// gate — no FSM state is touched here; the state table handles the same events
        /// independently (and unchanged). [`LivenessGone`] clears the flag too: production
        /// (AetherNode `nttDisconnectTap`) routes the QUIC link-drop tap into the FSM as
        /// `LivenessGone` (not `PeerDisconnected`), so without it a disconnected ghost would
        /// hold a stale `true` and defer its reap forever.
        private void trackTransportConnectivity(MembershipEvent event) {
            switch (event) {
                case PeerConnected _ -> transportConnected = true;
                case PeerDisconnected _, LivenessGone _ -> transportConnected = false;
                default -> {}
            }
        }

        /// H3 (Wave 7) — symmetric death-flag clearing on EVERY fresh entry into MEMBER: clears
        /// `swimFaultySeen`/`livenessGoneSeen`, cancels the #131 backstop, and cancels the
        /// join-grace reaper (the member reached MEMBER — the reaper's premise is gone). Covers
        /// all recovery paths uniformly (SwimHealthy, UpHysteresisMet, DEPARTING→MEMBER) so no
        /// stale single-plane flag can co-confirm with a later fresh one.
        private synchronized void enteredMember() {
            clearConfirmedDeath();
            cancelJoinGrace();
        }

        /// Fresh-edge-into-DEAD fan-out: cancel both pending timers, fire the manager's death hook
        /// once, and emit the REMOVED delta for a previously-JOINED member (clearing `everJoined`
        /// so a fenced rejoin re-emits JOINED).
        private synchronized void enteredDead() {
            cancelEvictionBackstop();
            cancelJoinGrace();
            onEnteredDead.accept(id);
            if (everJoined) {
                everJoined = false;
                deltaSink.accept(new MembershipDeltaEdge(id, MembershipDeltaEdge.Kind.REMOVED, incarnation(), descriptor.role()));
            }
        }

        private synchronized boolean isDead() {
            return fsm.current() instanceof MembershipState.Dead;
        }

        private synchronized boolean isDeparting() {
            return fsm.current() instanceof MembershipState.Departing;
        }

        /// Seed-promotion guard: dispatch [`UpHysteresisMet`] (OBSERVED→MEMBER) only when the FSM is
        /// still in OBSERVED. An id already past OBSERVED (MEMBER / SUSPECT / DEPARTING / DEAD) is left
        /// untouched, so the live-snapshot seed never resurrects a dead/suspect node. Guard + dispatch
        /// are atomic under the per-member monitor. Routes through the central [`#dispatch`]
        /// chokepoint (NOT the raw `fsm.dispatch`) so a seed promotion feeds the Wave-1 journal
        /// and fires the Wave-4 JOINED delta edge exactly like a SWIM-driven promotion — without
        /// this, boot-seeded members would never be baselined by the [`MembershipDeltaProjector`]
        /// and their deaths would emit no `NodeRemoved` (the #245 gap, re-opened for original
        /// cores). Reentrant-safe: both methods synchronize on this monitor.
        synchronized void promoteIfObserved() {
            if (fsm.current() instanceof MembershipState.Observed) {
                dispatch(new UpHysteresisMet());
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
            cancelEvictionBackstop();
        }

        /// Arm the terminal-eviction backstop (#131 Model C). IDEMPOTENT: if a backstop is already
        /// armed and not yet fired, this is a no-op — so re-entry on every SwimFaulty/livenessGone
        /// while co-confirmed never re-schedules. Otherwise schedules `terminalAction` after `window`
        /// via [`SharedScheduler#schedule`] and retains the handle for later cancellation. Guarded by
        /// the per-member monitor, consistent with the rest of this class.
        synchronized void armEvictionBackstop(Runnable terminalAction, TimeSpan window) {
            if (evictionBackstopHandle.filter(future -> !future.isDone()).isPresent()) {
                return;
            }

            evictionBackstopHandle = Option.some(SharedScheduler.schedule(terminalAction, window));
        }

        /// Cancel the pending terminal-eviction backstop (#131 Model C) if armed, and clear the handle.
        /// Idempotent — a no-op when no backstop is armed, and harmless when the timer has already
        /// fired (cancelling a completed future is a no-op). Called on recovery
        /// ([`#clearConfirmedDeath`]) and on any fresh edge into DEAD ([`#dispatch`]) so a terminal
        /// reached via another path (graceful `SwimDeparted`, join-grace expiry) never leaks a pending
        /// backstop.
        synchronized void cancelEvictionBackstop() {
            evictionBackstopHandle.onPresent(future -> future.cancel(false));
            evictionBackstopHandle = Option.none();
        }

        /// Arm the DEPARTING timeout (H2, Wave 7): after [`#departureTimeout`] of remaining in
        /// DEPARTING the member terminalizes to DEAD via a delayed `Stopped` — a drainer that went
        /// silent must not wedge in DEPARTING forever (the pre-Wave-7 inescapable trap). Replaces
        /// any stale handle defensively; armed only from the fresh-edge-into-DEPARTING branch of
        /// [`#dispatch`], so no double-arm occurs in practice.
        private synchronized void armDepartureTimeout() {
            cancelDepartureTimeout();
            departureTimeoutHandle = Option.some(SharedScheduler.schedule(this::terminalizeIfStillDeparting, departureTimeout));
        }

        /// Cancel the pending DEPARTING timeout if armed, and clear the handle. Idempotent; called
        /// on every edge OUT of DEPARTING (H2 recovery to MEMBER, or death via another path).
        private synchronized void cancelDepartureTimeout() {
            departureTimeoutHandle.onPresent(future -> future.cancel(false));
            departureTimeoutHandle = Option.none();
        }

        /// DEPARTING timeout firing under the per-member monitor (H2): terminalize ONLY if the
        /// member is STILL in DEPARTING. A recovery between timer-fire and monitor-acquire has
        /// already moved the FSM to MEMBER (and cancelled the handle), so the re-check no-ops —
        /// closing the `cancel(false)`-cannot-stop-a-running-task race; without it the delayed
        /// `Stopped` would kill a recovered MEMBER. The `Stopped` dispatch routes through the
        /// central chokepoint, so the resulting DEAD edge journals, fires the death hook, and
        /// emits the REMOVED delta exactly like any other death.
        private synchronized void terminalizeIfStillDeparting() {
            if (fsm.current() instanceof MembershipState.Departing) {
                dispatch(new Stopped());
            }
        }

        /// Arm the join-grace reaper (M10, Wave 7): after [`#joinGrace`] the member is reaped
        /// OBSERVED→DEAD via [`JoinGraceExpiredNeverHealthy`] unless it reached MEMBER first (the
        /// dispatch is a state-table no-op everywhere but OBSERVED, and promotion/death cancel the
        /// handle). Armed on tracking creation and re-armed on a fenced rejoin.
        @Contract
        synchronized void armJoinGrace() {
            cancelJoinGrace();
            joinGraceHandle = Option.some(SharedScheduler.schedule(this::expireJoinGrace, joinGrace));
        }

        /// Cancel the pending join-grace reaper if armed, and clear the handle. Idempotent; called
        /// on every fresh entry into MEMBER and into DEAD.
        private synchronized void cancelJoinGrace() {
            joinGraceHandle.onPresent(future -> future.cancel(false));
            joinGraceHandle = Option.none();
        }

        /// Join-grace firing under the per-member monitor (M10 + gate RCA fix, 2026-06-11):
        /// reap ONLY if the member is never-healthy (still OBSERVED) AND `transportConnected ==
        /// false` — a true ghost is co-confirmed by BOTH planes (never SWIM-healthy AND no live
        /// transport connection). A still-OBSERVED member with a LIVE transport connection is a
        /// busy-but-alive joiner (boot deploy-storm starving its first probe-ack): the reaper
        /// DEFERS — logs the deferral and RE-ARMS the timer for the same window — so the joiner
        /// either turns healthy (entry into MEMBER cancels the handle) or disconnects (the next
        /// firing reaps). When it does reap, the dispatch routes through the central chokepoint
        /// with the unchanged `JoinGraceExpiredNeverHealthy` journal cause; the state table
        /// confines the transition to OBSERVED→DEAD, so a racing promotion can never be killed
        /// by a fired-but-not-yet-run reaper.
        private synchronized void expireJoinGrace() {
            if (transportConnected && fsm.current() instanceof MembershipState.Observed) {
                log.info("Join-grace reaper DEFERRED for {}: never-healthy but transport connection is LIVE — re-arming (window={})",
                         id,
                         joinGrace);
                armJoinGrace();
                return;
            }
            dispatch(new JoinGraceExpiredNeverHealthy());
        }

        /// Backstop firing under the per-member monitor (#131 Model C): terminal-evict ONLY if the
        /// member is STILL co-confirmed dead. A `SwimHealthy` recovery between timer-fire and
        /// monitor-acquire runs `clearConfirmedDeath` (flags false + cancel), so `coConfirmedDead()` is
        /// false here and we no-op — closing the `cancel(false)`-cannot-stop-a-running-task race. When
        /// it does proceed, the terminal march (Suspect→Departing→Dead via `DownHysteresisMet` then
        /// `Stopped`) runs through `dispatch` (already synchronized/reentrant on this monitor); the
        /// fresh-edge-into-DEAD branch there cancels the now-fired handle and fires `onEnteredDead`
        /// exactly once.
        synchronized void evictIfStillConfirmedDead() {
            if (!coConfirmedDead()) {
                return;
            }

            dispatch(new DownHysteresisMet());
            dispatch(new Stopped());
            clearConfirmedDeath();
        }

        /// Guarded upsert of the network descriptor from a NodeInfo observation. Orthogonal to the
        /// lifecycle FSM — never touches the FSM state.
        ///
        /// Per-field downgrade guard: a descriptor update never ERASES previously-known information.
        /// A KNOWN non-empty address is retained when the update carries none, and a KNOWN non-blank
        /// role / source is retained when the update carries a blank one (Wave 2 worker accounting /
        /// audit M9: a label-less observation — e.g. a gossip-rebuilt peer NodeInfo — must not wipe a
        /// member's self-asserted role to blank, silently re-classifying a worker as core). A
        /// non-blank incoming value still wins, so a genuine re-label (core → worker) takes effect.
        /// For the address, a degraded-but-present hostname is handled by dial-time re-resolution
        /// (transport Step 1); the hazard the address guard closes is a null/empty ERASE that would
        /// silently drop the member out of `desiredConnections` (which skips address-unknown
        /// members), wedging it in a never-dialed state.
        synchronized void updateDescriptor(MemberDescriptor next) {
            descriptor = mergedDescriptor(descriptor, next);
        }

        /// Per-field downgrade-guard merge: each field takes `next` when it carries information
        /// (non-empty address, non-blank role / source), otherwise the stored value is retained.
        private static MemberDescriptor mergedDescriptor(MemberDescriptor prev, MemberDescriptor next) {
            return new MemberDescriptor(next.address().isEmpty() ? prev.address() : next.address(),
                                        next.role().isBlank() ? prev.role() : next.role(),
                                        next.source().isBlank() ? prev.source() : next.source());
        }

        /// The stored last-wins descriptor (address + role + source). Retained across DEAD so a dead
        /// node's `source` stays queryable for same-source replacement provisioning.
        synchronized MemberDescriptor descriptor() {
            return descriptor;
        }

        synchronized boolean countsTowardEffective() {
            return fsm.current()
                      .countsTowardEffective();
        }

        /// Whether this member is still in the lifecycle (NOT terminally DEAD): true for
        /// OBSERVED / MEMBER / SUSPECT / DEPARTING, false only for DEAD. Used by
        /// [`#broadcastEligibleMembers`] — consensus must keep reaching joining/suspected
        /// peers, only a co-confirmed-DEAD zombie is excluded.
        synchronized boolean notDead() {
            return ! isDead();
        }

        /// Whether this member belongs in the core dial-set: it currently counts (MEMBER + SUSPECT)
        /// AND its descriptor role is not the explicit literal `worker` (unknown role = included).
        synchronized boolean isCoreCountedMember() {
            return countsTowardEffective() && descriptor.isCore();
        }

        /// Strict quorum-numerator predicate ([`MembershipFsm#strictCoreMemberCount`], Wave 2 /
        /// W1): current state is exactly MEMBER (SUSPECT excluded) AND the descriptor role is
        /// core (unknown role = included). Single monitor acquisition pairs the state read with
        /// the role read atomically.
        synchronized boolean isStrictCoreMember() {
            return fsm.current() instanceof MembershipState.Member && descriptor.isCore();
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
                   ? descriptor.address()
                               .map(addr -> PeerTarget.peerTarget(memberId, addr))
                   : Option.none();
        }

        synchronized String stateName() {
            return fsm.current()
                      .getClass()
                      .getSimpleName();
        }

        /// Wave-1 diagnostic read: this member's SWIM-incarnation high-water mark
        /// ([`MembershipContext#lastSeenIncarnation`], reachable through the current state's
        /// shared context). Pure read under the per-member monitor.
        synchronized long incarnation() {
            return fsm.current()
                      .ctx()
                      .lastSeenIncarnation();
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

        /// Quiescence-gate variant of [`#healthHint`]: a terminally-DEAD member projects `none()`
        /// (NOT FAULTY), so its retained incarnation-fenced tombstone never poisons cluster
        /// quiescence (#68). For every live state this is identical to [`#healthHint`]. The
        /// not-DEAD gate uses the same [`#notDead`] predicate as [`#countedMembers`] /
        /// [`#broadcastEligibleMembers`], keeping the projections consistent. Pure read under the
        /// per-member monitor.
        synchronized Option<HealthHint> liveHealthHint(long nowMs, long ttlMs) {
            return notDead()
                   ? healthHint(nowMs, ttlMs)
                   : Option.none();
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
