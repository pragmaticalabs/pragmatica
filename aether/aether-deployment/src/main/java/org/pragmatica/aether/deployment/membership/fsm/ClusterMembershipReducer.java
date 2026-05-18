// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.CancelDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.CancelTimer;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.EmitDomainEvent;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.InvokeDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.MembershipDomainEvent;
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
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Decommissioned;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Draining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.FailedDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Joining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Provisioning;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Untracked;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;


/// Pure per-peer cluster-membership reducer (spec §5, 7 states × 10 events = 70 cells).
///
/// `apply(state, event)` is a total function: every cell is explicit. `err`-marked cells
/// (unreachable by construction) throw `IllegalStateException` to surface bugs rather than
/// silently swallow. `nop` cells return `Outcome(state, [], [])`.
///
/// The reducer performs no I/O. Effects are returned as `MembershipEffect` descriptors;
/// the wiring layer (E.3/E.4) translates them into scheduler / `DrainCoordinator` /
/// event-bus calls (I7).
///
/// **RC1 Step 4 — HLC.** Every event carries an `HlcTimestamp at`. The reducer derives
/// both the KV `updatedAt` (wall-clock millis from the HLC's physical micros component) and
/// the `transitionedAt` (the HLC itself) from this single source — so consensus-replicated
/// `NodeLifecycleValue` writes carry the originator's HLC for cross-node causal ordering.
///
/// **I2 assumption (caller-enforced).** Only the current leader should invoke `apply`.
/// The reducer does not check leadership — that is the wiring layer's job.
public record ClusterMembershipReducer(MembershipFsmConfig config) {
    public record Outcome(MembershipFsmState newState,
                          List<KVCommand<AetherKey>> writes,
                          List<MembershipEffect> effects) {
        public static Outcome outcome(MembershipFsmState newState,
                                      List<KVCommand<AetherKey>> writes,
                                      List<MembershipEffect> effects) {
            return new Outcome(newState, List.copyOf(writes), List.copyOf(effects));
        }

        public static Outcome nop(MembershipFsmState state) {
            return new Outcome(state, List.of(), List.of());
        }
    }

    public static ClusterMembershipReducer clusterMembershipReducer(MembershipFsmConfig config) {
        return new ClusterMembershipReducer(config);
    }

    /// Topology-observation refactor Step 4 — `gate` is consulted only by the two ON_DUTY
    /// decommission cells (`SwimFaulty` and `TransportUnreachable`). All other cells ignore
    /// `gate` and behave identically to the pre-Step-4 reducer. Callers that do not need
    /// aggregator-quorum gating may pass `ReachabilityGate.ALWAYS_CONFIRMED`.
    public Outcome apply(MembershipFsmState state, MembershipFsmEvent event, ReachabilityGate gate) {
        return switch (state){
            case Untracked s -> applyUntracked(s, event);
            case Provisioning s -> applyProvisioning(s, event);
            case Joining s -> applyJoining(s, event);
            case OnDuty s -> applyOnDuty(s, event, gate);
            case Draining s -> applyDraining(s, event);
            case Decommissioned s -> applyDecommissioned(s, event);
            case FailedDrain s -> applyFailedDrain(s, event);
        };
    }

