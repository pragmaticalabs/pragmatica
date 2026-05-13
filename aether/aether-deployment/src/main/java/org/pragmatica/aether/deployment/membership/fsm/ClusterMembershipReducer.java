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
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;


/// Pure per-peer cluster-membership reducer (spec §5, 7 states × 8 events = 56 cells).
///
/// `apply(state, event)` is a total function: every cell is explicit. `err`-marked cells
/// (unreachable by construction) throw `IllegalStateException` to surface bugs rather than
/// silently swallow. `nop` cells return `Outcome(state, [], [])`.
///
/// The reducer performs no I/O. Effects are returned as `MembershipEffect` descriptors;
/// the wiring layer (E.3/E.4) translates them into scheduler / `DrainCoordinator` /
/// event-bus calls (I7).
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

    public Outcome apply(MembershipFsmState state, MembershipFsmEvent event) {
        return switch (state){
            case Untracked s -> applyUntracked(s, event);
            case Provisioning s -> applyProvisioning(s, event);
            case Joining s -> applyJoining(s, event);
            case OnDuty s -> applyOnDuty(s, event);
            case Draining s -> applyDraining(s, event);
            case Decommissioned s -> applyDecommissioned(s, event);
            case FailedDrain s -> applyFailedDrain(s, event);
        };
    }

    private Outcome applyUntracked(Untracked state, MembershipFsmEvent event) {
        return switch (event){
            // H.3 (spec §H): SWIM-driven cells are nop. SWIM presence alone is sufficient for
            // `MembershipView` to report `ON_DUTY`; no KV write is needed. The pre-H model's
            // `(UNTRACKED, SwimHealthy) → ON_DUTY` write produced the leader-takeover stranded-
            // peer bug (alive peers never observed by the new leader stayed UNTRACKED in KV).
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted _ -> Outcome.nop(state);
            case SlotClaimed e -> enterJoining(state.peer(),
                                               Option.some(e.slotId()),
                                               e.nowMs());
            case OperatorDrain _ -> Outcome.nop(state);
            case OperatorDecommission e -> untrackedOperatorDecommission(state, e);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
        };
    }

    private Outcome applyProvisioning(Provisioning state, MembershipFsmEvent event) {
        return switch (event){
            case SwimHealthy _ -> illegal(state, event);
            case SwimFaulty _ -> illegal(state, event);
            case SwimDeparted _ -> illegal(state, event);
            case SlotClaimed e -> enterJoining(state.peer(),
                                               Option.some(e.slotId()),
                                               e.nowMs());
            case OperatorDrain _ -> illegal(state, event);
            case OperatorDecommission _ -> illegal(state, event);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
        };
    }

    private Outcome applyJoining(Joining state, MembershipFsmEvent event) {
        return switch (event){
            // H.3: `(JOINING, SwimHealthy) → ON_DUTY` is retained — this is the slot-provisioning
            // completion path (operator-declared CTM slot claim → JOINING → SWIM confirms →
            // promote to ON_DUTY and clear the slot key). The KV write here is operator-aware,
            // not SWIM-driven; the slot key removal is the load-bearing part. Pre-H code also
            // wrote ON_DUTY which `MembershipView` honours (KV `ON_DUTY` + HEALTHY SWIM ⇒
            // `ON_DUTY` in the view).
            case SwimHealthy e -> joiningToOnDuty(state, e.nowMs());
            case SwimFaulty _ -> Outcome.nop(state);
            // H.3: SWIM-departed during JOINING is a SWIM-driven signal; rely on the
            // `JoinDeadlineExpired` timer to clean up if the peer never confirms. Eliminates
            // the SWIM-driven KV write race.
            case SwimDeparted _ -> Outcome.nop(state);
            case SlotClaimed _ -> Outcome.nop(state);
            case OperatorDrain e -> enterDraining(state.peer(), e.reason(), e.nowMs());
            case OperatorDecommission e -> joiningOperatorDecommission(state, e);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired e -> joiningToDecommissioned(state, e.nowMs(), REASON_JOIN_TIMEOUT);
        };
    }

    private Outcome applyOnDuty(OnDuty state, MembershipFsmEvent event) {
        return switch (event){
            case SwimHealthy _ -> Outcome.nop(state);
            // H.3 (spec §H): SWIM-driven decommission cells are nop. `MembershipView` filters
            // a SWIM-FAULTY peer with a stale KV `ON_DUTY` entry to `UNTRACKED` automatically
            // — no KV write needed. NODE_FAILED / NODE_LEFT events continue to fire from
            // `ClusterEventAggregator.onSwimObservation`. This eliminates the chaos revival
            // storm root cause: pre-H model wrote DECOMMISSIONED on every SwimFaulty
            // observation, racing with stale SwimHealthy gossip to re-flip back to OnDuty.
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted _ -> Outcome.nop(state);
            case SlotClaimed _ -> illegal(state, event);
            case OperatorDrain e -> enterDraining(state.peer(), e.reason(), e.nowMs());
            case OperatorDecommission e -> onDutyOperatorDecommission(state, e);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
        };
    }

    private Outcome applyDraining(Draining state, MembershipFsmEvent event) {
        return switch (event){
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted e -> drainingHardDeparted(state, e.nowMs());
            case SlotClaimed _ -> illegal(state, event);
            case OperatorDrain _ -> Outcome.nop(state);
            case OperatorDecommission e -> drainingOperatorDecommission(state, e);
            case DrainOutcome e -> drainingDrainOutcome(state, e);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
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
        };
    }

    // H.4 (spec §H): `decommissionedSwimHealthy` revival path removed entirely. `MembershipView`
    // is now authoritative for "alive"; a `DECOMMISSIONED` KV entry is an operator-declared
    // override that does not get auto-revived by SWIM gossip.

    private Outcome applyFailedDrain(FailedDrain state, MembershipFsmEvent event) {
        return switch (event){
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted e -> failedDrainToDecommissioned(state, e.nowMs(), REASON_SWIM_DEPARTED);
            case SlotClaimed _ -> illegal(state, event);
            case OperatorDrain _ -> Outcome.nop(state);
            case OperatorDecommission e -> failedDrainToDecommissioned(state, e.nowMs(), REASON_OPERATOR_OVERRIDE);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
        };
    }

    // H.4 (spec §H): `untrackedDirectToOnDuty` helper removed entirely. `MembershipView`
    // derives ON_DUTY from a HEALTHY SWIM observation alone — no KV write is needed.

    private Outcome enterJoining(NodeId peer, Option<String> slotId, long nowMs) {
        var writes = singleWrite(putLifecycle(peer, NodeLifecycleState.JOINING, nowMs));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(emit(peer, MembershipDomainEvent.NODE_JOINING, REASON_NONE));
        effects.add(new ScheduleTimer(peer, TimerKind.JOIN_DEADLINE, config.joinDeadline()));
        return Outcome.outcome(MembershipFsmState.joining(peer, nowMs, slotId), writes, effects);
    }

    private Outcome joiningToOnDuty(Joining state, long nowMs) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.ON_DUTY, nowMs));
        state.slotId().onPresent(slotId -> writes.add(removeSlot(slotId)));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(state.peer(), TimerKind.JOIN_DEADLINE));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_ON_DUTY, REASON_NONE));
        return Outcome.outcome(MembershipFsmState.onDuty(state.peer(), nowMs),
                               writes,
                               effects);
    }

    private Outcome joiningToDecommissioned(Joining state, long nowMs, String reason) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, nowMs));
        state.slotId().onPresent(slotId -> writes.add(removeSlot(slotId)));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(state.peer(), TimerKind.JOIN_DEADLINE));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), nowMs, isSwimReason(reason)),
                               writes,
                               effects);
    }

    private Outcome joiningOperatorDecommission(Joining state, OperatorDecommission event) {
        return joiningToDecommissioned(state,
                                       event.nowMs(),
                                       event.force()
                                       ? REASON_OPERATOR_FORCED
                                       : REASON_OPERATOR_DECOMMISSION);
    }

    private Outcome onDutyToDecommissioned(OnDuty state, long nowMs, String reason) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, nowMs));
        var effects = List.<MembershipEffect>of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), nowMs, isSwimReason(reason)),
                               writes,
                               effects);
    }

    private Outcome onDutyOperatorDecommission(OnDuty state, OperatorDecommission event) {
        if (event.force()) {return onDutyToDecommissioned(state, event.nowMs(), REASON_OPERATOR_FORCED);}
        return enterDraining(state.peer(), DrainReason.OPERATOR_DRAIN, event.nowMs());
    }

    private Outcome untrackedOperatorDecommission(Untracked state, OperatorDecommission event) {
        if (!event.force()) {return Outcome.nop(state);}
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, event.nowMs()));
        var effects = List.<MembershipEffect>of(emit(state.peer(),
                                                     MembershipDomainEvent.NODE_FAILED,
                                                     REASON_OPERATOR_FORCED));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), event.nowMs(), false),
                               writes,
                               effects);
    }

    private Outcome enterDraining(NodeId peer, DrainReason reason, long nowMs) {
        var writes = singleWrite(putLifecycle(peer, NodeLifecycleState.DRAINING, nowMs));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(peer, TimerKind.JOIN_DEADLINE));
        effects.add(new InvokeDrain(peer, reason));
        return Outcome.outcome(MembershipFsmState.draining(peer, nowMs, reason), writes, effects);
    }

    private Outcome drainingHardDeparted(Draining state, long nowMs) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, nowMs));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelDrain(state.peer()));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, REASON_SWIM_DEPARTED));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), nowMs, true),
                               writes,
                               effects);
    }

    private Outcome drainingOperatorDecommission(Draining state, OperatorDecommission event) {
        if (!event.force()) {return Outcome.nop(state);}
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, event.nowMs()));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelDrain(state.peer()));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, REASON_OPERATOR_FORCED));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), event.nowMs(), false),
                               writes,
                               effects);
    }

    private Outcome drainingDrainOutcome(Draining state, DrainOutcome event) {
        if (event.success()) {
            var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, event.nowMs()));
            var effects = List.<MembershipEffect>of(emit(state.peer(), MembershipDomainEvent.NODE_DRAINED, REASON_NONE));
            return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), event.nowMs(), false),
                                   writes,
                                   effects);
        }
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.FAILED_DRAIN, event.nowMs()));
        var effects = List.<MembershipEffect>of(emit(state.peer(),
                                                     MembershipDomainEvent.NODE_DRAIN_FAILED,
                                                     REASON_DRAIN_HARD_DEADLINE));
        return Outcome.outcome(MembershipFsmState.failedDrain(state.peer(), event.nowMs()),
                               writes,
                               effects);
    }

    private Outcome failedDrainToDecommissioned(FailedDrain state, long nowMs, String reason) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.DECOMMISSIONED, nowMs));
        var effects = List.<MembershipEffect>of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));
        return Outcome.outcome(MembershipFsmState.decommissioned(state.peer(), nowMs, isSwimReason(reason)),
                               writes,
                               effects);
    }

    private static KVCommand<AetherKey> putLifecycle(NodeId peer, NodeLifecycleState newState, long nowMs) {
        var key = NodeLifecycleKey.nodeLifecycleKey(peer);
        var value = NodeLifecycleValue.nodeLifecycleValue(newState, nowMs);
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

    private static final String REASON_REVIVAL = "revival";
}
