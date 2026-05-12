// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.ClusterMembershipReducer.Outcome;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.CancelDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.CancelTimer;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.EmitDomainEvent;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.InvokeDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.ScheduleTimer;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.TimerKind;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.DrainOutcome;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.JoinDeadlineExpired;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.OperatorDecommission;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.OperatorDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SlotClaimed;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimDeparted;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimFaulty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimHealthy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.FaultyObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;
import org.pragmatica.swim.SwimObservation.SuspectObserved;
import org.pragmatica.swim.SwimObservation.UnknownObserved;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Per-peer cluster-membership FSM (spec §9 migration plan; post-E.8 always-active mode).
///
/// **Single-writer invariant.** Only the leader's FSM writes. Non-leader instances treat
/// operator events as no-ops (logged at WARN). The leader gate is enforced exclusively
/// inside the FSM.
///
/// **Reconstructibility (I1).** Local per-peer state is derived from KV. The FSM only
/// mutates `fsmStates` AFTER `commandApplier.apply(writes)` succeeds. On consensus
/// rejection, local state is left untouched — the next KV notification (or replay on
/// node restart) will reconcile it.
///
/// **Leader-takeover protocol resume (spec §6.2 steps 4–5).** On `start()`, after the KV
/// replay, the new leader resumes in-flight protocols for every peer in DRAINING or JOINING:
/// — `DRAINING` → `drainCoordinator.awaitDrainAck(peer, remainingDrainTimeout)` is called and
///   the resulting `Promise` is chained back as a `DrainOutcome(peer, success, nowMs)` event.
///   If the deadline has already elapsed on entry, `DrainOutcome(peer, false, nowMs)` is
///   enqueued immediately to drive `(DRAINING, DrainOutcome(false)) → FAILED_DRAIN`.
/// — `JOINING` → a fresh one-shot `JOIN_DEADLINE` timer is scheduled with the remaining
///   budget; if elapsed, `JoinDeadlineExpired(peer, nowMs)` is enqueued immediately.
/// Both are leader-gated: followers MUST NOT resume in-flight protocols (single-writer
/// invariant).
///
/// **Concurrency.** A single `ReentrantLock` (`fsmLock`) serializes all FSM event
/// delivery — SWIM observations, KV notifications, operator events. Public read-only
/// accessors (`snapshot()`, `get()`) use the lock-free `ConcurrentHashMap`.
public final class MembershipFsm {
    private static final Logger log = LoggerFactory.getLogger(MembershipFsm.class);

    private final NodeId self;

    private final MembershipFsmConfig config;

    private final ClusterMembershipReducer reducer;

    private final LifecycleSnapshotReader lifecycleSnapshotReader;

    private final SlotSnapshotReader slotSnapshotReader;

    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;

    private final DrainCoordinator drainCoordinator;

    private final TimerScheduler scheduler;

    private final BooleanSupplier isLeader;

    /// F.4 (2026-05-12) — Predicate gating the QUIC `PeerConnected` synthesis bridge. Returns
    /// `true` iff `peer` is BOTH (a) a real cluster peer (present in static topology config —
    /// i.e. not an auto-provisioned dynamic peer) AND (b) currently in SWIM's alive member
    /// set. Composed by the wiring layer over `TopologyConfig.coreNodes()` and
    /// `SwimProtocol.currentHealth()`. Tests inject controllable predicates. Default in
    /// test-only factories is **reject-all** so that synthetic `onPeerConnected` calls
    /// require explicit opt-in; this prevents silent test-side races.
    private final Predicate<NodeId> isKnownAliveClusterPeer;

    private final ReentrantLock fsmLock = new ReentrantLock();

    private final Map<NodeId, MembershipFsmState> fsmStates = new ConcurrentHashMap<>();

    /// Per-peer last-seen `NodeLifecycleValue` (KV-write or KV-notification). Used to
    /// preserve host/port/observedCoreEpoch/transitionedAt/provisioningSource when the
    /// reducer emits a state-only `Put` (spec F18 — non-state fields must survive
    /// transitions). The reducer is pure and emits minimal values; the wiring layer
    /// rewrites the value before consensus apply.
    private final Map<NodeId, NodeLifecycleValue> priorLifecycle = new ConcurrentHashMap<>();

    private final Map<String, NodeId> slotIdToPeer = new ConcurrentHashMap<>();

    private final Map<TimerHandle, ScheduledFuture<?>> pendingTimers = new ConcurrentHashMap<>();

    private final AtomicBoolean started = new AtomicBoolean();

    private MembershipFsm(NodeId self,
                          MembershipFsmConfig config,
                          ClusterMembershipReducer reducer,
                          LifecycleSnapshotReader lifecycleSnapshotReader,
                          SlotSnapshotReader slotSnapshotReader,
                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                          DrainCoordinator drainCoordinator,
                          TimerScheduler scheduler,
                          BooleanSupplier isLeader,
                          Predicate<NodeId> isKnownAliveClusterPeer) {
        this.self = self;
        this.config = config;
        this.reducer = reducer;
        this.lifecycleSnapshotReader = lifecycleSnapshotReader;
        this.slotSnapshotReader = slotSnapshotReader;
        this.commandApplier = commandApplier;
        this.drainCoordinator = drainCoordinator;
        this.scheduler = scheduler;
        this.isLeader = isLeader;
        this.isKnownAliveClusterPeer = isKnownAliveClusterPeer;
    }