    private Outcome applyUntracked(Untracked state, MembershipFsmEvent event) {
        return switch (event){
            // H.3 partial revert (2026-05-13): restored `(UNTRACKED, SwimHealthy) → ON_DUTY`
            // write so downstream consumers reading `NodeLifecycleKey` directly continue to
            // function. `MembershipView` remains the canonical reader (returns the same
            // answer either via KV or via SWIM-only derivation). The chaos-revival storm fix
            // (H.3 + H.4) is preserved by the eliminated `(DECOMMISSIONED, SwimHealthy)`
            // revival cell — that's what produced the bug, not this UNTRACKED→ON_DUTY path.
            case SwimHealthy e -> untrackedDirectToOnDuty(state.peer(), e.at());
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted _ -> Outcome.nop(state);
            case SlotClaimed e -> enterJoining(state.peer(),
                                               Option.some(e.slotId()),
                                               e.at());
            case OperatorDrain _ -> Outcome.nop(state);
            case OperatorDecommission e -> untrackedOperatorDecommission(state, e);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            // Topology-observation refactor Step 2: transport events have no effect on an
            // untracked peer (we have no lifecycle state to advance/withdraw).
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    private Outcome applyProvisioning(Provisioning state, MembershipFsmEvent event) {
        return switch (event){
            case SwimHealthy _ -> illegal(state, event);
            case SwimFaulty _ -> illegal(state, event);
            case SwimDeparted _ -> illegal(state, event);
            case SlotClaimed e -> enterJoining(state.peer(),
                                               Option.some(e.slotId()),
                                               e.at());
            case OperatorDrain _ -> illegal(state, event);
            case OperatorDecommission _ -> illegal(state, event);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            // Topology-observation refactor Step 2: provisioning is a leader-side bookkeeping
            // phase before any peer has actually started — transport observations of the
            // not-yet-online slot are noise; the slot lifecycle (SlotClaimed / timeout) owns it.
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    private Outcome applyJoining(Joining state, MembershipFsmEvent event) {
        return switch (event){
            // H.3 partial revert (2026-05-13): JOINING transitions retained. SWIM-departed
            // during JOINING writes DECOMMISSIONED + clears slot (operator-aware path —
            // CTM accounting depends on this).
            case SwimHealthy e -> joiningToOnDuty(state, e.at());
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted e -> joiningToDecommissioned(state, e.at(), REASON_SWIM_DEPARTED);
            case SlotClaimed _ -> Outcome.nop(state);
            case OperatorDrain e -> enterDraining(state.peer(), e.reason(), e.at());
            case OperatorDecommission e -> joiningOperatorDecommission(state, e);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired e -> joiningToDecommissioned(state, e.at(), REASON_JOIN_TIMEOUT);
            // Topology-observation refactor Step 2: a transport-reachable observation during
            // JOINING is confirmation only — the SwimHealthy cell drives the promotion.
            case TransportReachable _ -> Outcome.nop(state);
            // S01 (JOINING-window kill): transport disconnect is the fast detection path;
            // SWIM may take 10-15s to converge. Reuse `joiningToDecommissioned` so the
            // slot is cleared atomically with the lifecycle write.
            case TransportUnreachable e -> joiningToDecommissioned(state, e.at(), REASON_TRANSPORT_FAILURE);
        };
    }

    private Outcome applyOnDuty(OnDuty state, MembershipFsmEvent event, ReachabilityGate gate) {
        return switch (event){
            case SwimHealthy _ -> Outcome.nop(state);
            // H.3 partial revert (2026-05-13): restored `(OnDuty, SwimFaulty) → DECOMMISSIONED`
            // write so the legacy `ClusterDeploymentManager` / slice-deployment / dashboard
            // consumers reading KV directly continue to see the failed peer drop out. The
            // chaos revival storm cure stays — it lives in `(DECOMMISSIONED, SwimHealthy)`
            // being nop (no revival), not in suppressing the initial decommission write.
            //
            // Step 4 (spec §16 S04/S13/S17): aggregator-quorum gate. A SWIM-faulty observation
            // on an ON_DUTY peer is NOT sufficient to decommission unless the cluster-wide
            // ReachabilityAggregator also reports UNREACHABLE with quorum. Cold-start fallback
            // (no snapshot yet) returns `true` to preserve pre-Step-4 behavior.
            case SwimFaulty e -> gate.isConfirmedUnreachable(state.peer())
                                 ? onDutyToDecommissioned(state, e.at(), REASON_SWIM_FAULTY)
                                 : Outcome.nop(state);
            case SwimDeparted e -> onDutyToDecommissioned(state, e.at(), REASON_SWIM_DEPARTED);
            case SlotClaimed _ -> illegal(state, event);
            case OperatorDrain e -> enterDraining(state.peer(), e.reason(), e.at());
            case OperatorDecommission e -> onDutyOperatorDecommission(state, e);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            // Topology-observation refactor Step 2: TransportReachable on an ON_DUTY peer
            // re-confirms the existing state — no write needed (mirrors SwimHealthy nop).
            case TransportReachable _ -> Outcome.nop(state);
            // S02 / S14: transport disconnect on an ON_DUTY peer is the fast path to
            // DECOMMISSIONED (~1s vs SWIM ~10-15s).
            //
            // Step 4 (spec §16 S05/S17): aggregator-quorum gate. A single transport-unreachable
            // observation is NOT sufficient — confirmed by quorum or it's a partition seen by
            // a single observer (S05) and must be suppressed. Cold-start fallback returns
            // `true` (but transport events don't fire without a snapshot in practice).
            case TransportUnreachable e -> gate.isConfirmedUnreachable(state.peer())
                                           ? onDutyToDecommissioned(state, e.at(), REASON_TRANSPORT_FAILURE)
                                           : Outcome.nop(state);
        };
    }

    /// `@SuppressWarnings("JBCT-SEQ-01")`: the sealed-event switch has 10 arms after the
    /// topology-observation refactor (Step 2). The JBCT-SEQ-01 method-chain heuristic flags
    /// switches with many arms; here the count is dictated by the sealed-interface
    /// exhaustiveness contract (10 events × 7 states). Cannot be reduced without sacrificing
    /// per-cell explicitness.
    @SuppressWarnings("JBCT-SEQ-01")
    private Outcome applyDraining(Draining state, MembershipFsmEvent event) {
        return switch (event){
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted e -> drainingHardDeparted(state, e.at());
            case SlotClaimed _ -> illegal(state, event);
            case OperatorDrain _ -> Outcome.nop(state);
            case OperatorDecommission e -> drainingOperatorDecommission(state, e);
            case DrainOutcome e -> drainingDrainOutcome(state, e);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            // Topology-observation refactor Step 2: Draining is operator-owned and runs to
            // a terminal `DrainOutcome` (success → DECOMMISSIONED, failure → FAILED_DRAIN)
            // or hard-departs on `SwimDeparted`. Transport observations during drain are
            // ignored — the DrainCoordinator owns the timeline.
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    private Outcome applyDecommissioned(Decommissioned state, MembershipFsmEvent event) {
        return switch (event){
            // H.3 (spec §H): `(DECOMMISSIONED, SwimHealthy)` revival path is removed entirely.
            // Pre-H model carried this cell with refractory + tombstone defences to dampen
            // the revival storm — all obsolete now that SWIM is authoritative for "alive".
            // If a `DECOMMISSIONED` peer's container restarts and SWIM admits it again, the
            // operator must explicitly clear the KV entry (or wait for `DecommissionedAtomGc`
            // to age it out) before the peer rejoins as ON_DUTY through the view.
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted _ -> Outcome.nop(state);
            case SlotClaimed _ -> illegal(state, event);
            case OperatorDrain _ -> Outcome.nop(state);
            case OperatorDecommission _ -> Outcome.nop(state);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            // Topology-observation refactor Step 2: DECOMMISSIONED is terminal. Both
            // transport-reachable and transport-unreachable are nop to preserve the chaos-
            // revival defense (mirror of `SwimHealthy → nop` here). A peer's container that
            // briefly accepts QUIC again must NOT revive its lifecycle entry.
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    // H.4 (spec §H): `decommissionedSwimHealthy` revival path removed entirely. `MembershipView`
    // is now authoritative for "alive"; a `DECOMMISSIONED` KV entry is an operator-declared
    // override that does not get auto-revived by SWIM gossip.

    /// `@SuppressWarnings("JBCT-SEQ-01")`: see `applyDraining` rationale — 10-arm exhaustive
    /// switch is required by the sealed `MembershipFsmEvent` contract.
    @SuppressWarnings("JBCT-SEQ-01")
    private Outcome applyFailedDrain(FailedDrain state, MembershipFsmEvent event) {
        return switch (event){
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted e -> failedDrainToDecommissioned(state, e.at(), REASON_SWIM_DEPARTED);
            case SlotClaimed _ -> illegal(state, event);
            case OperatorDrain _ -> Outcome.nop(state);
            case OperatorDecommission e -> failedDrainToDecommissioned(state, e.at(), REASON_OPERATOR_OVERRIDE);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            // Topology-observation refactor Step 2: FAILED_DRAIN is operator-owned cleanup —
            // only `OperatorDecommission` or `SwimDeparted` move it forward. Transport
            // observations are ignored.
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    /// `(UNTRACKED, SwimHealthy) → ON_DUTY` direct. SWIM only emits an observation when a peer's
    /// state CHANGES — for SWIM-discovered peers we skip the intermediate JOINING state (which
    /// is reserved for slot-provisioning workflows). Pre-H this was load-bearing for KV ON_DUTY
    /// entries; post-H the `MembershipView` derived path returns the same answer either way,
    /// but the KV write is retained for legacy consumers that read `NodeLifecycleKey` directly.
    private Outcome untrackedDirectToOnDuty(NodeId peer, HlcTimestamp at) {
        var writes = singleWrite(putLifecycle(peer, NodeLifecycleState.ON_DUTY, at));
        var effects = List.<MembershipEffect>of(emit(peer, MembershipDomainEvent.NODE_ON_DUTY, REASON_NONE));
        return Outcome.outcome(MembershipFsmState.onDuty(peer, toMillis(at)), writes, effects);
    }

    private Outcome enterJoining(NodeId peer, Option<String> slotId, HlcTimestamp at) {
        var writes = singleWrite(putLifecycle(peer, NodeLifecycleState.JOINING, at));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(emit(peer, MembershipDomainEvent.NODE_JOINING, REASON_NONE));
        effects.add(new ScheduleTimer(peer, TimerKind.JOIN_DEADLINE, config.joinDeadline()));
        return Outcome.outcome(MembershipFsmState.joining(peer, toMillis(at), slotId), writes, effects);
    }

    private Outcome joiningToOnDuty(Joining state, HlcTimestamp at) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.ON_DUTY, at));
        state.slotId().onPresent(slotId -> writes.add(removeSlot(slotId)));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(state.peer(), TimerKind.JOIN_DEADLINE));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_ON_DUTY, REASON_NONE));
        return Outcome.outcome(MembershipFsmState.onDuty(state.peer(), toMillis(at)),
                               writes,
                               effects);
    }

    private Outcome joiningToDecommissioned(Joining state, HlcTimestamp at, String reason) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, at));
        state.slotId().onPresent(slotId -> writes.add(removeSlot(slotId)));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(state.peer(), TimerKind.JOIN_DEADLINE));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), toMillis(at), isSwimReason(reason)),
                               writes,
                               effects);
    }

    private Outcome joiningOperatorDecommission(Joining state, OperatorDecommission event) {
        return joiningToDecommissioned(state,
                                       event.at(),
                                       event.force()
                                       ? REASON_OPERATOR_FORCED
                                       : REASON_OPERATOR_DECOMMISSION);
    }

    private Outcome onDutyToDecommissioned(OnDuty state, HlcTimestamp at, String reason) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, at));
        var effects = List.<MembershipEffect>of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), toMillis(at), isSwimReason(reason)),
                               writes,
                               effects);
    }

    private Outcome onDutyOperatorDecommission(OnDuty state, OperatorDecommission event) {
        if (event.force()) {return onDutyToDecommissioned(state, event.at(), REASON_OPERATOR_FORCED);}
        return enterDraining(state.peer(), DrainReason.OPERATOR_DRAIN, event.at());
    }

    private Outcome untrackedOperatorDecommission(Untracked state, OperatorDecommission event) {
        if (!event.force()) {return Outcome.nop(state);}
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, event.at()));
        var effects = List.<MembershipEffect>of(emit(state.peer(),
                                                     MembershipDomainEvent.NODE_FAILED,
                                                     REASON_OPERATOR_FORCED));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), toMillis(event.at()), false),
                               writes,
                               effects);
    }

    private Outcome enterDraining(NodeId peer, DrainReason reason, HlcTimestamp at) {
        var writes = singleWrite(putLifecycle(peer, NodeLifecycleState.DRAINING, at));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(peer, TimerKind.JOIN_DEADLINE));
        effects.add(new InvokeDrain(peer, reason));
        return Outcome.outcome(MembershipFsmState.draining(peer, toMillis(at), reason), writes, effects);
    }

    private Outcome drainingHardDeparted(Draining state, HlcTimestamp at) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, at));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelDrain(state.peer()));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, REASON_SWIM_DEPARTED));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), toMillis(at), true),
                               writes,
                               effects);
    }

    private Outcome drainingOperatorDecommission(Draining state, OperatorDecommission event) {
        if (!event.force()) {return Outcome.nop(state);}
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, event.at()));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelDrain(state.peer()));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, REASON_OPERATOR_FORCED));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), toMillis(event.at()), false),
                               writes,
                               effects);
    }

    private Outcome drainingDrainOutcome(Draining state, DrainOutcome event) {
        if (event.success()) {
            var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, event.at()));
            var effects = List.<MembershipEffect>of(emit(state.peer(), MembershipDomainEvent.NODE_DRAINED, REASON_NONE));
            return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), toMillis(event.at()), false),
                                   writes,
                                   effects);
        }
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.FAILED_DRAIN, event.at()));
        var effects = List.<MembershipEffect>of(emit(state.peer(),
                                                     MembershipDomainEvent.NODE_DRAIN_FAILED,
                                                     REASON_DRAIN_HARD_DEADLINE));
        return Outcome.outcome(MembershipFsmState.failedDrain(state.peer(), toMillis(event.at())),
                               writes,
                               effects);
    }

    private Outcome failedDrainToDecommissioned(FailedDrain state, HlcTimestamp at, String reason) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, at));
        var effects = List.<MembershipEffect>of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), toMillis(at), isSwimReason(reason)),
                               writes,
                               effects);
    }

    /// Build a `Put<NodeLifecycleKey, NodeLifecycleValue>` carrying the originator's HLC.
    /// The reducer emits a minimal value (state + updatedAt-millis + transitionedAt-HLC);
    /// the wiring layer merges host/port/observedCoreEpoch/provisioningSource before
    /// dispatching to consensus (see `MembershipFsm.resolveLifecycleWrites`).
    private static KVCommand<AetherKey> putLifecycle(NodeId peer, NodeLifecycleState newState, HlcTimestamp at) {
        var key = NodeLifecycleKey.nodeLifecycleKey(peer);
        var value = NodeLifecycleValue.nodeLifecycleValue(newState,
                                                          toMillis(at),
                                                          "",
                                                          0,
                                                          Epoch.ZERO,
                                                          at);
        return new KVCommand.Put<>(key, value);
    }

    private static KVCommand<AetherKey> removeSlot(String slotId) {
        return new KVCommand.Remove<>(ProvisioningSlotKey.provisioningSlotKey(slotId));
    }

    private static List<KVCommand<AetherKey>> singleWrite(KVCommand<AetherKey> command) {
        return List.of(command);
    }

    private static EmitDomainEvent emit(NodeId peer, MembershipDomainEvent event, String reason) {
        return new EmitDomainEvent(peer, event, reason);
    }

    /// Converts an `HlcTimestamp` to wall-clock milliseconds for use in KV `updatedAt` and
    /// in-memory state `...AtMs` fields. Truncates the HLC physical-microseconds component;
    /// the HLC itself (with logical counter + node id) is preserved via `transitionedAt`.
    private static long toMillis(HlcTimestamp at) {
        return at.physicalMicros() / 1000L;
    }

    /// SWIM-driven decommission reasons that activate the `decommissionedSwimRefractory`
    /// gate in `decommissionedSwimHealthy` — see field doc on
    /// `MembershipFsmConfig.decommissionedSwimRefractory`.
    private static boolean isSwimReason(String reason) {
        return REASON_SWIM_FAULTY.equals(reason) || REASON_SWIM_DEPARTED.equals(reason);
    }

    @SuppressWarnings("JBCT-EX-01") private static Outcome illegal(MembershipFsmState state, MembershipFsmEvent event) {
        var message = "Illegal (state, event) transition: state=" + state.getClass().getSimpleName() + ", event=" + event.getClass()
                                                                                                                                  .getSimpleName() + ", peer=" + state.peer()
                                                                                                                                                                           .id();
        throw new IllegalStateException(message);
    }

    private static final String REASON_NONE = "";

    private static final String REASON_SWIM_FAULTY = "swim-faulty";

    private static final String REASON_SWIM_DEPARTED = "swim-departed";

    private static final String REASON_JOIN_TIMEOUT = "join-timeout";

    private static final String REASON_OPERATOR_FORCED = "operator-forced";

    private static final String REASON_OPERATOR_DECOMMISSION = "operator-decommission";

    private static final String REASON_OPERATOR_OVERRIDE = "operator-override";

    private static final String REASON_DRAIN_HARD_DEADLINE = "drain-hard-deadline";

    /// Topology-observation refactor Step 2: reason carried on `NodeLifecycleValue` writes
    /// triggered by `TransportUnreachable` events (JOINING and ON_DUTY cells). Not a SWIM
    /// reason — does NOT activate `decommissionedSwimRefractory` (see `isSwimReason`).
    private static final String REASON_TRANSPORT_FAILURE = "transport-failure";

    private static final String REASON_REVIVAL = "revival";
}
