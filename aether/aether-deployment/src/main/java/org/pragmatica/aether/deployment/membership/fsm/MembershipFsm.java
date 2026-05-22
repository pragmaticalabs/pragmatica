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
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.TransportReachable;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.TransportUnreachable;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
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
import org.pragmatica.swim.SwimObservation.JoinAnnounced;
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
import java.util.function.Supplier;

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

    /// RC1 Step 4 — per-node Hybrid Logical Clock. Used at every event-creation site to stamp
    /// the event's `at` field, and to merge cross-node HLC values that arrive via KV-put
    /// notifications carrying remote `transitionedAt`. See `mergeRemoteHlcOrDrop` for the
    /// drift policy.
    private final HlcClock hlcClock;

    /// Topology-observation refactor Step 4 — aggregator-snapshot supplier. Consulted at every
    /// `reducer.apply(...)` call site to build a per-event `ReachabilityGate`. The supplier is
    /// expected to be cheap (the aggregator returns a pre-built snapshot reference); the gate
    /// itself reads `snapshot.states().get(peer)` once per consultation. Cold-start fallback:
    /// when the supplier returns `Option.none()`, the gate returns `true` (permissive) so the
    /// pre-Step-4 behavior is preserved before the first snapshot is produced.
    private final Supplier<Option<AggregatedReachabilitySnapshot>> aggregatorSnapshotSupplier;

    private final ReentrantLock fsmLock = new ReentrantLock();

    private final Map<NodeId, MembershipFsmState> fsmStates = new ConcurrentHashMap<>();

    /// Per-peer last-seen `NodeLifecycleValue` (KV-write or KV-notification). Used to
    /// preserve host/port/observedCoreEpoch/transitionedAt/provisioningSource when the
    /// reducer emits a state-only `Put` (spec F18 — non-state fields must survive
    /// transitions). The reducer is pure and emits minimal values; the wiring layer
    /// rewrites the value before consensus apply.
    private final Map<NodeId, NodeLifecycleValue> priorLifecycle = new ConcurrentHashMap<>();

    /// RC1 Step 3 — per-peer monotone-incarnation gate (topology-rc1-spec §3.2; closes
    /// N10 stale-arrival, SM2 ordering inversion, same-NodeId restart class). The map
    /// is leader-local and rebuilds on takeover — incarnation is a property of the
    /// SWIM event stream, not the FSM state, so cold-start reads KV → reconstructs FSM
    /// → map starts empty and reseeds on the first accepted event. A stale event during
    /// the cold window cannot damage state because the FSM has not yet started
    /// accepting work. NOT carried in `MembershipFsmState` (avoids a snapshot schema
    /// change for cluster-decision-stream state). Pruned on `(*, DECOMMISSIONED)` peer
    /// transitions (terminal cell) to bound growth.
    private final Map<NodeId, Long> latestObservedIncarnation = new ConcurrentHashMap<>();

    private final Map<String, NodeId> slotIdToPeer = new ConcurrentHashMap<>();

    // H.4 (spec §H): `swimDecommissionTombstones` map removed. The chaos-revival-storm
    // defence is no longer needed — `MembershipView` derives ON_DUTY from SWIM directly,
    // so there is no `(UNTRACKED, SwimHealthy) → ON_DUTY` write path to defend against
    // and no post-GC resurrection vector.

    private final Map<TimerHandle, ScheduledFuture<?>> pendingTimers = new ConcurrentHashMap<>();

    private final AtomicBoolean started = new AtomicBoolean();

    private volatile Function<NodeId, Boolean> swimHealthGate = ignored -> false;

    private MembershipFsm(NodeId self,
                          MembershipFsmConfig config,
                          ClusterMembershipReducer reducer,
                          LifecycleSnapshotReader lifecycleSnapshotReader,
                          SlotSnapshotReader slotSnapshotReader,
                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                          DrainCoordinator drainCoordinator,
                          TimerScheduler scheduler,
                          BooleanSupplier isLeader,
                          HlcClock hlcClock,
                          Supplier<Option<AggregatedReachabilitySnapshot>> aggregatorSnapshotSupplier) {
        this.self = self;
        this.config = config;
        this.reducer = reducer;
        this.lifecycleSnapshotReader = lifecycleSnapshotReader;
        this.slotSnapshotReader = slotSnapshotReader;
        this.commandApplier = commandApplier;
        this.drainCoordinator = drainCoordinator;
        this.scheduler = scheduler;
        this.isLeader = isLeader;
        this.hlcClock = hlcClock;
        this.aggregatorSnapshotSupplier = aggregatorSnapshotSupplier;
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
                             NEVER_LEADER,
                             defaultHlcClock(self),
                             NO_AGGREGATOR_SNAPSHOT);
    }

    /// Write-capable factory. When `isLeader.getAsBoolean()` returns `true`, operator events
    /// route through the FSM: proposed via `commandApplier`, drain effects dispatched to
    /// `drainCoordinator`, timers scheduled via `scheduler`.
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
                             defaultHlcClock(self),
                             NO_AGGREGATOR_SNAPSHOT);
    }

    /// RC1 Step 4 — production factory with explicit `HlcClock`. The FSM shares the node-wide
    /// HLC instance with other subsystems (generation snapshots, governor announcer, etc.) —
    /// every emitted FSM event is causally ordered against every other HLC-stamped action of
    /// this node. Defaults the aggregator-snapshot supplier to a cold-start (`Option.none()`)
    /// stub — call the supplier-accepting overload from production wiring.
    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader,
                                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                              DrainCoordinator drainCoordinator,
                                              TimerScheduler scheduler,
                                              BooleanSupplier isLeader,
                                              HlcClock hlcClock) {
        return membershipFsm(self,
                             config,
                             lifecycleSnapshotReader,
                             slotSnapshotReader,
                             commandApplier,
                             drainCoordinator,
                             scheduler,
                             isLeader,
                             hlcClock,
                             NO_AGGREGATOR_SNAPSHOT);
    }

    /// Topology-observation refactor Step 4 — full production factory accepting an aggregator-
    /// snapshot supplier. Each reducer call site builds a per-event `ReachabilityGate` from the
    /// current snapshot (cold-start fallback returns a permissive gate). Production wiring
    /// (`AetherNode.buildMembershipFsm`) passes `reachabilityAggregator::snapshot`.
    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader,
                                              Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                              DrainCoordinator drainCoordinator,
                                              TimerScheduler scheduler,
                                              BooleanSupplier isLeader,
                                              HlcClock hlcClock,
                                              Supplier<Option<AggregatedReachabilitySnapshot>> aggregatorSnapshotSupplier) {
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
                                 hlcClock,
                                 aggregatorSnapshotSupplier);
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
                                 defaultHlcClock(self),
                                 NO_AGGREGATOR_SNAPSHOT);
    }

    /// Custom-reducer + custom-clock factory (test-only — lets adversarial tests inject a
    /// `HlcClock` over a fake physical clock to exercise monotonicity and drift policy).
    public static MembershipFsm membershipFsm(NodeId self,
                                              MembershipFsmConfig config,
                                              ClusterMembershipReducer reducer,
                                              LifecycleSnapshotReader lifecycleSnapshotReader,
                                              SlotSnapshotReader slotSnapshotReader,
                                              HlcClock hlcClock) {
        return new MembershipFsm(self,
                                 config,
                                 reducer,
                                 lifecycleSnapshotReader,
                                 slotSnapshotReader,
                                 NO_OP_COMMAND_APPLIER,
                                 NO_OP_DRAIN_COORDINATOR,
                                 defaultScheduler(),
                                 NEVER_LEADER,
                                 hlcClock,
                                 NO_AGGREGATOR_SNAPSHOT);
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
        if (!admitIncarnationOrDrop(observation)) {
            return;
        }
        translate(observation).onPresent(this::processOperatorOrFsmEvent);
    }

    /// Topology-observation refactor Step 3: ingest a `ReachabilityAggregator` snapshot and
    /// fan it out as per-peer `TransportReachable` / `TransportUnreachable` events.
    ///
    /// **Idempotence.** No edge-dedup cache is maintained here. Duplicate snapshots produce
    /// duplicate events; the reducer absorbs them as nop cells (see Step 2 reducer for the
    /// 14 transport cells — most are explicit `nop`, ON_DUTY+TransportUnreachable produces
    /// the same `DECOMMISSIONED` write each time but consensus is naturally idempotent on
    /// an already-DECOMMISSIONED lifecycle).
    ///
    /// **Leader gate (single-writer rule).** Only the leader's FSM dispatches transport
    /// events; followers drop the snapshot (TRACE log) — they receive the resulting
    /// lifecycle transitions via the `NodeLifecycleKey` KV notification path. This mirrors
    /// `onSwimObservation`'s leader gate.
    ///
    /// **UNKNOWN suppression.** Peers with `ReachabilityKind.UNKNOWN` are intentionally not
    /// translated to events — UNKNOWN means "no quorum either way" and must not produce a
    /// state transition. Step 4 will refine REACHABLE/UNREACHABLE gating with aggregator-
    /// quorum semantics; Step 3 fires the events unconditionally on quorum-derived kinds.
    @Contract public void onTransportSnapshot(AggregatedReachabilitySnapshot snapshot) {
        if (!started.get()) {
            return;
        }
        if (!isLeader.getAsBoolean()) {
            logTransportDropOnFollower(snapshot);
            return;
        }
        var now = hlcClock.now();
        snapshot.states().forEach((peer, state) -> dispatchTransportEvent(peer, state.kind(), now));
    }

    private void dispatchTransportEvent(NodeId peer, ReachabilityKind kind, HlcTimestamp now) {
        switch (kind) {
            case REACHABLE -> enqueueOperatorEvent(new TransportReachable(peer, now));
            case UNREACHABLE -> enqueueOperatorEvent(new TransportUnreachable(peer, now));
            case UNKNOWN -> { /* suppress — quorum-undecided, no event */ }
        }
    }

    /// RC1 Step 3 — monotone-incarnation gate (topology-rc1-spec §3.2). Returns `true`
    /// to admit, `false` to drop the observation.
    ///
    /// **Admit:** `incarnation > stored`. Updates the map.
    ///
    /// **Restart-reset:** `incarnation == 0` is treated as a legitimate peer-restart
    /// reset (SWIM-protocol semantics — a process that restarts with a cleared counter
    /// emits incarnation 0). Resets the map entry to 0 so the next observation from
    /// the restarted peer's fresh stream is accepted from the beginning.
    ///
    /// **Drop:** `incarnation < stored` is stale (event-ordering inversion from a
    /// slower observer). Logged at DEBUG.
    ///
    /// **Tie:** `incarnation == stored && stored != 0` is admitted (re-confirmation
    /// of the same generation — harmless; the reducer cell handles idempotence).
    ///
    /// Suspect/Unknown observations are not gated — they translate to `Option.none()`
    /// in `translate()` and never reach the reducer.
    private boolean admitIncarnationOrDrop(SwimObservation observation) {
        if (!(observation instanceof HealthyObserved || observation instanceof FaultyObserved || observation instanceof DepartedObserved)) {
            return true;
        }
        var peer = observation.peer();
        var incoming = observation.incarnation();
        var stored = latestObservedIncarnation.getOrDefault(peer, 0L);
        if (incoming == 0L) {
            latestObservedIncarnation.put(peer, 0L);
            log.debug("MembershipFsm: incarnation==0 restart-reset for peer={} (prior stored={}) — admitted, map reset",
                      peer.id(),
                      stored);
            return true;
        }
        if (incoming < stored) {
            log.debug("MembershipFsm: dropping stale SWIM observation {} for peer={} (incoming={} < stored={})",
                      observation.getClass().getSimpleName(),
                      peer.id(),
                      incoming,
                      stored);
            return false;
        }
        latestObservedIncarnation.put(peer, incoming);
        return true;
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

    @Contract public void setSwimHealthGate(Function<NodeId, Boolean> gate) {
        this.swimHealthGate = gate;
        // Re-evaluate JOINING peers immediately — handles the common wiring order where
        // AetherNode calls setSwimHealthGate after start(), so resumeJoinDeadline already
        // ran with the default false-gate and skipped synthesis for SWIM-healthy peers.
        if (started.get() && isLeader.getAsBoolean()) {
            fsmStates.forEach((peer, state) -> {
                if (state instanceof MembershipFsmState.Joining && gate.apply(peer)) {
                    log.info("MembershipFsm: setSwimHealthGate — SWIM already healthy for JOINING peer={}, synthesizing SwimHealthy",
                             peer.id());
                    onSwimObservation(new HealthyObserved(peer, 0L));
                }
            });
        }
    }

    private boolean isSelfAlreadyOnDuty() {
        var current = fsmStates.get(self);
        return current instanceof MembershipFsmState.OnDuty;
    }

    /// RC1 Step 4 — HLC drift policy site. Cross-node KV-put notifications carry the
    /// originator's HLC in `NodeLifecycleValue.transitionedAt`; merging that into the local
    /// clock both advances local causal time and detects pathological drift. Policy:
    /// `HlcClock.update` fails when `remote.physicalMicros - localPhysicalMicros > 500ms`;
    /// on failure we WARN-log peer + remote + local timestamps and DROP the event. We do
    /// NOT silently accept (would poison local ordering) and we do NOT throw (would crash
    /// the KV-notification dispatcher). Sentinel `HlcTimestamp.ZERO` means the value was
    /// minted by a pre-RC1 path or a writer that did not stamp the HLC — skip the merge
    /// and admit the put unchanged.
    private boolean mergeRemoteHlcOrDrop(NodeId peer, HlcTimestamp remote) {
        if (HlcTimestamp.ZERO.equals(remote)) {
            return true;
        }
        var merge = hlcClock.update(remote);
        if (merge.isFailure()) {
            merge.onFailure(cause -> log.warn(
                    "MembershipFsm: dropping NodeLifecyclePut from peer={} — HLC drift exceeds threshold: {} (remote={}, local={})",
                    peer.id(),
                    cause.message(),
                    remote,
                    hlcClock.peek()));
            return false;
        }
        return true;
    }

    private void logSwimDropOnFollower(SwimObservation observation) {
        if (log.isTraceEnabled()) {
            log.trace("MembershipFsm: SWIM observation {} dropped on follower {} (spec §6.1 — followers learn via KV notifications)",
                      observation.getClass().getSimpleName(),
                      self.id());
        }
    }

    private void logTransportDropOnFollower(AggregatedReachabilitySnapshot snapshot) {
        if (log.isTraceEnabled()) {
            log.trace("MembershipFsm: transport snapshot (states={}) dropped on follower {} (spec §6.1 — single-writer; followers learn via KV notifications)",
                      snapshot.states().size(),
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
    ///
    /// **RC1 Step 4 — HLC drift gate.** The KV value carries the originating node's HLC in
    /// `transitionedAt`. Before applying the update, the local clock is advanced via
    /// `mergeRemoteHlcOrDrop`. If the remote HLC exceeds the configured drift threshold
    /// (default 500ms), the put is DROPPED and a WARN is logged — preventing a drifting peer
    /// from polluting local causal ordering. Sentinel `HlcTimestamp.ZERO` (pre-RC1 entries,
    /// or values minted by a path that hasn't been migrated yet) is treated as "no remote HLC"
    /// and the put is admitted without a clock merge.
    @Contract public void onNodeLifecyclePut(ValuePut<NodeLifecycleKey, NodeLifecycleValue> put) {
        if (!started.get()) {
            return;
        }
        var peer = put.cause().key().nodeId();
        var value = put.cause().value();
        if (!mergeRemoteHlcOrDrop(peer, value.transitionedAt())) {
            return;
        }
        applyExternalLifecyclePut(peer, value);
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
    /// **Topology-observation refactor Step 3 (transport events):** `TransportUnreachable`
    /// can produce consensus writes (`(JOINING, TransportUnreachable) → DECOMMISSIONED`,
    /// `(ON_DUTY, TransportUnreachable) → DECOMMISSIONED`) so it MUST flow through the
    /// leader-writing path. `TransportReachable` is intentionally absent — all of its 7
    /// reducer cells are `Outcome.nop` in Step 2, so it requires no leader gate and is
    /// classified as shadow-only. If Step 4/5 introduces a `TransportReachable` write cell,
    /// add it here.
    ///
    /// `SlotClaimed` IS a leader-writing event. CTM wrote the `ProvisioningSlotKey` atom, but
    /// the lifecycle transition `(Untracked, SlotClaimed) → Joining` emitted by
    /// `ClusterMembershipReducer.enterJoining` produces a SEPARATE `Put(NodeLifecycleKey,
    /// JOINING)` write plus a `ScheduleTimer(JOIN_DEADLINE)` effect — both must flow through
    /// `proposeWritesAndApply` and `applyEffectsLocked`. Misclassifying SlotClaimed as
    /// shadow-only silently dropped the JOINING write, leaving CTM-provisioned replacements
    /// with no `NodeLifecycleKey` atom in KV and no join-deadline tombstone path — observed
    /// 2026-05-22 as the dominant failure mode of suite 02-chaos / test-joining-window-kill.sh.
    private static boolean isLeaderWritingEvent(MembershipFsmEvent event) {
        return event instanceof OperatorDrain
               || event instanceof OperatorDecommission
               || event instanceof DrainOutcome
               || event instanceof JoinDeadlineExpired
               || event instanceof SwimFaulty
               || event instanceof SwimDeparted
               || event instanceof SwimHealthy
               || event instanceof TransportUnreachable
               || event instanceof SlotClaimed;
    }

    private void applyExternalLifecyclePut(NodeId peer, NodeLifecycleValue value) {
        fsmLock.lock();
        try {
            priorLifecycle.put(peer, value);
            var newState = deriveStateFromLifecycle(peer, value, slotIdForPeer(peer));
            var previous = fsmStates.put(peer, newState);
            pruneIncarnationIfDecommissioned(peer, newState);
            logExternalLifecycleChange(peer, previous, newState, value.state());
        } finally {
            fsmLock.unlock();
        }
    }

    /// RC1 Step 3 — prune the incarnation map entry on terminal `Decommissioned`
    /// transitions to bound map growth across long-running clusters with churning
    /// peer IDs. Called from both consensus-write success and KV-notification paths
    /// so leader-takeover and follower-side observation converge to the same pruned
    /// state.
    private void pruneIncarnationIfDecommissioned(NodeId peer, MembershipFsmState newState) {
        if (newState instanceof MembershipFsmState.Decommissioned) {
            var removed = latestObservedIncarnation.remove(peer);
            if (removed != null && log.isDebugEnabled()) {
                log.debug("MembershipFsm: pruned incarnation entry for decommissioned peer={} (stored was {})",
                          peer.id(),
                          removed);
            }
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
            // SlotClaimed is a leader-writing event (see isLeaderWritingEvent Javadoc) — route
            // through processOperatorEventLocked so the reducer's `Put(NodeLifecycleKey, JOINING)`
            // write reaches consensus and the `ScheduleTimer(JOIN_DEADLINE)` effect actually
            // schedules. The prior `processFsmEventLocked` call updated the in-memory state map
            // only, silently discarding the write and the timer — the symptom was CTM-provisioned
            // replacements never appearing in `/api/nodes/lifecycle/<id>` and never being
            // tombstoned when they failed to bootstrap.
            processOperatorEventLocked(new SlotClaimed(peer, slotId, hlcClock.now()));
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
        var outcome = reducer.apply(current, event, currentReachabilityGate());
        fsmStates.put(peer, outcome.newState());
        // Symmetry with processOperatorEventLocked: apply effects even on the shadow path so a
        // reducer-emitted ScheduleTimer or EmitDomainEvent doesn't silently disappear. Writes
        // ARE still dropped here — that's the contract of "shadow-only" — but flag any
        // unexpected write as a misclassification rather than swallow it silently (this is the
        // exact bear-trap that hid the SlotClaimed JOINING-write loss; see 2026-05-22 fix).
        if (!outcome.writes().isEmpty()) {
            log.warn("MembershipFsm: shadow-only event {} for {} produced {} write(s) that will not be proposed — "
                     + "the event is likely misclassified (see isLeaderWritingEvent)",
                     event.getClass().getSimpleName(),
                     peer.id(),
                     outcome.writes().size());
        }
        applyEffectsLocked(outcome.effects());
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
        var outcome = reducer.apply(current, event, currentReachabilityGate());
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
            var newState = outcome.newState();
            fsmStates.put(newState.peer(), newState);
            pruneIncarnationIfDecommissioned(newState.peer(), newState);
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
            enqueueOperatorEvent(new JoinDeadlineExpired(handle.peer(), hlcClock.now()));
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
        enqueueOperatorEvent(new DrainOutcome(peer, false, hlcClock.now()));
    }

    /// Chains `DrainCoordinator.awaitDrainAck(peer, timeout)` and translates its resolution
    /// back into a `DrainOutcome` event (spec §8.2, F4). Success → `DrainOutcome(true)` →
    /// `(DRAINING, DrainOutcome(true)) → DECOMMISSIONED`. Failure (or hard-deadline) →
    /// `DrainOutcome(false)` → `(DRAINING, DrainOutcome(false)) → FAILED_DRAIN`.
    @Contract private void awaitDrainAndFeedback(NodeId peer, TimeSpan timeout) {
        log.debug("MembershipFsm: awaiting drain ack for {} (timeout={}ms)", peer.id(), timeout.millis());
        drainCoordinator.awaitDrainAck(peer, timeout)
                         .onSuccess(_ -> enqueueOperatorEvent(new DrainOutcome(peer, true, hlcClock.now())))
                         .onFailure(cause -> onAwaitDrainAckFailure(peer, cause));
    }

    private void onAwaitDrainAckFailure(NodeId peer, Cause cause) {
        log.warn("MembershipFsm: awaitDrainAck failed for {}: {} — feeding DrainOutcome(false)",
                 peer.id(),
                 cause.message());
        enqueueOperatorEvent(new DrainOutcome(peer, false, hlcClock.now()));
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
        latestObservedIncarnation.clear();
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
            enqueueOperatorEvent(new DrainOutcome(peer, false, hlcClock.now()));
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
            enqueueOperatorEvent(new JoinDeadlineExpired(peer, hlcClock.now()));
            return;
        }
        log.info("MembershipFsm: resumeJoinDeadline peer={} remaining={}ms — scheduling timer",
                 peer.id(), remainingMs);
        var handle = new TimerHandle(peer, TimerKind.JOIN_DEADLINE);
        cancelTimer(peer, TimerKind.JOIN_DEADLINE);
        var future = scheduler.schedule(() -> onTimerFired(handle), TimeSpan.timeSpan(remainingMs).millis());
        pendingTimers.put(handle, future);
        if (swimHealthGate.apply(peer)) {
            log.info("MembershipFsm: resumeJoinDeadline peer={} — SWIM already healthy on leader takeover, synthesizing SwimHealthy",
                     peer.id());
            onSwimObservation(new HealthyObserved(peer, 0L));
        }
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
            case DECOMMISSIONED -> MembershipFsmState.decommissioned(peer, value.updatedAt(), true);
            case FAILED_DRAIN -> MembershipFsmState.failedDrain(peer, value.updatedAt());
            case SHUTTING_DOWN -> MembershipFsmState.draining(peer, value.updatedAt(), DrainReason.OPERATOR_DRAIN);
        };
    }

    /// RC1 Step 4 — translates SWIM observations into FSM events stamped with the local HLC.
    /// `SwimObservation` does not currently carry a wire-side HLC (Step 6 future work), so the
    /// observation is treated as a local event and the clock advances via `hlcClock.now()`.
    /// When `SwimObservation` gains an `at` field, this site will switch to
    /// `mergeRemoteHlcOrDrop(observation.at())` so cross-node SWIM gossip advances the local
    /// clock and is gated by the drift policy.
    private Option<MembershipFsmEvent> translate(SwimObservation observation) {
        var at = hlcClock.now();
        return switch (observation) {
            case HealthyObserved h -> some(new SwimHealthy(h.peer(), h.incarnation(), at));
            case FaultyObserved f -> some(new SwimFaulty(f.peer(), f.incarnation(), at));
            case DepartedObserved d -> some(new SwimDeparted(d.peer(), d.incarnation(), at));
            case SuspectObserved _ -> none();
            case UnknownObserved _ -> none();
            case JoinAnnounced _ -> none();
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

    /// Cold-start default for test factories: no aggregator snapshot available. The reducer's
    /// `(ON_DUTY, SwimFaulty)` and `(ON_DUTY, TransportUnreachable)` cells fall back to the
    /// permissive (pre-Step-4) behavior when the supplier returns `Option.none()`.
    private static final Supplier<Option<AggregatedReachabilitySnapshot>> NO_AGGREGATOR_SNAPSHOT = Option::none;

    /// Topology-observation refactor Step 4 — build a per-event `ReachabilityGate` from the
    /// current aggregator snapshot. Returns a permissive gate (always `true`) when the supplier
    /// reports `Option.none()` (cold start, no snapshot yet — preserves pre-Step-4 behavior).
    /// Otherwise returns `true` only when the snapshot entry for `peer` is UNREACHABLE; REACHABLE
    /// and UNKNOWN both gate to `false` (a confirmed cluster-wide failure is required).
    private ReachabilityGate currentReachabilityGate() {
        var snapshotOpt = aggregatorSnapshotSupplier.get();
        return snapshotOpt.fold(() -> ReachabilityGate.ALWAYS_CONFIRMED,
                                 snapshot -> peer -> isUnreachableInSnapshot(snapshot, peer));
    }

    private static boolean isUnreachableInSnapshot(AggregatedReachabilitySnapshot snapshot, NodeId peer) {
        var entry = snapshot.states().get(peer);
        return entry != null && entry.kind() == ReachabilityKind.UNREACHABLE;
    }

    /// RC1 Step 4 — default per-node `HlcClock` for test-only factories that do not accept an
    /// explicit clock. Production wiring (`AetherNode.buildMembershipFsm`) MUST pass the node's
    /// canonical clock instance so HLC values are shared across subsystems.
    private static HlcClock defaultHlcClock(NodeId self) {
        return HlcClock.hlcClock(self.id()).unwrap();
    }
}