    /// Read-only factory (no-op writes). Useful for tests that only exercise the reducer +
    /// snapshot derivation without consensus dependencies.
    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader) {
        return membershipFsm(self,
                             config,
                             lifecycleSnapshotReader,
                             slotSnapshotReader,
                             NO_OP_COMMAND_APPLIER,
                             NO_OP_DRAIN_COORDINATOR,
                             defaultScheduler(),
                             NEVER_LEADER);
    }

    /// Write-capable factory. When `isLeader.getAsBoolean()` returns `true`, operator events
    /// route through the FSM: proposed via `commandApplier`, drain effects dispatched to
    /// `drainCoordinator`, timers scheduled via `scheduler`.
    ///
    /// **F.4 (2026-05-12) overload.** Defaults `isKnownAliveClusterPeer` to **reject-all** so
    /// that `onPeerConnected` (the QUIC PeerConnected → SwimHealthy synthesis bridge) is
    /// inert unless callers explicitly opt in via the 9-arg overload below. Production
    /// wiring (`AetherNode.buildMembershipFsm`) must use the 9-arg form.
    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader,
                                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                              DrainCoordinator drainCoordinator,
                                              TimerScheduler scheduler,
                                              BooleanSupplier isLeader) {
        return membershipFsm(self,
                             config,
                             lifecycleSnapshotReader,
                             slotSnapshotReader,
                             commandApplier,
                             drainCoordinator,
                             scheduler,
                             isLeader,
                             REJECT_ALL_PEERS);
    }

    /// F.4 (2026-05-12) — full production factory. `isKnownAliveClusterPeer` predicate gates
    /// the QUIC `PeerConnected` synthesis bridge (`onPeerConnected`). See field doc on
    /// `isKnownAliveClusterPeer` for semantics.
    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader,
                                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                              DrainCoordinator drainCoordinator,
                                              TimerScheduler scheduler,
                                              BooleanSupplier isLeader,
                                              Predicate<NodeId> isKnownAliveClusterPeer) {
        var reducer = ClusterMembershipReducer.clusterMembershipReducer(config);
        return new MembershipFsm(self,
                                 config,
                                 reducer,
                                 lifecycleSnapshotReader,
                                 slotSnapshotReader,
                                 commandApplier,
                                 drainCoordinator,
                                 scheduler,
                                 isLeader,
                                 isKnownAliveClusterPeer);
    }

    /// Custom-reducer factory (test-only — lets callers inject a reducer with deterministic
    /// thresholds). Defaults the write dependencies to no-ops.
    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              ClusterMembershipReducer reducer,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader) {
        return new MembershipFsm(self,
                                 config,
                                 reducer,
                                 lifecycleSnapshotReader,
                                 slotSnapshotReader,
                                 NO_OP_COMMAND_APPLIER,
                                 NO_OP_DRAIN_COORDINATOR,
                                 defaultScheduler(),
                                 NEVER_LEADER,
                                 REJECT_ALL_PEERS);
    }

    public Promise<Unit> start() {
        if (!started.compareAndSet(false, true)) {
            return Promise.unitPromise();
        }
        replayFromKv();
        resumeInFlightProtocolsIfLeader();
        log.info("MembershipFsm started for {} (peers reconstructed: {})",
                 self.id(),
                 fsmStates.size());
        return Promise.unitPromise();
    }

    public Promise<Unit> stop() {
        if (!started.compareAndSet(true, false)) {
            return Promise.unitPromise();
        }
        cancelAllTimers();
        clearState();
        log.info("MembershipFsm stopped for {}", self.id());
        return Promise.unitPromise();
    }

    /// Returns the current per-peer state map. Public read-only API for downstream subsystems
    /// (status routes, routing) per spec §9 E.3 deliverable.
    public Map<NodeId, MembershipFsmState> snapshot() {
        return Map.copyOf(fsmStates);
    }

    /// Returns the current state for `peer` if tracked, or `Option.none()` otherwise.
    public Option<MembershipFsmState> get(NodeId peer) {
        return option(fsmStates.get(peer));
    }

    /// SWIM observation entry point. Translates the observation into a typed FSM event and
    /// routes it through the leader-writing dispatcher (spec §9 E.5).
    ///
    /// **Leader gate (spec §6.1).** When `isLeader.getAsBoolean()` returns `false`, the
    /// observation is dropped (TRACE log) — followers MUST NOT advance their FSM state from
    /// SWIM observations; they learn state via `NodeLifecycleKey` KV notifications only.
    /// This closes the F1 follower-state-drift bug from the spec audit: each node has its own
    /// SWIM view, so allowing followers to write to `fsmStates` from SWIM would diverge them
    /// from the leader's authoritative view.
    ///
    /// **Leader write path.** When leader: the translated event flows through the same
    /// `processOperatorOrFsmEvent` dispatcher that operator commands use. The reducer's
    /// `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` transition fires here — this is the structural
    /// fix that closes the smoking-gun bug (previously the threshold gate + phase suppression
    /// in the legacy path prevented this write from ever firing).
    @Contract public void onSwimObservation(SwimObservation observation) {
        if (!started.get()) {
            return;
        }
        if (!isLeader.getAsBoolean()) {
            logSwimDropOnFollower(observation);
            return;
        }
        translate(observation).onPresent(this::processOperatorOrFsmEvent);
    }

    /// Leader-change entry point (spec §6.2 step 7 — Bootstrap-correction 2026-05-12, second
    /// trigger). The original NodeLifecycle.ACTIVE listener fires when local subsystems are
    /// ready, but that can race leader election: if signalReady fires BEFORE this node is
    /// elected leader, the synthetic `SwimHealthy(self)` is dropped by the leader-write gate
    /// inside `onSwimObservation`, and no retry path existed. Hooking self-bootstrap into
    /// `LeaderChange` closes the race in the opposite direction — when leader election
    /// completes AFTER subsystem readiness, this entry re-injects the synthetic observation.
    ///
    /// **Idempotence.** Both triggers (NodeLifecycle ACTIVE and LeaderChange-to-self) route
    /// through the same `onSwimObservation` path. The reducer cell `(ON_DUTY, SwimHealthy) →
    /// nop` guarantees the second invocation is a no-op once self is already ON_DUTY — even
    /// if both triggers fire, the FSM writes self exactly once.
    ///
    /// **Non-leader transitions.** When `localNodeIsLeader() == false` (becoming follower, or
    /// follower-to-follower leader update), nothing is enqueued. The new leader writes self's
    /// ON_DUTY entry on its side; this node learns it via the `NodeLifecycleKey` KV
    /// notification path.
    @Contract public void onLeaderChange(LeaderChange leaderChange) {
        if (!started.get()) {
            return;
        }
        if (!leaderChange.localNodeIsLeader()) {
            return;
        }
        if (isSelfAlreadyOnDuty()) {
            log.debug("MembershipFsm: onLeaderChange(self={}) — already ON_DUTY, no synthetic SwimHealthy needed", self.id());
            return;
        }
        log.info("MembershipFsm: onLeaderChange(self={}) — synthesizing SwimHealthy(self) for self-bootstrap (spec §6.2 step 7)",
                 self.id());
        onSwimObservation(new HealthyObserved(self, 0L));
    }

    /// QUIC PeerConnected → SwimHealthy synthesis bridge (F.4, 2026-05-12; spec §4 +
    /// §6.2 step 7). The QUIC handshake completes deterministically within ~100ms of cluster
    /// boot, while SWIM probe Ack landing is jittered (first probe ~8s, then ~1s with random
    /// target selection) and may miss peers entirely on small clusters within bounded time.
    /// QUIC `PeerConnected` is a stronger liveness signal than SWIM probe Ack: handshake
    /// completed implies authenticated + reachable + serving. When the wiring layer routes a
    /// QUIC PeerConnected event here, we synthesize a `HealthyObserved(peer, 0L)` into the
    /// local FSM via `onSwimObservation`, which fires `(UNTRACKED, SwimHealthy) → ON_DUTY` on
    /// the leader (consensus-replicated `Put(L=ON_DUTY)`).
    ///
    /// **Precondition filters (in order).**
    ///
    /// 1. **Already-started gate.** Mirrors all other entry points — pre-`start()` calls drop.
    ///
    /// 2. **Self filter.** Self bootstrap goes through the existing
    ///    `NodeLifecycle.ACTIVE` and `LeaderChange-to-self` paths (spec §6.2 step 7); the
    ///    QUIC bridge ignores `peer == self`. (SWIM does not observe self either.)
    ///
    /// 3. **Static-config + SWIM-alive gate** (`isKnownAliveClusterPeer.test(peer)`). The
    ///    bridge fires ONLY for peers that are (a) members of the static topology config
    ///    (`TopologyConfig.coreNodes()` — i.e., real cluster peers, not auto-provisioned
    ///    dynamic peers with fresh NodeIds) AND (b) currently in SWIM's alive member set.
    ///    Dynamic / auto-provisioned peers legitimately need the SWIM probe-Ack path to
    ///    confirm them. The SWIM-alive sub-check avoids races where QUIC connects to a peer
    ///    SWIM has not yet admitted (e.g., stale handshake before re-join), preventing
    ///    premature `ON_DUTY` writes.
    ///
    /// **Single-writer preservation.** The synthesized event flows through
    /// `onSwimObservation` which enforces the leader-write gate (spec §6.1). On followers
    /// the synthetic observation is silently dropped (TRACE log) — the leader writes the
    /// follower's own `NodeLifecycleKey` via consensus, and the follower learns via KV
    /// notification.
    ///
    /// **Idempotence.** Multiple PeerConnected events for the same peer (e.g., transient
    /// reconnect) are harmless: the reducer's `(ON_DUTY, SwimHealthy) → nop` cell short-
    /// circuits the second write. Belt-and-suspenders convergence between this bridge and
    /// the SWIM probe-Ack path is similarly safe — whichever signal arrives first wins; the
    /// second is a no-op.
    @Contract public void onPeerConnected(NodeId peer) {
        if (!started.get()) {
            return;
        }
        if (peer.equals(self)) {
            return;
        }
        if (!isKnownAliveClusterPeer.test(peer)) {
            if (log.isTraceEnabled()) {
                log.trace("MembershipFsm: onPeerConnected({}) dropped — peer not in static config or not SWIM-alive (F.4 filter)",
                          peer.id());
            }
            return;
        }
        log.debug("MembershipFsm: onPeerConnected({}) — synthesizing SwimHealthy via QUIC bridge (F.4)",
                  peer.id());
        onSwimObservation(new HealthyObserved(peer, 0L));
    }

    private boolean isSelfAlreadyOnDuty() {
        var current = fsmStates.get(self);
        return current instanceof MembershipFsmState.OnDuty;
    }

    private void logSwimDropOnFollower(SwimObservation observation) {
        if (log.isTraceEnabled()) {
            log.trace("MembershipFsm: SWIM observation {} dropped on follower {} (spec §6.1 — followers learn via KV notifications)",
                      observation.getClass().getSimpleName(),
                      self.id());
        }
    }

    /// Event entry point for operator commands and leader-issued protocol feedback.
    /// `OperatorDrain` / `OperatorDecommission` / `DrainOutcome` / `JoinDeadlineExpired`
    /// events are applied on the leader (writes proposed via `commandApplier`, drain
    /// coordinator invoked, timers scheduled). On followers, leader-writing events are
    /// dropped with a WARN log (single-writer invariant).
    @Contract public void enqueueOperatorEvent(MembershipFsmEvent event) {
        if (!started.get()) {
            log.debug("MembershipFsm: operator event {} for {} ignored (not started)",
                      event.getClass().getSimpleName(),
                      event.peer().id());
            return;
        }
        if (isLeaderWritingEvent(event) && !isLeader.getAsBoolean()) {
            log.warn(
                    "MembershipFsm: operator event {} for {} received on non-leader {} — no-op (single-writer invariant). "
                    + "Possible leader-handoff race; caller should retry against the new leader.",
                    event.getClass().getSimpleName(),
                    event.peer().id(),
                    self.id());
            return;
        }
        processOperatorOrFsmEvent(event);
    }

    /// KV-notification handler for `NodeLifecycleKey` puts. Updates the FSM state from the
    /// externally-written lifecycle value WITHOUT emitting any reducer effects (the value
    /// already reflects the production write — the FSM derives its state, it doesn't re-act).
    @Contract public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> put) {
        if (!started.get()) {
            return;
        }
        applyExternalLifecyclePut(put.cause().key().nodeId(), put.cause().value());
    }

    /// KV-notification handler for `NodeLifecycleKey` removes. Returns the peer to `UNTRACKED`
    /// (this fires when `DecommissionedAtomGc` cleans up a fully-decommissioned atom). If the
    /// peer still has a provisioning slot, the shadow falls back to `PROVISIONING` instead.
    @Contract public void onNodeLifecycleRemove(ValueRemove<NodeLifecycleKey, NodeLifecycleValue> remove) {
        if (!started.get()) {
            return;
        }
        applyExternalLifecycleRemove(remove.cause().key().nodeId());
    }

    /// KV-notification handler for `ProvisioningSlotKey` puts. Updates slot-to-peer mapping and,
    /// when the slot is newly claimed (`assignedNodeId.isPresent()`), feeds a `SlotClaimed`
    /// event into the reducer.
    @Contract public void onProvisioningSlotPut(ValuePut<ProvisioningSlotKey, ProvisioningSlotValue> put) {
        if (!started.get()) {
            return;
        }
        applySlotPut(put.cause().key().slotId(), put.cause().value());
    }

    /// KV-notification handler for `ProvisioningSlotKey` removes. Cleans up the slot-to-peer
    /// mapping. Does not feed the reducer (the lifecycle transition `JOINING → ON_DUTY` /
    /// `JOINING → DECOMMISSIONED` already removed the slot association).
    @Contract public void onProvisioningSlotRemove(ValueRemove<ProvisioningSlotKey, ProvisioningSlotValue> remove) {
        if (!started.get()) {
            return;
        }
        applySlotRemove(remove.cause().key().slotId());
    }

    /// Events that may produce consensus writes via the FSM reducer and therefore require
    /// the single-writer (leader-only) gate.
    ///
    /// **E.4 set (operator + protocol feedback):** `OperatorDrain`, `OperatorDecommission`,
    /// `DrainOutcome` (from `DrainCoordinator.awaitDrainAck`), `JoinDeadlineExpired` (from the
    /// JOIN_DEADLINE timer) — the leader started the protocol, so the leader must own its
    /// terminal write.
    ///
    /// **E.5 extension (SWIM observations):** `SwimFaulty`, `SwimDeparted`, `SwimHealthy`.
    /// Per spec §5 these can produce consensus writes:
    /// - `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` — the smoking-gun transition.
    /// - `(ON_DUTY, SwimDeparted) → DECOMMISSIONED` — same semantics as FAULTY per §4.
    /// - `(JOINING, SwimHealthy) → ON_DUTY` — Q1=A leader-initiated promotion.
    /// - `(JOINING, SwimDeparted) → DECOMMISSIONED` and `(FAILED_DRAIN, SwimDeparted) →
    ///   DECOMMISSIONED` also fall under the SWIM-writing classification.
    /// - `(DRAINING, SwimDeparted) → DECOMMISSIONED` (hard-departed, cancels drain).
    /// Even SWIM-event cells that are nops (e.g., `(ON_DUTY, SwimHealthy) → nop` for
    /// re-confirmation) flow through this path; the reducer returns an empty `writes` list
    /// and `proposeWritesAndApply` short-circuits without proposing anything.
    ///
    /// `SlotClaimed` remains a shadow-only event (no consensus write — the slot key was
    /// already written by another actor; this event drives a derived JOINING transition).
    private static boolean isLeaderWritingEvent(MembershipFsmEvent event) {
        return event instanceof OperatorDrain
               || event instanceof OperatorDecommission
               || event instanceof DrainOutcome
               || event instanceof JoinDeadlineExpired
               || event instanceof SwimFaulty
               || event instanceof SwimDeparted
               || event instanceof SwimHealthy;
    }

    private void applyExternalLifecyclePut(NodeId peer, NodeLifecycleValue value) {
        fsmLock.lock();
        try {
            priorLifecycle.put(peer, value);
            var newState = deriveStateFromLifecycle(peer, value, slotIdForPeer(peer));
            var previous = fsmStates.put(peer, newState);
            logExternalLifecycleChange(peer, previous, newState, value.state());
        } finally {
            fsmLock.unlock();
        }
    }

    private void applyExternalLifecycleRemove(NodeId peer) {
        fsmLock.lock();
        try {
            priorLifecycle.remove(peer);
            var slotIdOpt = slotIdForPeer(peer);
            slotIdOpt.apply(() -> applyLifecycleRemoveWithoutSlot(peer),
                            slotId -> applyLifecycleRemoveWithSlot(peer, slotId));
        } finally {
            fsmLock.unlock();
        }
    }

    private void applyLifecycleRemoveWithoutSlot(NodeId peer) {
        var previous = fsmStates.remove(peer);
        log.info("MembershipFsm: lifecycle-removed peer={} previous={} → UNTRACKED",
                 peer.id(),
                 describe(previous));
    }

    private void applyLifecycleRemoveWithSlot(NodeId peer, String slotId) {
        var derived = MembershipFsmState.provisioning(peer, slotId);
        var previous = fsmStates.put(peer, derived);
        log.info("MembershipFsm: lifecycle-removed peer={} retained slot={}, prior={} → PROVISIONING",
                 peer.id(),
                 slotId,
                 describe(previous));
    }

    private void applySlotPut(String slotId, ProvisioningSlotValue value) {
        value.assignedNodeId().apply(() -> applySlotPutUnassigned(slotId),
                                      peer -> applySlotPutAssigned(slotId, peer, value));
    }

    private void applySlotPutUnassigned(String slotId) {
        slotIdToPeer.remove(slotId);
        log.debug("MembershipFsm: slot-put slotId={} unassigned (PROVISIONING placeholder; spec §4.2)",
                  slotId);
    }

    private void applySlotPutAssigned(String slotId, NodeId peer, ProvisioningSlotValue value) {
        slotIdToPeer.put(slotId, peer);
        fsmLock.lock();
        try {
            ensureProvisioningTracked(peer, slotId);
            processFsmEventLocked(new SlotClaimed(peer, slotId, value.spawnedAtMs()));
        } finally {
            fsmLock.unlock();
        }
    }

    private void ensureProvisioningTracked(NodeId peer, String slotId) {
        fsmStates.computeIfAbsent(peer, key -> MembershipFsmState.provisioning(key, slotId));
    }

    private void applySlotRemove(String slotId) {
        var removedPeer = slotIdToPeer.remove(slotId);
        log.debug("MembershipFsm: slot-removed slotId={} peer={}",
                  slotId,
                  removedPeer == null ? "<unassigned>" : removedPeer.id());
    }

    private void processFsmEvent(MembershipFsmEvent event) {
        fsmLock.lock();
        try {
            processFsmEventLocked(event);
        } finally {
            fsmLock.unlock();
        }
    }

    private void processFsmEventLocked(MembershipFsmEvent event) {
        var peer = event.peer();
        var current = fsmStates.getOrDefault(peer, MembershipFsmState.untracked(peer));
        var outcome = reducer.apply(current, event);
        fsmStates.put(peer, outcome.newState());
        logFsmOutcome(event, current, outcome);
    }

    /// Operator-event dispatcher. Splits writing-event handling from shadow-only SWIM
    /// observations so the writing path can short-circuit to a write-and-mutate flow while
    /// SWIM events stay shadow-only in E.4.
    private void processOperatorOrFsmEvent(MembershipFsmEvent event) {
        if (!isLeaderWritingEvent(event)) {
            processFsmEvent(event);
            return;
        }
        fsmLock.lock();
        try {
            processOperatorEventLocked(event);
        } finally {
            fsmLock.unlock();
        }
    }

    private void processOperatorEventLocked(MembershipFsmEvent event) {
        var peer = event.peer();
        var current = fsmStates.getOrDefault(peer, MembershipFsmState.untracked(peer));
        var outcome = reducer.apply(current, event);
        if (outcome.writes().isEmpty()) {
            applyEffectsLocked(outcome.effects());
            logOperatorOutcome(event, current, outcome, true);
            return;
        }
        proposeWritesAndApply(event, current, outcome);
    }

    @Contract private void proposeWritesAndApply(MembershipFsmEvent event,
                                                  MembershipFsmState prior,
                                                  Outcome outcome) {
        var peer = event.peer();
        var resolvedWrites = resolveLifecycleWrites(outcome.writes());
        log.info("MembershipFsm: operator event {} for {} → proposing {} write(s) via consensus",
                 event.getClass().getSimpleName(),
                 peer.id(),
                 resolvedWrites.size());
        commandApplier.apply(resolvedWrites)
                       .onSuccess(_ -> handleOperatorWriteSuccess(event, prior, outcome, resolvedWrites))
                       .onFailure(cause -> log.warn(
                               "MembershipFsm: operator event {} for {} consensus rejected: {} — local state NOT mutated (I1)",
                               event.getClass().getSimpleName(),
                               peer.id(),
                               cause.message()));
    }

    @Contract private void handleOperatorWriteSuccess(MembershipFsmEvent event,
                                                       MembershipFsmState prior,
                                                       Outcome outcome,
                                                       List<KVCommand<AetherKey>> resolvedWrites) {
        fsmLock.lock();
        try {
            fsmStates.put(outcome.newState().peer(), outcome.newState());
            recordResolvedLifecycleWrites(resolvedWrites);
            applyEffectsLocked(outcome.effects());
            logOperatorOutcome(event, prior, outcome, true);
        } finally {
            fsmLock.unlock();
        }
    }

    /// F18 resolution. Rewrites each `Put<NodeLifecycleKey, NodeLifecycleValue>` in `writes`
    /// to preserve host/port/observedCoreEpoch/transitionedAt/provisioningSource from the
    /// last-seen `NodeLifecycleValue` (via `priorLifecycle`). The reducer emits minimal
    /// 2-arg values for purity; consensus must receive complete values. Non-lifecycle writes
    /// (e.g., `Remove<ProvisioningSlotKey>`) pass through untouched.
    private List<KVCommand<AetherKey>> resolveLifecycleWrites(List<KVCommand<AetherKey>> writes) {
        return writes.stream().map(this::resolveSingleWrite).toList();
    }

    private KVCommand<AetherKey> resolveSingleWrite(KVCommand<AetherKey> command) {
        if (command instanceof Put<AetherKey, ?> put
            && put.key() instanceof NodeLifecycleKey lifecycleKey
            && put.value() instanceof NodeLifecycleValue minimal) {
            return new Put<>(lifecycleKey, mergeWithPrior(lifecycleKey.nodeId(), minimal));
        }
        return command;
    }

    private NodeLifecycleValue mergeWithPrior(NodeId peer, NodeLifecycleValue minimal) {
        var prior = priorLifecycle.get(peer);
        if (prior == null) {
            return minimal;
        }
        return new NodeLifecycleValue(minimal.state(),
                                       minimal.updatedAt(),
                                       prior.host(),
                                       prior.port(),
                                       prior.observedCoreEpoch(),
                                       prior.transitionedAt(),
                                       prior.provisioningSource());
    }

    private void recordResolvedLifecycleWrites(List<KVCommand<AetherKey>> resolvedWrites) {
        resolvedWrites.forEach(this::recordSingleResolvedWrite);
    }

    private void recordSingleResolvedWrite(KVCommand<AetherKey> command) {
        if (command instanceof Put<AetherKey, ?> put
            && put.key() instanceof NodeLifecycleKey lifecycleKey
            && put.value() instanceof NodeLifecycleValue value) {
            priorLifecycle.put(lifecycleKey.nodeId(), value);
        }
    }

    private void applyEffectsLocked(List<MembershipEffect> effects) {
        effects.forEach(this::applyEffect);
    }

    private void applyEffect(MembershipEffect effect) {
        switch (effect) {
            case ScheduleTimer s -> scheduleTimer(s);
            case CancelTimer c -> cancelTimer(c.peer(), c.kind());
            case InvokeDrain d -> invokeDrain(d.peer(), d.reason());
            case CancelDrain c -> log.info(
                    "MembershipFsm: CancelDrain for {} — best-effort log only (E.7 wires coordinator cancel)",
                    c.peer().id());
            case EmitDomainEvent e -> log.info("MembershipFsm: domain event {} for {} (reason={})",
                                                  e.event(),
                                                  e.peer().id(),
                                                  e.reason());
        }
    }

    private void scheduleTimer(ScheduleTimer timer) {
        var handle = new TimerHandle(timer.peer(), timer.kind());
        cancelTimer(timer.peer(), timer.kind());
        var future = scheduler.schedule(() -> onTimerFired(handle), timer.delay());
        pendingTimers.put(handle, future);
        log.debug("MembershipFsm: scheduled timer {} for {} in {}ms",
                  timer.kind(),
                  timer.peer().id(),
                  timer.delay().millis());
    }

    private void cancelTimer(NodeId peer, TimerKind kind) {
        var handle = new TimerHandle(peer, kind);
        var future = pendingTimers.remove(handle);
        if (future != null) {
            future.cancel(false);
            log.debug("MembershipFsm: cancelled timer {} for {}", kind, peer.id());
        }
    }

    private void cancelAllTimers() {
        pendingTimers.values().forEach(future -> future.cancel(false));
        pendingTimers.clear();
    }

    @Contract private void onTimerFired(TimerHandle handle) {
        pendingTimers.remove(handle);
        log.debug("MembershipFsm: timer {} fired for {}", handle.kind(), handle.peer().id());
        if (handle.kind() == TimerKind.JOIN_DEADLINE) {
            enqueueOperatorEvent(new JoinDeadlineExpired(handle.peer(), System.currentTimeMillis()));
        }
    }

    @Contract private void invokeDrain(NodeId peer, DrainReason reason) {
        log.info("MembershipFsm: invoking DrainCoordinator.prepareDrain({}, {})", peer.id(), reason);
        drainCoordinator.prepareDrain(peer, reason)
                         .onFailure(cause -> onPrepareDrainFailure(peer, cause))
                         .onSuccess(_ -> awaitDrainAndFeedback(peer, config.drainTimeout()));
    }

    private void onPrepareDrainFailure(NodeId peer, Cause cause) {
        log.warn("MembershipFsm: prepareDrain failed for {}: {} — feeding DrainOutcome(false)",
                 peer.id(),
                 cause.message());
        enqueueOperatorEvent(new DrainOutcome(peer, false, System.currentTimeMillis()));
    }

    /// Chains `DrainCoordinator.awaitDrainAck(peer, timeout)` and translates its resolution
    /// back into a `DrainOutcome` event (spec §8.2, F4). Success → `DrainOutcome(true)` →
    /// `(DRAINING, DrainOutcome(true)) → DECOMMISSIONED`. Failure (or hard-deadline) →
    /// `DrainOutcome(false)` → `(DRAINING, DrainOutcome(false)) → FAILED_DRAIN`.
    @Contract private void awaitDrainAndFeedback(NodeId peer, TimeSpan timeout) {
        log.debug("MembershipFsm: awaiting drain ack for {} (timeout={}ms)", peer.id(), timeout.millis());
        drainCoordinator.awaitDrainAck(peer, timeout)
                         .onSuccess(_ -> enqueueOperatorEvent(new DrainOutcome(peer, true, System.currentTimeMillis())))
                         .onFailure(cause -> onAwaitDrainAckFailure(peer, cause));
    }

    private void onAwaitDrainAckFailure(NodeId peer, Cause cause) {
        log.warn("MembershipFsm: awaitDrainAck failed for {}: {} — feeding DrainOutcome(false)",
                 peer.id(),
                 cause.message());
        enqueueOperatorEvent(new DrainOutcome(peer, false, System.currentTimeMillis()));
    }

    private void replayFromKv() {
        fsmLock.lock();
        try {
            clearState();
            slotSnapshotReader.forEachSlot(this::indexSlotForReplay);
            lifecycleSnapshotReader.forEachLifecycle(this::reconstructPeerFromLifecycle);
            slotSnapshotReader.forEachSlot(this::ensureSlotPeerCovered);
            log.info("MembershipFsm replay: lifecycle peers={}, slot peers={}",
                     fsmStates.size(),
                     slotIdToPeer.size());
        } finally {
            fsmLock.unlock();
        }
    }

    private void indexSlotForReplay(ProvisioningSlotKey slotKey, ProvisioningSlotValue slotValue) {
        slotValue.assignedNodeId().onPresent(peer -> slotIdToPeer.put(slotKey.slotId(), peer));
    }

    private void reconstructPeerFromLifecycle(NodeLifecycleKey lifecycleKey, NodeLifecycleValue value) {
        var peer = lifecycleKey.nodeId();
        priorLifecycle.put(peer, value);
        var derived = deriveStateFromLifecycle(peer, value, slotIdForPeer(peer));
        fsmStates.put(peer, derived);
    }

    private void ensureSlotPeerCovered(ProvisioningSlotKey slotKey, ProvisioningSlotValue slotValue) {
        slotValue.assignedNodeId().onPresent(peer -> ensureProvisioningTracked(peer, slotKey.slotId()));
    }

    private void clearState() {
        fsmStates.clear();
        slotIdToPeer.clear();
        priorLifecycle.clear();
    }

    /// Leader-takeover step 4+5 (spec §6.2, F7/F8). After KV replay, the new leader resumes
    /// in-flight protocols: re-attaches `awaitDrainAck` to every peer in DRAINING, and
    /// reschedules JOIN_DEADLINE timers for every peer in JOINING. Both leader-gated —
    /// followers MUST NOT take over running protocols.
    private void resumeInFlightProtocolsIfLeader() {
        if (!isLeader.getAsBoolean()) {
            log.debug("MembershipFsm: resumeInFlightProtocols skipped on {} — not leader", self.id());
            return;
        }
        var nowMs = System.currentTimeMillis();
        priorLifecycle.forEach((peer, value) -> resumePerPeer(peer, value, nowMs));
    }

    private void resumePerPeer(NodeId peer, NodeLifecycleValue value, long nowMs) {
        switch (value.state()) {
            case DRAINING, SHUTTING_DOWN -> resumeDrain(peer, value, nowMs);
            case JOINING -> resumeJoinDeadline(peer, value, nowMs);
            default -> { /* no-op for ON_DUTY/DECOMMISSIONED/FAILED_DRAIN — nothing to resume */ }
        }
    }

    @Contract private void resumeDrain(NodeId peer, NodeLifecycleValue value, long nowMs) {
        var elapsed = Math.max(0L, nowMs - value.updatedAt());
        var remainingMs = config.drainTimeout().millis() - elapsed;
        if (remainingMs <= 0L) {
            log.info("MembershipFsm: resumeDrain peer={} hard-deadline elapsed (drainStarted={}, elapsed={}ms) → DrainOutcome(false) immediate",
                     peer.id(), value.updatedAt(), elapsed);
            enqueueOperatorEvent(new DrainOutcome(peer, false, nowMs));
            return;
        }
        log.info("MembershipFsm: resumeDrain peer={} remaining={}ms — re-attaching awaitDrainAck",
                 peer.id(), remainingMs);
        awaitDrainAndFeedback(peer, TimeSpan.timeSpan(remainingMs).millis());
    }

    @Contract private void resumeJoinDeadline(NodeId peer, NodeLifecycleValue value, long nowMs) {
        var elapsed = Math.max(0L, nowMs - value.updatedAt());
        var remainingMs = config.joinDeadline().millis() - elapsed;
        if (remainingMs <= 0L) {
            log.info("MembershipFsm: resumeJoinDeadline peer={} deadline elapsed (joinedAt={}, elapsed={}ms) → JoinDeadlineExpired immediate",
                     peer.id(), value.updatedAt(), elapsed);
            enqueueOperatorEvent(new JoinDeadlineExpired(peer, nowMs));
            return;
        }
        log.info("MembershipFsm: resumeJoinDeadline peer={} remaining={}ms — scheduling timer",
                 peer.id(), remainingMs);
        var handle = new TimerHandle(peer, TimerKind.JOIN_DEADLINE);
        cancelTimer(peer, TimerKind.JOIN_DEADLINE);
        var future = scheduler.schedule(() -> onTimerFired(handle), TimeSpan.timeSpan(remainingMs).millis());
        pendingTimers.put(handle, future);
    }

    private Option<String> slotIdForPeer(NodeId peer) {
        for (var entry : slotIdToPeer.entrySet()) {
            if (entry.getValue().equals(peer)) {
                return some(entry.getKey());
            }
        }
        return none();
    }

    private static MembershipFsmState deriveStateFromLifecycle(NodeId peer,
                                                                NodeLifecycleValue value,
                                                                Option<String> slotId) {
        return switch (value.state()) {
            case JOINING -> MembershipFsmState.joining(peer, value.updatedAt(), slotId);
            case ON_DUTY -> MembershipFsmState.onDuty(peer, value.updatedAt());
            case DRAINING -> MembershipFsmState.draining(peer, value.updatedAt(), DrainReason.OPERATOR_DRAIN);
            case DECOMMISSIONED -> MembershipFsmState.decommissioned(peer, value.updatedAt());
            case FAILED_DRAIN -> MembershipFsmState.failedDrain(peer, value.updatedAt());
            case SHUTTING_DOWN -> MembershipFsmState.draining(peer, value.updatedAt(), DrainReason.OPERATOR_DRAIN);
        };
    }

    private static Option<MembershipFsmEvent> translate(SwimObservation observation) {
        var nowMs = System.currentTimeMillis();
        return switch (observation) {
            case HealthyObserved h -> some(new SwimHealthy(h.peer(), h.incarnation(), nowMs));
            case FaultyObserved f -> some(new SwimFaulty(f.peer(), f.incarnation(), nowMs));
            case DepartedObserved d -> some(new SwimDeparted(d.peer(), d.incarnation(), nowMs));
            case SuspectObserved _ -> none();
            case UnknownObserved _ -> none();
        };
    }

    private void logFsmOutcome(MembershipFsmEvent event,
                                  MembershipFsmState priorState,
                                  Outcome outcome) {
        log.info("MembershipFsm: event={} peer={} priorState={} → newState={} would-write={} would-effect={}",
                 event.getClass().getSimpleName(),
                 event.peer().id(),
                 describe(priorState),
                 describe(outcome.newState()),
                 outcome.writes(),
                 outcome.effects());
    }

    private void logOperatorOutcome(MembershipFsmEvent event,
                                    MembershipFsmState priorState,
                                    Outcome outcome,
                                    boolean writesApplied) {
        log.info("MembershipFsm: event={} peer={} priorState={} → newState={} writes={} effects={} applied={}",
                 event.getClass().getSimpleName(),
                 event.peer().id(),
                 describe(priorState),
                 describe(outcome.newState()),
                 outcome.writes().size(),
                 outcome.effects().size(),
                 writesApplied);
    }

    private void logExternalLifecycleChange(NodeId peer,
                                            MembershipFsmState previous,
                                            MembershipFsmState newState,
                                            NodeLifecycleState lifecycleState) {
        log.info("MembershipFsm: external KV write peer={} priorState={} newState={} lifecycle={}",
                 peer.id(),
                 describe(previous),
                 describe(newState),
                 lifecycleState);
    }

    private static String describe(MembershipFsmState state) {
        if (state == null) {
            return "<absent/UNTRACKED>";
        }
        return state.getClass().getSimpleName();
    }

    private record TimerHandle(NodeId peer, TimerKind kind) {}

    /// Snapshot reader for `NodeLifecycleKey → NodeLifecycleValue` entries. Implemented by
    /// the wiring layer over `KVStore.forEach(...)`. Kept as a `@FunctionalInterface` so the
    /// shadow has no compile-time dependency on `KVStore` and remains testable with a
    /// hand-rolled fake.
    @FunctionalInterface
    public interface LifecycleSnapshotReader {
        @Contract void forEachLifecycle(BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer);
    }

    /// Snapshot reader for `ProvisioningSlotKey → ProvisioningSlotValue` entries.
    @FunctionalInterface
    public interface SlotSnapshotReader {
        @Contract void forEachSlot(BiConsumer<ProvisioningSlotKey, ProvisioningSlotValue> consumer);
    }

    /// One-shot timer scheduler. Returns a `ScheduledFuture` so the FSM can cancel pending
    /// timers when the reducer emits a `CancelTimer` effect (spec §10.2). The default
    /// implementation delegates to `SharedScheduler`; tests inject a fake scheduler.
    @FunctionalInterface
    public interface TimerScheduler {
        ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay);
    }

    private static TimerScheduler defaultScheduler() {
        return SharedScheduler::schedule;
    }

    private static final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> NO_OP_COMMAND_APPLIER =
            MembershipFsm::noOpCommandApply;

    private static Promise<List<Object>> noOpCommandApply(List<KVCommand<AetherKey>> commands) {
        log.debug("MembershipFsm: no-op command applier — {} write(s) discarded", commands.size());
        return Promise.success(List.of());
    }

    private static final DrainCoordinator NO_OP_DRAIN_COORDINATOR = new DrainCoordinator() {
        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            log.debug("MembershipFsm: no-op DrainCoordinator.prepareDrain({}, {})", nodeId.id(), reason);
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            return Promise.unitPromise();
        }

        @Override @Contract public void markDrainComplete(NodeId nodeId) {
            log.debug("MembershipFsm: no-op DrainCoordinator.markDrainComplete({})", nodeId.id());
        }
    };

    private static final BooleanSupplier NEVER_LEADER = () -> false;

    /// F.4 default predicate for test-only factories. Returns `false` for every peer so that
    /// `onPeerConnected` is inert unless callers explicitly opt in via the production
    /// 9-arg factory. See `isKnownAliveClusterPeer` field doc.
    private static final Predicate<NodeId> REJECT_ALL_PEERS = _ -> false;
}
