// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.CancelDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.CancelTimer;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.EmitDomainEvent;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.InvokeDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.MembershipDomainEvent;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.ScheduleTimer;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEffect.TimerKind;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.DrainOutcome;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.JoinDeadlineExpired;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SlotClaimed;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimDeparted;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimFaulty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimHealthy;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.TransportReachable;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.TransportUnreachable;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Draining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Joining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Provisioning;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Stopped;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Untracked;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DrainDeadlineKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.JoinDeadlineKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DrainDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.JoinDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;


/// Pure per-peer cluster-membership reducer (spec §5, 6 states × 8 events = 48 cells, plus
/// the `LifecycleCommand` branch dispatched via `applyCommand`).
///
/// Step H collapse (2026-05-22): the prior 7-state alphabet (Untracked / Provisioning /
/// Joining / OnDuty / Draining / Decommissioned / FailedDrain) collapsed to 6 by unifying
/// the two terminal records into a single `Stopped` carrying a `StopReason` sidecar (FORCED /
/// GRACEFUL / DRAIN_FAILED). The KV-layer `NodeLifecycleState` enum (step I) collapsed
/// `DECOMMISSIONED`/`SHUTTING_DOWN`/`FAILED_DRAIN` → `STOPPED` over the same period, so the
/// reducer's `MembershipFsmState.Stopped` and `NodeLifecycleState.STOPPED` are isomorphic.
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

    /// Input-dispatching entry point. `MembershipFsmInput` is the sealed root over the
    /// observed-fact branch (`MembershipFsmEvent`) and the operator-intent branch
    /// (`LifecycleCommand`). Events flow through the per-state event matrix; commands flow
    /// through `applyCommand` which dispatches per-command-type then per-state.
    public Outcome apply(MembershipFsmState state, MembershipFsmInput input) {
        return switch (input) {
            case MembershipFsmEvent event -> apply(state, event);
            case LifecycleCommand command -> applyCommand(state, command);
        };
    }

    /// E2 Phase 2a (2026-05-28): the leader-side φ-accrual handoff (#231) is removed. The
    /// `(ON_DUTY, SwimFaulty)` cell now trusts SWIM directly — a faulty observation
    /// decommissions unconditionally. The reducer signature drops the `PhiWarmth` parameter.
    public Outcome apply(MembershipFsmState state, MembershipFsmEvent event) {
        return switch (state) {
            case Untracked s -> applyUntracked(s, event);
            case Provisioning s -> applyProvisioning(s, event);
            case Joining s -> applyJoining(s, event);
            case OnDuty s -> applyOnDuty(s, event);
            case Draining s -> applyDraining(s, event);
            case Stopped s -> applyStopped(s, event);
        };
    }

    private Outcome applyUntracked(Untracked state, MembershipFsmEvent event) {
        return switch (event) {
            case SwimHealthy e -> untrackedDirectToOnDuty(state.peer(), e.at());
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted _ -> Outcome.nop(state);
            case SlotClaimed e -> enterJoining(state.peer(),
                                               Option.some(e.slotId()),
                                               e.at());
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    private Outcome applyProvisioning(Provisioning state, MembershipFsmEvent event) {
        return switch (event) {
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted _ -> Outcome.nop(state);
            case SlotClaimed e -> enterJoining(state.peer(),
                                               Option.some(e.slotId()),
                                               e.at());
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    private Outcome applyJoining(Joining state, MembershipFsmEvent event) {
        return switch (event) {
            case SwimHealthy e -> joiningToOnDuty(state, e.at());
            // SPIKE #231 (THROWAWAY): was Outcome.nop(state) — flip to decommission so a killed
            // JOINING node SWIM-marked FAULTY is removed fast instead of waiting for the join
            // deadline (~45-60s). REVERT to restore Outcome.nop(state).
            case SwimFaulty e -> joiningToStopped(state, e.at(), REASON_SWIM_FAULTY, StopReason.FORCED);
            case SwimDeparted e -> joiningToStopped(state, e.at(), REASON_SWIM_DEPARTED, StopReason.FORCED);
            case SlotClaimed _ -> Outcome.nop(state);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired e -> joiningToStopped(state, e.at(), REASON_JOIN_TIMEOUT, StopReason.FORCED);
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable e -> joiningToStopped(state, e.at(), REASON_TRANSPORT_FAILURE, StopReason.FORCED);
        };
    }

    private Outcome applyOnDuty(OnDuty state, MembershipFsmEvent event) {
        // E2 Phase 2a (2026-05-28): the leader-side φ-accrual handoff (#231) is removed. SWIM
        // owns the liveness decision directly — a SwimFaulty on an ON_DUTY peer decommissions
        // unconditionally, matching the pre-handoff ALWAYS_CONFIRMED behavior. TransportUnreachable
        // remains UNGATED (a closed QUIC channel is definitive). SwimDeparted is unconditional
        // (explicit leave). The 30s OnDutyFaulty reconciler remains the backstop.
        return switch (event) {
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty e -> onDutyToStopped(state, e.at(), REASON_SWIM_FAULTY, StopReason.FORCED);
            case SwimDeparted e -> onDutyToStopped(state, e.at(), REASON_SWIM_DEPARTED, StopReason.FORCED);
            // A late/duplicate SlotClaimed for an already-ON_DUTY peer is benign (auto-heal
            // replacement re-claim / event re-delivery): nop rather than illegal() — a
            // throwing reducer cell aborts the FSM tick (found via Spike-2, 2026-05-24).
            case SlotClaimed _ -> Outcome.nop(state);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable e -> onDutyToStopped(state, e.at(), REASON_TRANSPORT_FAILURE, StopReason.FORCED);
        };
    }

    /// `@SuppressWarnings("JBCT-SEQ-01")`: the sealed-event switch has 8 arms after the
    /// convergence-reconciler Phase 1 migration (OperatorDrain/OperatorDecommission moved to
    /// `LifecycleCommand`) plus the topology-observation refactor (Step 2). The JBCT-SEQ-01
    /// method-chain heuristic flags switches with many arms; here the count is dictated by
    /// the sealed-interface exhaustiveness contract (8 events × 6 states post-Step-H). Cannot
    /// be reduced without sacrificing per-cell explicitness.
    @SuppressWarnings("JBCT-SEQ-01")
    private Outcome applyDraining(Draining state, MembershipFsmEvent event) {
        return switch (event) {
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted e -> drainingHardDeparted(state, e.at());
            // A SlotClaimed re-delivered for a DRAINING peer is a benign idempotent re-projection
            // (CTM slot re-PUT / KV-notification replay): nop rather than illegal() — a throwing
            // reducer cell aborts the FSM tick on the KV-notification thread (mirrors the ON_DUTY
            // :180 rationale, Spike-2 2026-05-24).
            case SlotClaimed _ -> Outcome.nop(state);
            case DrainOutcome e -> drainingDrainOutcome(state, e);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    /// `@SuppressWarnings("JBCT-SEQ-01")`: see `applyDraining` rationale — 8-arm exhaustive
    /// switch is required by the sealed `MembershipFsmEvent` contract.
    ///
    /// Step H collapse (2026-05-22): unified former `applyDecommissioned` and `applyFailedDrain`
    /// into a single Stopped-state handler. The single behavioural difference between the two
    /// originals — `(FailedDrain, SwimDeparted) → Decommissioned` clears the FAILED_DRAIN
    /// terminal — is preserved by gating the `SwimDeparted` arm on `state.reason() == DRAIN_FAILED`,
    /// which re-writes the lifecycle entry with `StopReason.FORCED` so consumers can distinguish
    /// "drain failed and never came back" from "drain failed, then SWIM-departed".
    @SuppressWarnings("JBCT-SEQ-01")
    private Outcome applyStopped(Stopped state, MembershipFsmEvent event) {
        return switch (event) {
            case SwimHealthy _ -> Outcome.nop(state);
            case SwimFaulty _ -> Outcome.nop(state);
            case SwimDeparted e -> state.reason() == StopReason.DRAIN_FAILED
                                   ? stoppedDrainFailedDeparted(state, e.at())
                                   : Outcome.nop(state);
            // A SlotClaimed re-delivered for an already-STOPPED (terminal) peer is a benign
            // idempotent re-projection (CTM reseed re-binding a dead occupant / KV-notification
            // replay): nop rather than illegal() — a throwing reducer cell aborts the FSM tick on
            // the KV-notification thread (mirrors the ON_DUTY :180 rationale, Spike-2 2026-05-24).
            case SlotClaimed _ -> Outcome.nop(state);
            case DrainOutcome _ -> illegal(state, event);
            case JoinDeadlineExpired _ -> Outcome.nop(state);
            case TransportReachable _ -> Outcome.nop(state);
            case TransportUnreachable _ -> Outcome.nop(state);
        };
    }

    // H.4 (spec §H): `decommissionedSwimHealthy` revival path removed entirely. `MembershipView`
    // is now authoritative for "alive"; a `STOPPED` KV entry is an operator-declared
    // override that does not get auto-revived by SWIM gossip.
    /// Command-branch dispatch. Each `LifecycleCommand` variant has a dedicated handler that
    /// switches on the current state and routes through the existing state-transition helpers
    /// where possible. Illegal command-on-state combinations are treated as no-ops (mirrors
    /// the "no-op + audit" decision in spec §6) rather than throwing — commands originate
    /// from external systems (operator, reconciler, drain coordinator) where a stale view
    /// of state is normal, and a leader-side throw would crash the FSM thread.
    private Outcome applyCommand(MembershipFsmState state, LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission cmd -> applyForceDecommission(state, cmd);
            case ForceOnDuty cmd -> applyForceOnDuty(state, cmd);
            case RecordJoining cmd -> applyRecordJoining(state, cmd);
            case RequestReJoin cmd -> applyRequestReJoin(state, cmd);
            case ForceDrain cmd -> applyForceDrain(state, cmd);
        };
    }

    /// `ForceDecommission` — terminal transition to STOPPED carrying a `StopReason`
    /// sidecar (GRACEFUL / FORCED / DRAIN_FAILED). Sources per spec §6: CTM scale-down
    /// (FORCED), drain coordinator on success (GRACEFUL), drain coordinator/HTTP route on
    /// timeout (DRAIN_FAILED), reconciler `JoiningTimeout` / `OnDutyFaulty` / `DrainTimeout`
    /// rules, operator API. Idempotent on already-Stopped except when the prior
    /// `StopReason` is `DRAIN_FAILED` and the incoming reason differs: the marker is
    /// cleared by rewriting the lifecycle entry with the new `StopReason` (spec step H
    /// preserved transition — former `FailedDrain → ForceDecommission` arms).
    private Outcome applyForceDecommission(MembershipFsmState state, ForceDecommission cmd) {
        var reasonText = stopReasonText(cmd.reason());

        return switch (state) {
            case Untracked s -> untrackedToStopped(s, cmd.at(), reasonText, cmd.reason());
            case Provisioning s -> provisioningToStopped(s, cmd.at(), reasonText, cmd.reason());
            case Joining s -> joiningToStopped(s, cmd.at(), reasonText, cmd.reason());
            case OnDuty s -> onDutyToStopped(s, cmd.at(), reasonText, cmd.reason());
            case Draining s -> drainingToStopped(s, cmd.at(), reasonText, cmd.reason());
            case Stopped s -> stoppedToStopped(s, cmd.at(), reasonText, cmd.reason());
        };
    }

    /// `Stopped(DRAIN_FAILED) → Stopped(FORCED | GRACEFUL)` clears the drain-failed
    /// marker via an explicit operator override (spec §6, step H preserved transition).
    /// All other `Stopped → Stopped` combinations are idempotent no-ops: the prior
    /// `StopReason` is already terminal and ForceDecommission re-issued with the same
    /// or a non-clearing reason MUST NOT produce a new write (spec idempotence rule).
    private Outcome stoppedToStopped(Stopped state, HlcTimestamp at, String reason, StopReason stopReason) {
        if (state.reason() != StopReason.DRAIN_FAILED || stopReason == StopReason.DRAIN_FAILED) {
            return Outcome.nop(state);
        }

        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.STOPPED, at, Option.some(stopReason)));
        writes.add(removeDrainDeadline(state.peer()));
        var effects = List.<MembershipEffect> of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), stopReason, false),
                               writes,
                               effects);
    }

    /// `ForceOnDuty` — promote to ON_DUTY. Sources per spec §6: cluster-sync `readyCandidate`
    /// arrival on the leader (the SYNCING sub-phase completion signal arriving via
    /// `ClusterSyncPong`), reconciler post-sync convergence. Idempotent on already-ON_DUTY;
    /// no-op from operator-owned states (Draining) and terminal states.
    private Outcome applyForceOnDuty(MembershipFsmState state, ForceOnDuty cmd) {
        return switch (state) {
            case Untracked _ -> untrackedDirectToOnDuty(cmd.peer(), cmd.at());
            case Joining s -> joiningToOnDuty(s, cmd.at());
            case OnDuty s -> Outcome.nop(s);
            case Provisioning s -> Outcome.nop(s);
            case Draining s -> Outcome.nop(s);
            case Stopped s -> Outcome.nop(s);
        };
    }

    /// `RecordJoining` — register a JOINING entry. Sources per spec §6: reconciler
    /// `GenerationLifecycleGap` rule (Rabia member with no lifecycle entry past budget),
    /// CTM slot-claimed flow when JOINING entry is missing. No-op if peer is already tracked
    /// in any non-Untracked / non-Provisioning state.
    private Outcome applyRecordJoining(MembershipFsmState state, RecordJoining cmd) {
        return switch (state) {
            case Untracked _ -> enterJoining(cmd.peer(), cmd.slotId(), cmd.at());
            case Provisioning _ -> enterJoining(cmd.peer(), cmd.slotId(), cmd.at());
            case Joining s -> Outcome.nop(s);
            case OnDuty s -> Outcome.nop(s);
            case Draining s -> Outcome.nop(s);
            case Stopped s -> Outcome.nop(s);
        };
    }

    /// `RequestReJoin` — reset peer to Untracked so it can re-enter JOINING. Sources per
    /// spec §6: drain coordinator on drain cancellation, operator API for forced re-join
    /// after stuck DRAINING. Emits a `Remove` for the lifecycle key + cancels any in-flight
    /// timers/drains. The next SlotClaimed / SwimHealthy / RecordJoining will recreate the
    /// entry. No-op if already Untracked.
    private Outcome applyRequestReJoin(MembershipFsmState state, RequestReJoin cmd) {
        if (state instanceof Untracked s) {
            return Outcome.nop(s);
        }

        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(new KVCommand.Remove<>(NodeLifecycleKey.nodeLifecycleKey(cmd.peer())));

        if (state instanceof Joining) {
            writes.add(removeJoinDeadline(cmd.peer()));
        }
        if (state instanceof Draining) {
            writes.add(removeDrainDeadline(cmd.peer()));
        }

        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(cmd.peer(), TimerKind.JOIN_DEADLINE));

        if (state instanceof Draining) {
            effects.add(new CancelDrain(cmd.peer()));
        }

        return Outcome.outcome(MembershipFsmState.untracked(cmd.peer()),
                               writes,
                               effects);
    }

    /// `ForceDrain` — transition to DRAINING carrying a `DrainReason` sidecar. Sources per
    /// spec §6: Operator API drain endpoint, CLI `aether nodes drain <id>`. Delegates to
    /// `enterDraining` on ON_DUTY so the `InvokeDrain` effect + join-deadline cancel are
    /// emitted. Idempotent on already-DRAINING; no-op from pre-active (Untracked /
    /// Provisioning / Joining) and terminal (Stopped) states — operator
    /// intent against a peer that isn't actively serving is recorded by the audit stream but
    /// does not advance the FSM.
    private Outcome applyForceDrain(MembershipFsmState state, ForceDrain cmd) {
        return switch (state) {
            case OnDuty _ -> enterDraining(cmd.peer(), cmd.reason(), cmd.at());
            case Draining s -> Outcome.nop(s);
            case Untracked s -> Outcome.nop(s);
            case Provisioning s -> Outcome.nop(s);
            case Joining s -> Outcome.nop(s);
            case Stopped s -> Outcome.nop(s);
        };
    }

    /// `(UNTRACKED, SwimHealthy) → ON_DUTY` direct. SWIM only emits an observation when a peer's
    /// state CHANGES — for SWIM-discovered peers we skip the intermediate JOINING state (which
    /// is reserved for slot-provisioning workflows). Pre-H this was load-bearing for KV ON_DUTY
    /// entries; post-H the `MembershipView` derived path returns the same answer either way,
    /// but the KV write is retained for legacy consumers that read `NodeLifecycleKey` directly.
    private Outcome untrackedDirectToOnDuty(NodeId peer, HlcTimestamp at) {
        var writes = singleWrite(putLifecycle(peer, NodeLifecycleState.ON_DUTY, at, Option.none()));
        var effects = List.<MembershipEffect> of(emit(peer, MembershipDomainEvent.NODE_ON_DUTY, REASON_NONE));

        return Outcome.outcome(MembershipFsmState.onDuty(peer, toMillis(at)), writes, effects);
    }

    private Outcome enterJoining(NodeId peer, Option<String> slotId, HlcTimestamp at) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(peer, NodeLifecycleState.JOINING, at, Option.none()));
        writes.add(putJoinDeadline(peer, at));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(emit(peer, MembershipDomainEvent.NODE_JOINING, REASON_NONE));
        effects.add(new ScheduleTimer(peer, TimerKind.JOIN_DEADLINE, config.joinDeadline()));

        return Outcome.outcome(MembershipFsmState.joining(peer, toMillis(at), slotId), writes, effects);
    }

    private Outcome joiningToOnDuty(Joining state, HlcTimestamp at) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.ON_DUTY, at, Option.none()));
        writes.add(removeJoinDeadline(state.peer()));
        // Durable slots (D1, spec §3.1): the slot is NOT deleted when its occupant reaches
        // ON_DUTY — it persists and CTM `classifyOccupied` reclassifies it HEALTHY. The reducer
        // only writes the lifecycle atom; CTM is the sole owner of slot occupancy.
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(state.peer(), TimerKind.JOIN_DEADLINE));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_ON_DUTY, REASON_NONE));

        return Outcome.outcome(MembershipFsmState.onDuty(state.peer(), toMillis(at)),
                               writes,
                               effects);
    }

    private Outcome joiningToStopped(Joining state, HlcTimestamp at, String reason, StopReason stopReason) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.STOPPED, at, Option.some(stopReason)));
        writes.add(removeJoinDeadline(state.peer()));
        // Durable slots (D1, spec §3.1): a JOINING node stopping does NOT delete the slot atom.
        // The slot persists with its assignedNodeId; CTM `classifyOccupancy` → DEAD → `freeSlot`
        // clears the occupant IN PLACE (records supersededNodeId) and refills. The reducer only
        // writes the lifecycle (STOPPED) atom.
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(state.peer(), TimerKind.JOIN_DEADLINE));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), stopReason, isSwimReason(reason)),
                               writes,
                               effects);
    }

    private Outcome onDutyToStopped(OnDuty state, HlcTimestamp at, String reason, StopReason stopReason) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.STOPPED, at, Option.some(stopReason)));
        var effects = List.<MembershipEffect> of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), stopReason, isSwimReason(reason)),
                               writes,
                               effects);
    }

    private Outcome enterDraining(NodeId peer, DrainReason reason, HlcTimestamp at) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(peer, NodeLifecycleState.DRAINING, at, Option.none()));
        writes.add(putDrainDeadline(peer, at));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelTimer(peer, TimerKind.JOIN_DEADLINE));
        effects.add(new InvokeDrain(peer, reason));

        return Outcome.outcome(MembershipFsmState.draining(peer, toMillis(at), reason), writes, effects);
    }

    private Outcome drainingHardDeparted(Draining state, HlcTimestamp at) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.STOPPED, at, Option.some(StopReason.FORCED)));
        writes.add(removeDrainDeadline(state.peer()));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelDrain(state.peer()));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, REASON_SWIM_DEPARTED));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), StopReason.FORCED, true),
                               writes,
                               effects);
    }

    private Outcome drainingDrainOutcome(Draining state, DrainOutcome event) {
        if (event.success()) {
            var writes = new ArrayList<KVCommand<AetherKey>>();
            writes.add(putLifecycle(state.peer(),
                                    NodeLifecycleState.STOPPED,
                                    event.at(),
                                    Option.some(StopReason.GRACEFUL)));
            writes.add(removeDrainDeadline(state.peer()));
            var effects = List.<MembershipEffect> of(emit(state.peer(), MembershipDomainEvent.NODE_DRAINED, REASON_NONE));

            return Outcome.outcome(MembershipFsmState.stopped(state.peer(),
                                                              toMillis(event.at()),
                                                              StopReason.GRACEFUL,
                                                              false),
                                   writes,
                                   effects);
        }

        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(),
                                NodeLifecycleState.STOPPED,
                                event.at(),
                                Option.some(StopReason.DRAIN_FAILED)));
        writes.add(removeDrainDeadline(state.peer()));
        var effects = List.<MembershipEffect> of(emit(state.peer(),
                                                      MembershipDomainEvent.NODE_DRAIN_FAILED,
                                                      REASON_DRAIN_HARD_DEADLINE));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(),
                                                          toMillis(event.at()),
                                                          StopReason.DRAIN_FAILED,
                                                          false),
                               writes,
                               effects);
    }

    /// Step H preserved transition: a `STOPPED+DRAIN_FAILED` peer that subsequently SWIM-departs
    /// is re-written as `STOPPED+FORCED` to mark the resolved-by-departure path. Mirrors the
    /// former `failedDrainToDecommissioned(_, _, REASON_SWIM_DEPARTED)` write.
    private Outcome stoppedDrainFailedDeparted(Stopped state, HlcTimestamp at) {
        var writes = singleWrite(putLifecycle(state.peer(),
                                              NodeLifecycleState.STOPPED,
                                              at,
                                              Option.some(StopReason.FORCED)));
        var effects = List.<MembershipEffect> of(emit(state.peer(),
                                                      MembershipDomainEvent.NODE_FAILED,
                                                      REASON_SWIM_DEPARTED));
        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), StopReason.FORCED, true),
                               writes,
                               effects);
    }

    /// Command-driven decommission from `Untracked`. The command carries an explicit
    /// `StopReason` and is always honored. Writes a STOPPED entry so external
    /// consumers reading `NodeLifecycleKey` directly see the operator's intent.
    private Outcome untrackedToStopped(Untracked state, HlcTimestamp at, String reason, StopReason stopReason) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.STOPPED, at, Option.some(stopReason)));
        var effects = List.<MembershipEffect> of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), stopReason, false),
                               writes,
                               effects);
    }

    /// Command-driven decommission from `Provisioning`. Writes only the STOPPED lifecycle atom.
    /// Durable slots (D1, spec §3.1): CTM owns slot occupancy — it handles scale-down via
    /// `removeSurplusSlots` (removes slots with index >= configured) and failure-clearing via
    /// `freeDeadSlots`. The reducer must NOT delete the slot atom.
    private Outcome provisioningToStopped(Provisioning state, HlcTimestamp at, String reason, StopReason stopReason) {
        var writes = singleWrite(putLifecycle(state.peer(), NodeLifecycleState.STOPPED, at, Option.some(stopReason)));
        var effects = List.<MembershipEffect> of(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), stopReason, false),
                               writes,
                               effects);
    }

    /// Command-driven decommission from `Draining`. Cancels the in-flight drain and writes
    /// STOPPED. Used by the reconciler `DrainTimeout` rule and operator API.
    private Outcome drainingToStopped(Draining state, HlcTimestamp at, String reason, StopReason stopReason) {
        var writes = new ArrayList<KVCommand<AetherKey>>();
        writes.add(putLifecycle(state.peer(), NodeLifecycleState.STOPPED, at, Option.some(stopReason)));
        writes.add(removeDrainDeadline(state.peer()));
        var effects = new ArrayList<MembershipEffect>();
        effects.add(new CancelDrain(state.peer()));
        effects.add(emit(state.peer(), MembershipDomainEvent.NODE_FAILED, reason));

        return Outcome.outcome(MembershipFsmState.stopped(state.peer(), toMillis(at), stopReason, false),
                               writes,
                               effects);
    }

    /// Map a `StopReason` from a `ForceDecommission` command to a reducer reason string.
    /// The reason is consumed by `MembershipDomainEvent` emission and by `isSwimReason` (none
    /// of these stop-reasons are SWIM-driven, so the resulting `Stopped.swimDriven`
    /// flag is always `false` for command-driven paths).
    private static String stopReasonText(StopReason reason) {
        return switch (reason) {
            case GRACEFUL -> REASON_GRACEFUL_STOP;
            case FORCED -> REASON_OPERATOR_FORCED;
            case DRAIN_FAILED -> REASON_DRAIN_HARD_DEADLINE;
        };
    }

    /// Build a `Put<NodeLifecycleKey, NodeLifecycleValue>` carrying the originator's HLC and
    /// (for STOPPED writes) the `StopReason` sidecar. The reducer emits a minimal value
    /// (state + updatedAt-millis + transitionedAt-HLC + stopReason); the wiring layer merges
    /// host/port/observedCoreEpoch/provisioningSource before dispatching to consensus
    /// (see `MembershipFsm.resolveLifecycleWrites`).
    ///
    /// `stopReason` is `Option.some(...)` only when `newState == STOPPED` — DRAINING / ON_DUTY /
    /// JOINING writes pass `Option.none()` and the resulting `NodeLifecycleValue.stopReason()`
    /// is `none()` (which the deserialiser also tolerates for pre-Step-I snapshots).
    private static KVCommand<AetherKey> putLifecycle(NodeId peer,
                                                     NodeLifecycleState newState,
                                                     HlcTimestamp at,
                                                     Option<StopReason> stopReason) {
        var key = NodeLifecycleKey.nodeLifecycleKey(peer);
        var value = NodeLifecycleValue.nodeLifecycleValue(newState, toMillis(at), "", 0, Epoch.ZERO, at).withStopReason(stopReason);

        return new KVCommand.Put<>(key, value);
    }

    /// Phase 1 step J — write the JOIN_DEADLINE observability atom. `deadlineMs` is wall-clock
    /// millis derived from the event HLC plus `config.joinDeadline()`; `setAt` is the HLC stamp
    /// of the triggering JOINING-entry event. Mirrors the in-process scheduler entry so a new
    /// leader can reconstruct the deadline from KV on takeover.
    private KVCommand<AetherKey> putJoinDeadline(NodeId peer, HlcTimestamp at) {
        var deadlineMs = toMillis(at) + config.joinDeadline().millis();

        return new KVCommand.Put<>(JoinDeadlineKey.joinDeadlineKey(peer),
                                   JoinDeadlineValue.joinDeadlineValue(deadlineMs, at));
    }

    /// Phase 1 step J — write the DRAIN_DEADLINE observability atom. `deadlineMs` is wall-clock
    /// millis derived from the event HLC plus `config.drainTimeout()`; `setAt` is the HLC stamp
    /// of the triggering DRAINING-entry event.
    private KVCommand<AetherKey> putDrainDeadline(NodeId peer, HlcTimestamp at) {
        var deadlineMs = toMillis(at) + config.drainTimeout().millis();

        return new KVCommand.Put<>(DrainDeadlineKey.drainDeadlineKey(peer),
                                   DrainDeadlineValue.drainDeadlineValue(deadlineMs, at));
    }

    /// Phase 1 step J — clear the JOIN_DEADLINE atom on JOINING-exit (ON_DUTY / STOPPED).
    private static KVCommand<AetherKey> removeJoinDeadline(NodeId peer) {
        return new KVCommand.Remove<>(JoinDeadlineKey.joinDeadlineKey(peer));
    }

    /// Phase 1 step J — clear the DRAIN_DEADLINE atom on DRAINING-exit (any STOPPED variant).
    private static KVCommand<AetherKey> removeDrainDeadline(NodeId peer) {
        return new KVCommand.Remove<>(DrainDeadlineKey.drainDeadlineKey(peer));
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

    /// `swimDriven` flag carried on the `Stopped` state. SWIM-driven reasons
    /// (`swim-faulty`, `swim-departed`) distinguish failure-detection paths from
    /// operator/drain-driven decommissions. Currently informational only — the H.4
    /// refractory gate that consumed it has been removed; the field is dormant and
    /// scheduled for follow-up cleanup along with the `Stopped.swimDriven` field.
    private static boolean isSwimReason(String reason) {
        return REASON_SWIM_FAULTY.equals(reason) || REASON_SWIM_DEPARTED.equals(reason);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Outcome illegal(MembershipFsmState state, MembershipFsmEvent event) {
        var message = "Illegal (state, event) transition: state=" + state.getClass().getSimpleName()
                    + ", event=" + event.getClass().getSimpleName()
                    + ", peer=" + state.peer().id();
        throw new IllegalStateException(message);
    }

    private static final String REASON_NONE = "";
    private static final String REASON_SWIM_FAULTY = "swim-faulty";
    private static final String REASON_SWIM_DEPARTED = "swim-departed";
    private static final String REASON_JOIN_TIMEOUT = "join-timeout";
    private static final String REASON_OPERATOR_FORCED = "operator-forced";
    private static final String REASON_OPERATOR_OVERRIDE = "operator-override";

    private static final String REASON_DRAIN_HARD_DEADLINE = "drain-hard-deadline";

    /// Command-branch reason: drain coordinator reports successful drain completion. Routed
    /// through `ForceDecommission` with `StopReason.GRACEFUL`. Not a SWIM reason — produces
    /// `Stopped.swimDriven = false`.
    private static final String REASON_GRACEFUL_STOP = "graceful-stop";

    /// Topology-observation refactor Step 2: reason carried on `NodeLifecycleValue` writes
    /// triggered by `TransportUnreachable` events (JOINING and ON_DUTY cells). Not a SWIM
    /// reason — does NOT mark the resulting Stopped state as `swimDriven`.
    private static final String REASON_TRANSPORT_FAILURE = "transport-failure";
}
