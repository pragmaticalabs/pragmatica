// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.ClusterMembershipReducer.Outcome;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
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
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.JoinDeadlineKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DrainDeadlineKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.JoinDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DrainDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterMembershipReducerTest {
    private static final NodeId PEER = NodeId.nodeId("peer-1").unwrap();

    private static final NodeId LEADER = NodeId.nodeId("leader").unwrap();

    private static final String SLOT_ID = "slot-1";

    private static final HlcTimestamp T0 = at(1_000L);

    private static final HlcTimestamp T1 = at(2_000L);

    private static final HlcTimestamp T2 = at(3_000L);

    /// Builds an `HlcTimestamp` whose physical-microseconds component equals `millis * 1000`
    /// so the reducer's `physicalMicros() / 1000` derivation yields exactly `millis`.
    /// Counter component is zero, nodeId "test" — sufficient for deterministic equality.
    private static HlcTimestamp at(long millis) {
        return new HlcTimestamp(HlcTimestamp.pack(millis * 1000L, 0), new NodeId("test"));
    }

    /// Extracts the wall-clock millis derivation used inside the reducer for state-AtMs fields.
    private static long ms(HlcTimestamp at) {
        return at.physicalMicros() / 1000L;
    }

    private static final Cause TEST_CAUSE = Causes.cause("test");

    private static ForceDrain forceDrain(HlcTimestamp at) {
        return new ForceDrain(PEER, DrainReason.OPERATOR_DRAIN, TEST_CAUSE, at);
    }

    private static ForceDecommission forceDecommissionForced(HlcTimestamp at) {
        return new ForceDecommission(PEER, StopReason.FORCED, TEST_CAUSE, at);
    }

    private static ForceDecommission forceDecommissionGraceful(HlcTimestamp at) {
        return new ForceDecommission(PEER, StopReason.GRACEFUL, TEST_CAUSE, at);
    }

    private static ForceDecommission forceDecommissionDrainFailed(HlcTimestamp at) {
        return new ForceDecommission(PEER, StopReason.DRAIN_FAILED, TEST_CAUSE, at);
    }

    private ClusterMembershipReducer reducer;

    @BeforeEach
    void setUp() {
        reducer = ClusterMembershipReducer.clusterMembershipReducer(MembershipFsmConfig.defaultMembershipFsmConfig());
    }

    // =================================================================================
    // 56-cell totality: every (state × event) pair has an explicit, expected outcome.
    // Cells marked `err` in the spec are tested for IllegalStateException.
    // =================================================================================

    @Nested @DisplayName("Untracked × *")
    class FromUntracked {
        private final Untracked state = MembershipFsmState.untracked(PEER);

        @Test void untracked_swimHealthy_writesOnDuty_legacyConsumers() {
            // H partial revert (2026-05-13): SWIM-driven `ON_DUTY` write is retained so
            // legacy consumers reading `NodeLifecycleKey` directly continue to function.
            // `MembershipView` is the canonical reader; the KV write is redundant with the
            // view's SWIM-derived ON_DUTY but is preserved for back-compat.
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD);
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.onDuty(PEER, ms(T1)));
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.ON_DUTY, T1));
            assertEmitted(outcome, MembershipDomainEvent.NODE_ON_DUTY);
        }

        @Test void untracked_swimFaulty_isNop_bootstrapSafe() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void untracked_swimDeparted_isNop() {
            assertNop(reducer.apply(state, new SwimDeparted(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void untracked_slotClaimed_entersJoining() {
            var outcome = reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD);
            assertEntersJoining(outcome, Option.some(SLOT_ID), T1);
        }

        @Test void untracked_forceDrain_isNop_unknownPeer() {
            assertNop(reducer.apply(state, forceDrain(T1), PhiWarmth.COLD), state);
        }

        @Test void untracked_forceDecommissionForced_writesDecommissioned() {
            var outcome = reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "operator-forced");
        }

        @Test void untracked_forceDecommissionGraceful_writesDecommissioned() {
            // Command-driven: ForceDecommission is unconditional on Untracked (writes
            // DECOMMISSIONED so external KV consumers see the operator's intent).
            var outcome = reducer.apply(state, forceDecommissionGraceful(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "graceful-stop");
        }

        @Test void untracked_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void untracked_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD), state);
        }
    }

    @Nested @DisplayName("Provisioning × *")
    class FromProvisioning {
        private final Provisioning state = MembershipFsmState.provisioning(PEER, SLOT_ID);

        @Test void provisioning_swimHealthy_isNop() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void provisioning_swimFaulty_isNop() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void provisioning_swimDeparted_isNop() {
            assertNop(reducer.apply(state, new SwimDeparted(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void provisioning_slotClaimed_entersJoining() {
            var outcome = reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD);
            assertEntersJoining(outcome, Option.some(SLOT_ID), T1);
        }

        @Test void provisioning_forceDrain_isNop_notServingYet() {
            assertNop(reducer.apply(state, forceDrain(T1), PhiWarmth.COLD), state);
        }

        @Test void provisioning_forceDecommissionForced_writesDecommissioned_slotPersists() {
            var outcome = reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "operator-forced");
            // Durable slots (D1): the reducer writes only the STOPPED lifecycle atom; CTM owns
            // slot occupancy (scale-down via removeSurplusSlots, failure-clearing via freeDeadSlots).
            assertThat(outcome.writes()).noneMatch(c -> c instanceof KVCommand.Remove<?> r
                                                       && r.key() instanceof ProvisioningSlotKey);
        }

        @Test void provisioning_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void provisioning_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD), state);
        }
    }

    @Nested @DisplayName("Joining × *")
    class FromJoining {
        private final Joining state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));

        @Test void joining_swimHealthy_promotesToOnDuty_leaderInitiated() {
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD);
            assertOnDuty(outcome, T1);
            // Durable slots (D1): the slot is NOT removed on ON_DUTY — it persists and CTM
            // reclassifies it HEALTHY. The reducer writes only the lifecycle + join-deadline-clear.
            assertThat(outcome.writes()).noneMatch(c -> c instanceof KVCommand.Remove<?> r
                                                       && r.key() instanceof ProvisioningSlotKey);
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
            assertEmitted(outcome, MembershipDomainEvent.NODE_ON_DUTY);
        }

        @Test void joining_swimHealthy_withoutSlot_writesLifecycleOnly() {
            var slotless = MembershipFsmState.joining(PEER, ms(T0), Option.none());
            var outcome = reducer.apply(slotless, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD);
            assertOnDuty(outcome, T1);
            // Lifecycle Put (ON_DUTY) + JoinDeadlineKey Remove (Phase 1 step J co-write).
            // Durable slots (D1): the slot is never removed on ON_DUTY (slotted or slotless),
            // so exactly 2 writes either way.
            assertThat(outcome.writes()).hasSize(2);
            assertThat(outcome.writes()).noneMatch(c -> c instanceof KVCommand.Remove<?> r
                                                       && r.key() instanceof ProvisioningSlotKey);
        }

        @Test void joining_swimFaulty_writesDecommissioned_killedDuringBoot() {
            // SPIKE #231 reducer change (ClusterMembershipReducer applyJoining): a killed JOINING
            // node SWIM-marked FAULTY is decommissioned fast rather than waiting out the join
            // deadline (~45-60s). Warmth is irrelevant here — JOINING has no φ history and the cell
            // does not consult it.
            var outcome = reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "swim-faulty");
        }

        @Test void joining_swimDeparted_writesDecommissioned() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "swim-departed");
        }

        @Test void joining_slotClaimed_isNop_idempotentReDelivery() {
            assertNop(reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD), state);
        }

        @Test void joining_forceDrain_isNop_drainsOnlyFromOnDuty() {
            // Command-driven: ForceDrain only acts on ON_DUTY. From Joining, it is a no-op —
            // operator must wait for the JOINING peer to either complete its join or time out
            // before issuing a drain. Force-decommission is the explicit short-circuit path.
            assertNop(reducer.apply(state, forceDrain(T1), PhiWarmth.COLD), state);
        }

        @Test void joining_forceDecommissionGraceful_writesDecommissioned() {
            var outcome = reducer.apply(state, forceDecommissionGraceful(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "graceful-stop");
            // Durable slots (D1): JOINING-stop does NOT delete the slot atom; CTM clears the
            // occupant in place (DEAD → freeSlot). Reducer writes only the STOPPED lifecycle.
            assertThat(outcome.writes()).noneMatch(c -> c instanceof KVCommand.Remove<?> r
                                                       && r.key() instanceof ProvisioningSlotKey);
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }

        @Test void joining_forceDecommissionForced_writesDecommissioned() {
            var outcome = reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "operator-forced");
        }

        @Test void joining_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void joining_joinDeadlineExpired_writesDecommissioned_joinTimeout() {
            var outcome = reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "join-timeout");
            // Durable slots (D1): JOINING-stop does NOT delete the slot atom; CTM clears the
            // occupant in place. Reducer writes only the STOPPED lifecycle + join-deadline-clear.
            assertThat(outcome.writes()).noneMatch(c -> c instanceof KVCommand.Remove<?> r
                                                       && r.key() instanceof ProvisioningSlotKey);
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }
    }

    @Nested @DisplayName("OnDuty × *")
    class FromOnDuty {
        private final OnDuty state = MembershipFsmState.onDuty(PEER, ms(T0));

        @Test void onDuty_swimHealthy_isNop_reConfirmation() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void onDuty_swimFaulty_writesDecommissioned_smokingGun() {
            // #231: φ COLD → SWIM owns liveness → SwimFaulty decommissions (matches pre-handoff
            // ALWAYS_CONFIRMED behavior for the cold-start / never-warmed window).
            var outcome = reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "swim-faulty");
            assertThat(outcome.writes()).hasSize(1);
        }

        @Test void onDuty_swimFaulty_phiWarm_isNop_phiOwnsLiveness() {
            // #231: φ WARM → φ owns liveness. A SwimFaulty while φ still hears the peer's pongs is a
            // SWIM false-positive (the peer is ponging → alive) → nop. φ-silence, not SWIM, drives
            // the eventual decommission (PhiObserver issues ForceDecommission directly).
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.WARM), state);
        }

        @Test void onDuty_swimDeparted_writesDecommissioned() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "swim-departed");
        }

        @Test void onDuty_transportUnreachable_writesDecommissioned_ungatedByPhiCold() {
            // #231: TransportUnreachable is UNGATED — a closed QUIC channel is definitive and
            // non-flapping. Fires regardless of φ-warmth (φ COLD here).
            var outcome = reducer.apply(state, new TransportUnreachable(PEER, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "transport-failure");
            assertThat(outcome.writes()).hasSize(1);
        }

        @Test void onDuty_transportUnreachable_writesDecommissioned_ungatedByPhiWarm() {
            // Mirror: even with φ WARM the transport cell still decommissions — warmth gates only
            // the SwimFaulty cell, never the definitive closed-channel signal.
            var outcome = reducer.apply(state, new TransportUnreachable(PEER, T1), PhiWarmth.WARM);
            assertDecommissioned(outcome, T1, "transport-failure");
            assertThat(outcome.writes()).hasSize(1);
        }

        @Test void onDuty_slotClaimed_isNop_idempotentReDelivery() {
            // Auto-heal replacement re-claim / late SlotClaimed re-delivery against an
            // already-ON_DUTY peer is benign — nop, not illegal (found via Spike-2, 2026-05-24:
            // the prior illegal() threw on the normal auto-heal path, aborting the FSM tick).
            assertNop(reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD), state);
        }

        @Test void onDuty_forceDrain_entersDraining() {
            var outcome = reducer.apply(state, forceDrain(T1), PhiWarmth.COLD);
            assertDraining(outcome, T1, DrainReason.OPERATOR_DRAIN);
            assertThat(outcome.effects()).contains(new InvokeDrain(PEER, DrainReason.OPERATOR_DRAIN));
        }

        @Test void onDuty_forceDecommissionForced_directToDecommissioned_noDrainCoordinator() {
            var outcome = reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "operator-forced");
            // Q2=A: force is a direct transition; no DrainCoordinator effect.
            assertThat(outcome.effects()).noneMatch(e -> e instanceof InvokeDrain);
            assertThat(outcome.effects()).noneMatch(e -> e instanceof CancelDrain);
        }

        @Test void onDuty_forceDecommissionGraceful_directToDecommissioned() {
            // Command-driven: ForceDecommission(GRACEFUL) writes DECOMMISSIONED directly. The
            // pre-Phase-1 "graceful → enter Draining" path moved out of the FSM — graceful drain
            // is now driven by the operator API issuing ForceDrain first, then ForceDecommission
            // after the drain coordinator reports success.
            var outcome = reducer.apply(state, forceDecommissionGraceful(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "graceful-stop");
            assertThat(outcome.effects()).noneMatch(e -> e instanceof InvokeDrain);
        }

        @Test void onDuty_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void onDuty_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD), state);
        }
    }

    @Nested @DisplayName("Draining × *")
    class FromDraining {
        private final Draining state = MembershipFsmState.draining(PEER, ms(T0), DrainReason.OPERATOR_DRAIN);

        @Test void draining_swimHealthy_isNop_drainOwnsOutcome() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void draining_swimFaulty_isNop_drainOwnsOutcome() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void draining_swimDeparted_writesDecommissioned_hardOverridesDrain() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "swim-departed");
            assertThat(outcome.effects()).contains(new CancelDrain(PEER));
        }

        @Test void draining_slotClaimed_isNop_idempotentReProjection() {
            // A SlotClaimed re-delivered for a DRAINING peer is a benign idempotent re-projection
            // (CTM slot re-PUT / KV-notification replay). It must NOT throw — a throwing reducer
            // cell aborts the FSM tick on the KV-notification thread (mirrors ON_DUTY, Spike-2).
            assertNop(reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD), state);
        }

        @Test void draining_forceDrain_isNop_alreadyDraining() {
            assertNop(reducer.apply(state, forceDrain(T1), PhiWarmth.COLD), state);
        }

        @Test void draining_forceDecommissionForced_cancelsDrainAndDecommissions() {
            var outcome = reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "operator-forced");
            assertThat(outcome.effects()).contains(new CancelDrain(PEER));
        }

        @Test void draining_forceDecommissionDrainFailed_cancelsDrainAndDecommissions() {
            // Used by the reconciler DrainTimeout rule to finalize a stuck drain.
            var outcome = reducer.apply(state, forceDecommissionDrainFailed(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "drain-hard-deadline");
            assertThat(outcome.effects()).contains(new CancelDrain(PEER));
        }

        @Test void draining_drainOutcomeSuccess_writesDecommissioned_emitsNodeDrained() {
            var outcome = reducer.apply(state, new DrainOutcome(PEER, true, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, null);  // null reason check — see assertion below
            assertEmitted(outcome, MembershipDomainEvent.NODE_DRAINED);
        }

        @Test void draining_drainOutcomeFailure_writesFailedDrain_emitsNodeDrainFailed() {
            var outcome = reducer.apply(state, new DrainOutcome(PEER, false, T1), PhiWarmth.COLD);
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.DRAIN_FAILED));
            // Phase 1 step J co-write: lifecycle Put + DrainDeadlineKey Remove on draining-exit.
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.STOPPED, T1, Option.some(StopReason.DRAIN_FAILED)),
                                                          removeDrainDeadline());
            assertEmitted(outcome, MembershipDomainEvent.NODE_DRAIN_FAILED);
        }

        @Test void draining_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD), state);
        }
    }

    @Nested @DisplayName("Stopped × * (former Decommissioned cell)")
    class FromStopped {
        // Use a stoppedAt far enough in the past (T_AGED) to be beyond the 60s
        // revival TTL — preserves the historical "zombie" semantics for the non-revival cells.
        private static final long T_AGED = 0L;
        private static final HlcTimestamp T_LATE = at(120_000L);

        private final Stopped state = MembershipFsmState.stopped(PEER, T_AGED, StopReason.FORCED);

        @Test void stopped_swimHealthy_isNop_zombie_pastTtl() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T_LATE), PhiWarmth.COLD), state);
        }

        @Test void stopped_swimFaulty_isNop() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void stopped_swimDeparted_isNop() {
            assertNop(reducer.apply(state, new SwimDeparted(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void stopped_slotClaimed_isNop_idempotentReProjection() {
            // A SlotClaimed re-delivered for an already-STOPPED (terminal) peer is a benign
            // idempotent re-projection (CTM reseed re-binding a dead occupant / KV-notification
            // replay). It must NOT throw — a throwing reducer cell aborts the FSM tick on the
            // KV-notification thread, killing the apply loop (mirrors ON_DUTY, Spike-2 2026-05-24).
            assertNop(reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD), state);
        }

        @Test void stopped_forceDrain_isNop() {
            assertNop(reducer.apply(state, forceDrain(T1), PhiWarmth.COLD), state);
        }

        @Test void stopped_forceDecommission_isNop_idempotent() {
            assertNop(reducer.apply(state, forceDecommissionGraceful(T1), PhiWarmth.COLD), state);
            assertNop(reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD), state);
        }

        @Test void stopped_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void stopped_joinDeadlineExpired_isNop_waitingForGc() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD), state);
        }
    }

    /// H.3 (spec §H): the revival path is eliminated entirely. `MembershipView` is now
    /// authoritative for "alive" — a peer in KV `STOPPED` stays operator-decommissioned
    /// regardless of SWIM gossip. The pre-H ELT-fixture fast-restart pattern (docker kill →
    /// docker start with same NodeId) now requires either an explicit operator-clear of the
    /// KV entry or waiting for `DecommissionedAtomGc` retention. Test class kept as a
    /// regression assertion: SwimHealthy on Stopped must NOT produce a write.
    @Nested @DisplayName("Stopped × SwimHealthy (H.3: no revival)")
    class StoppedRevival {
        private static final long NOW = 1_000_000L;
        private static final HlcTimestamp NOW_HLC = at(NOW);

        @Test void stopped_swimHealthy_isNop_hSeriesNoRevival() {
            var state = MembershipFsmState.stopped(PEER, NOW - 30_000L, StopReason.FORCED);
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, NOW_HLC), PhiWarmth.COLD), state);
        }

        @Test void stopped_swimHealthy_pastRetention_isNop_hSeriesNoRevival() {
            var state = MembershipFsmState.stopped(PEER, NOW - 600_000L, StopReason.FORCED);
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, NOW_HLC), PhiWarmth.COLD), state);
        }
    }

    @Nested @DisplayName("Stopped(DRAIN_FAILED) × * (former FailedDrain cell)")
    class FromStoppedDrainFailed {
        private final Stopped state = MembershipFsmState.stopped(PEER, ms(T0), StopReason.DRAIN_FAILED);

        @Test void stoppedDrainFailed_swimHealthy_isNop() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void stoppedDrainFailed_swimFaulty_isNop() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD), state);
        }

        @Test void stoppedDrainFailed_swimDeparted_writesDecommissioned() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "swim-departed");
        }

        @Test void stoppedDrainFailed_slotClaimed_isNop_idempotentReProjection() {
            // SlotClaimed re-delivered for a terminal STOPPED(DRAIN_FAILED) peer is a benign
            // idempotent re-projection — nop, not a throw (mirrors ON_DUTY, Spike-2 2026-05-24).
            assertNop(reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD), state);
        }

        @Test void stoppedDrainFailed_forceDrain_isNop_operatorRecoveryRequired() {
            assertNop(reducer.apply(state, forceDrain(T1), PhiWarmth.COLD), state);
        }

        @Test void stoppedDrainFailed_forceDecommissionGraceful_clearsDrainFailedMarker() {
            var outcome = reducer.apply(state, forceDecommissionGraceful(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "graceful-stop");
        }

        @Test void stoppedDrainFailed_forceDecommissionForced_clearsDrainFailedMarker() {
            var outcome = reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD);
            assertDecommissioned(outcome, T1, "operator-forced");
        }

        @Test void stoppedDrainFailed_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void stoppedDrainFailed_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD), state);
        }
    }

    // =================================================================================
    // Reducer purity: pure function of inputs — same (state, event) yields equal Outcome.
    // =================================================================================
    @Nested @DisplayName("Purity / idempotence")
    class Purity {
        @Test void apply_isPure_sameInputsYieldEqualOutcomes() {
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            var event = new SwimFaulty(PEER, 1L, T1);
            var first = reducer.apply(state, event, PhiWarmth.COLD);
            var second = reducer.apply(state, event, PhiWarmth.COLD);
            assertThat(first).isEqualTo(second);
        }

        @Test void onDutyToDecommissioned_thenSwimFaulty_isNopFromTerminalState() {
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            var event = new SwimFaulty(PEER, 1L, T1);
            var first = reducer.apply(state, event, PhiWarmth.COLD);
            var second = reducer.apply(first.newState(), event, PhiWarmth.COLD);
            assertThat(second.writes()).isEmpty();
            assertThat(second.effects()).isEmpty();
            assertThat(second.newState()).isEqualTo(first.newState());
        }

        @Test void joiningToOnDuty_thenSwimHealthy_isNopFromOnDuty() {
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var event = new SwimHealthy(PEER, 1L, T1);
            var first = reducer.apply(state, event, PhiWarmth.COLD);
            var second = reducer.apply(first.newState(), event, PhiWarmth.COLD);
            assertThat(second.writes()).isEmpty();
            assertThat(second.effects()).isEmpty();
        }

        @Test void drainOutcomeSuccess_thenReapply_isErr_atMostOnceContract() {
            // DrainOutcome is at-most-once per spec §4.1 row 4 — re-apply on Decommissioned is err.
            var state = MembershipFsmState.draining(PEER, ms(T0), DrainReason.OPERATOR_DRAIN);
            var event = new DrainOutcome(PEER, true, T1);
            var first = reducer.apply(state, event, PhiWarmth.COLD);
            assertThatThrownBy(() -> reducer.apply(first.newState(), event, PhiWarmth.COLD)).isInstanceOf(IllegalStateException.class);
        }
    }

    // =================================================================================
    // Specific scenarios called out by the spec.
    // =================================================================================

    @Nested @DisplayName("Smoking-gun replay (spec §1.1)")
    class SmokingGunReplay {
        @Test void onDutyVictim_singleFaultyObservation_writesDecommissioned() {
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            var outcome = reducer.apply(state, new SwimFaulty(PEER, 7L, T1), PhiWarmth.COLD);
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.STOPPED, T1, Option.some(StopReason.FORCED)));
            assertEmitted(outcome, MembershipDomainEvent.NODE_FAILED);
            // H.4 cure: subsequent SwimHealthy on the Stopped state is nop forever
            // (revival path eliminated). Re-apply SwimFaulty: also nop (already stopped).
            var reapply = reducer.apply(outcome.newState(), new SwimFaulty(PEER, 7L, T2), PhiWarmth.COLD);
            assertThat(reapply.writes()).isEmpty();
        }
    }

    @Nested @DisplayName("Force decommission direct path (Q2=A)")
    class ForceDecommissionDirect {
        @Test void onDuty_forceDecommissionForced_singleAtomicWrite_noDrainCoordinator() {
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            var outcome = reducer.apply(state, forceDecommissionForced(T1), PhiWarmth.COLD);

            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.FORCED));
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.STOPPED, T1, Option.some(StopReason.FORCED)));
            // No DrainCoordinator invocation — Q2=A is a direct transition.
            assertThat(outcome.effects()).noneMatch(e -> e instanceof InvokeDrain);
            assertThat(outcome.effects()).noneMatch(e -> e instanceof CancelDrain);
            assertEmittedWithReason(outcome, MembershipDomainEvent.NODE_FAILED, "operator-forced");
        }
    }

    @Nested @DisplayName("Join deadline one-shot timer (Q3=C)")
    class JoinDeadlineTimer {
        @Test void enteringJoining_schedulesJoinDeadlineTimer() {
            // Post-bootstrap-correction 2026-05-12: JOINING is only reachable via SlotClaimed
            // (the slot-provisioning path), not via SwimHealthy from UNTRACKED.
            var state = MembershipFsmState.untracked(PEER);
            var outcome = reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1), PhiWarmth.COLD);

            var expectedDelay = MembershipFsmConfig.DEFAULT_JOIN_DEADLINE;
            assertThat(outcome.effects()).contains(new ScheduleTimer(PEER, TimerKind.JOIN_DEADLINE, expectedDelay));
        }

        @Test void joinDeadlineExpired_inJoining_transitionsToDecommissioned() {
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var outcome = reducer.apply(state, new JoinDeadlineExpired(PEER, T1), PhiWarmth.COLD);

            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.FORCED));
            assertEmittedWithReason(outcome, MembershipDomainEvent.NODE_FAILED, "join-timeout");
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }

        @Test void joiningExit_emitsCancelTimer_preventingStaleFire() {
            // Any exit from Joining must cancel the join-deadline timer (spec R4).
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD);
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }

        @Test void joinDeadlineExpired_outsideJoining_isHarmlessNop() {
            // Stale-fire after Joining exit must be a no-op against every non-Joining state.
            var onDuty = MembershipFsmState.onDuty(PEER, ms(T0));
            assertNop(reducer.apply(onDuty, new JoinDeadlineExpired(PEER, T2), PhiWarmth.COLD), onDuty);

            var decommissioned = MembershipFsmState.stopped(PEER, ms(T0), StopReason.FORCED);
            assertNop(reducer.apply(decommissioned, new JoinDeadlineExpired(PEER, T2), PhiWarmth.COLD), decommissioned);
        }
    }

    @Nested @DisplayName("Leader-initiated promotion (Q1=A)")
    class LeaderInitiatedPromotion {
        @Test void joining_swimHealthy_promotesToOnDuty_noSelfWriteEvent() {
            // No self-write event exists in MembershipFsmEvent vocabulary — verified at compile time
            // by the sealed hierarchy. This test asserts the leader-observed SwimHealthy fires the promotion.
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1), PhiWarmth.COLD);

            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.onDuty(PEER, ms(T1)));
            assertThat(outcome.writes()).contains(putLifecycle(NodeLifecycleState.ON_DUTY, T1));
            // Durable slots (D1): the slot persists on ON_DUTY (CTM reclassifies it HEALTHY).
            assertThat(outcome.writes()).noneMatch(c -> c instanceof KVCommand.Remove<?> r
                                                       && r.key() instanceof ProvisioningSlotKey);
            assertEmitted(outcome, MembershipDomainEvent.NODE_ON_DUTY);
        }

        @Test void joining_swimHealthy_observedByLeader_fromAnyObserverIdentity() {
            // The reducer does not care who the observer was — it consumes `SwimHealthy` events
            // produced by the leader's local SWIM. This test documents that semantic.
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.none());
            var leaderObservation = new SwimHealthy(PEER, 1L, T1);  // observer identity is implicit
            assertThat(reducer.apply(state, leaderObservation, PhiWarmth.COLD).newState()).isEqualTo(MembershipFsmState.onDuty(PEER, ms(T1)));
            // A second, different observer (also the leader, different incarnation) is nop on OnDuty.
            var reapply = reducer.apply(MembershipFsmState.onDuty(PEER, ms(T1)), new SwimHealthy(LEADER, 2L, T2), PhiWarmth.COLD);
            assertThat(reapply.writes()).isEmpty();
        }
    }

    // =================================================================================
    // Topology-observation refactor (Step 2): 14 transport-event cells.
    //
    // 7 states × 2 events (TransportReachable, TransportUnreachable) = 14 explicit cells.
    // Step 2 has NO conditional gating — gating arrives in Step 4. Two cells produce
    // transitions today: (Joining, TransportUnreachable) and (OnDuty, TransportUnreachable);
    // the other 12 are `nop`. The `(Decommissioned, TransportUnreachable) → nop` row is the
    // chaos-revival defense — preserve it.
    // =================================================================================

    @Nested @DisplayName("Transport events (Step 2): 14-cell table")
    class TransportEventsTable {
        /// Single record per (state, event) row — keeps the table dense and the assertion loop
        /// readable. JUnit 5 parameterized-test infrastructure (junit-jupiter-params) is not on
        /// this module's test classpath, so we walk an explicit list with a per-row failure label.
        record Row(String label, MembershipFsmState state, MembershipFsmEvent event, Outcome expected) {}

        @Test void transportEventsByStateTable_matchesSpecSection16() {
            for (var row : table()) {
                var actual = reducer.apply(row.state(), row.event(), PhiWarmth.COLD);
                assertThat(actual.newState()).as("newState for %s", row.label()).isEqualTo(row.expected().newState());
                assertThat(actual.writes()).as("writes for %s", row.label()).containsExactlyElementsOf(row.expected().writes());
                assertThat(actual.effects()).as("effects for %s", row.label()).containsExactlyElementsOf(row.expected().effects());
            }
        }

        private static List<Row> table() {
            var untracked = MembershipFsmState.untracked(PEER);
            var provisioning = MembershipFsmState.provisioning(PEER, SLOT_ID);
            var joining = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var onDuty = MembershipFsmState.onDuty(PEER, ms(T0));
            var draining = MembershipFsmState.draining(PEER, ms(T0), DrainReason.OPERATOR_DRAIN);
            var decommissioned = MembershipFsmState.stopped(PEER, ms(T0), StopReason.FORCED);
            var failedDrain = MembershipFsmState.stopped(PEER, ms(T0), StopReason.DRAIN_FAILED);

            var reachable = new TransportReachable(PEER, T1);
            var unreachable = new TransportUnreachable(PEER, T1);

            return List.of(
                new Row("Untracked × TransportReachable → nop", untracked, reachable, Outcome.nop(untracked)),
                new Row("Untracked × TransportUnreachable → nop", untracked, unreachable, Outcome.nop(untracked)),
                new Row("Provisioning × TransportReachable → nop", provisioning, reachable, Outcome.nop(provisioning)),
                new Row("Provisioning × TransportUnreachable → nop", provisioning, unreachable, Outcome.nop(provisioning)),
                new Row("Joining × TransportReachable → nop", joining, reachable, Outcome.nop(joining)),
                new Row("Joining × TransportUnreachable → DECOMMISSIONED(transport-failure)",
                        joining, unreachable, joiningToDecommissionedTransport(T1)),
                new Row("OnDuty × TransportReachable → nop", onDuty, reachable, Outcome.nop(onDuty)),
                new Row("OnDuty × TransportUnreachable → DECOMMISSIONED(transport-failure)",
                        onDuty, unreachable, onDutyToDecommissionedTransport(T1)),
                new Row("Draining × TransportReachable → nop", draining, reachable, Outcome.nop(draining)),
                new Row("Draining × TransportUnreachable → nop", draining, unreachable, Outcome.nop(draining)),
                // Chaos-revival defense — DO NOT modify. If a future change accidentally
                // adds a revival path, these two rows fail first.
                new Row("Decommissioned × TransportReachable → nop (chaos-revival defense)",
                        decommissioned, reachable, Outcome.nop(decommissioned)),
                new Row("Decommissioned × TransportUnreachable → nop (chaos-revival defense)",
                        decommissioned, unreachable, Outcome.nop(decommissioned)),
                new Row("FailedDrain × TransportReachable → nop", failedDrain, reachable, Outcome.nop(failedDrain)),
                new Row("FailedDrain × TransportUnreachable → nop", failedDrain, unreachable, Outcome.nop(failedDrain))
            );
        }

        /// Mirrors `ClusterMembershipReducer.joiningToStopped` output: lifecycle write +
        /// join-deadline remove (Phase 1 step J co-write) + cancel-join-deadline effect +
        /// NODE_FAILED domain event with the transport-failure reason. Durable slots (D1):
        /// NO slot removal — CTM clears the dead occupant in place. `swimDriven=false`
        /// (transport-failure is NOT a SWIM reason).
        private static Outcome joiningToDecommissionedTransport(HlcTimestamp at) {
            var newState = MembershipFsmState.stopped(PEER, ms(at), StopReason.FORCED, false);
            return Outcome.outcome(newState,
                                   List.of(putLifecycle(NodeLifecycleState.STOPPED, at, Option.some(StopReason.FORCED)),
                                           removeJoinDeadline()),
                                   List.of(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE),
                                           new EmitDomainEvent(PEER,
                                                               MembershipDomainEvent.NODE_FAILED,
                                                               "transport-failure")));
        }

        /// Mirrors `ClusterMembershipReducer.onDutyToStopped` output: single lifecycle
        /// write + NODE_FAILED domain event. No slot removal (OnDuty has no slot). `swimDriven=false`.
        private static Outcome onDutyToDecommissionedTransport(HlcTimestamp at) {
            var newState = MembershipFsmState.stopped(PEER, ms(at), StopReason.FORCED, false);
            return Outcome.outcome(newState,
                                   List.of(putLifecycle(NodeLifecycleState.STOPPED, at, Option.some(StopReason.FORCED))),
                                   List.of(new EmitDomainEvent(PEER,
                                                               MembershipDomainEvent.NODE_FAILED,
                                                               "transport-failure")));
        }

        @Test void decommissioned_transportUnreachable_isNop_chaosRevivalDefense() {
            // Standalone assertion of the chaos-revival defense for the (Stopped,
            // TransportUnreachable) cell. Duplicates a table row by design — this test is
            // named for code-search visibility and is the canary for future regressions.
            var state = MembershipFsmState.stopped(PEER, ms(T0), StopReason.FORCED);
            var outcome = reducer.apply(state, new TransportUnreachable(PEER, T1), PhiWarmth.COLD);
            assertNop(outcome, state);
        }

        @Test void decommissioned_transportReachable_isNop_chaosRevivalDefense() {
            // Mirror canary for the reachable side — no revival, no writes.
            var state = MembershipFsmState.stopped(PEER, ms(T0), StopReason.FORCED);
            var outcome = reducer.apply(state, new TransportReachable(PEER, T1), PhiWarmth.COLD);
            assertNop(outcome, state);
        }
    }

    // =================================================================================
    // Assertion helpers.
    // =================================================================================

    private static void assertNop(Outcome outcome, MembershipFsmState expectedState) {
        assertThat(outcome.newState()).isEqualTo(expectedState);
        assertThat(outcome.writes()).isEmpty();
        assertThat(outcome.effects()).isEmpty();
    }

    private static void assertEntersJoining(Outcome outcome, Option<String> expectedSlotId, HlcTimestamp expectedJoinedAt) {
        assertThat(outcome.newState()).isEqualTo(MembershipFsmState.joining(PEER, ms(expectedJoinedAt), expectedSlotId));
        // Phase 1 step J co-write: lifecycle Put (JOINING) + JoinDeadlineKey Put.
        assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.JOINING, expectedJoinedAt),
                                                      putJoinDeadline(expectedJoinedAt));
        assertEmitted(outcome, MembershipDomainEvent.NODE_JOINING);
        assertThat(outcome.effects()).anyMatch(e -> e instanceof ScheduleTimer st && st.kind() == TimerKind.JOIN_DEADLINE);
    }

    private static void assertOnDuty(Outcome outcome, HlcTimestamp expectedAt) {
        assertThat(outcome.newState()).isEqualTo(MembershipFsmState.onDuty(PEER, ms(expectedAt)));
        assertThat(outcome.writes()).contains(putLifecycle(NodeLifecycleState.ON_DUTY, expectedAt));
    }

    private static void assertDecommissioned(Outcome outcome, HlcTimestamp expectedAt, String expectedReasonOrNull) {
        var expectedSwimDriven = expectedReasonOrNull != null
                                  && ("swim-faulty".equals(expectedReasonOrNull)
                                      || "swim-departed".equals(expectedReasonOrNull));
        var expectedStopReason = expectedStopReasonFromReasonText(expectedReasonOrNull);
        assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(expectedAt), expectedStopReason, expectedSwimDriven));
        assertThat(outcome.writes()).contains(putLifecycle(NodeLifecycleState.STOPPED, expectedAt, Option.some(expectedStopReason)));
        if (expectedReasonOrNull != null) {
            assertEmittedWithReason(outcome, MembershipDomainEvent.NODE_FAILED, expectedReasonOrNull);
        }
    }

    /// Maps the reducer's reason text (carried on the NODE_FAILED domain event) to the
    /// expected `StopReason` sidecar written into the `NodeLifecycleValue`. Mirrors
    /// `ClusterMembershipReducer.stopReasonText` + the implicit drain-outcome rules.
    /// `null` reason (DrainOutcome success path) → GRACEFUL.
    private static StopReason expectedStopReasonFromReasonText(String reason) {
        if (reason == null) {return StopReason.GRACEFUL;}
        return switch (reason) {
            case "graceful-stop" -> StopReason.GRACEFUL;
            case "drain-hard-deadline" -> StopReason.DRAIN_FAILED;
            default -> StopReason.FORCED;
        };
    }

    private static void assertDraining(Outcome outcome, HlcTimestamp expectedAt, DrainReason expectedReason) {
        assertThat(outcome.newState()).isEqualTo(MembershipFsmState.draining(PEER, ms(expectedAt), expectedReason));
        // Phase 1 step J co-write: lifecycle Put (DRAINING) + DrainDeadlineKey Put.
        assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.DRAINING, expectedAt),
                                                      putDrainDeadline(expectedAt));
    }

    private static void assertEmitted(Outcome outcome, MembershipDomainEvent expected) {
        assertThat(outcome.effects()).anyMatch(e -> e instanceof EmitDomainEvent emit && emit.event() == expected);
    }

    private static void assertEmittedWithReason(Outcome outcome, MembershipDomainEvent expected, String reason) {
        assertThat(outcome.effects()).anyMatch(e -> e instanceof EmitDomainEvent emit
                                                   && emit.event() == expected
                                                   && emit.reason().equals(reason));
    }

    private void assertIllegal(MembershipFsmState state, MembershipFsmEvent event) {
        assertThatThrownBy(() -> reducer.apply(state, event, PhiWarmth.COLD)).isInstanceOf(IllegalStateException.class);
    }

    private static KVCommand<AetherKey> putLifecycle(NodeLifecycleState newState, HlcTimestamp at) {
        return putLifecycle(newState, at, Option.none());
    }

    private static KVCommand<AetherKey> putLifecycle(NodeLifecycleState newState, HlcTimestamp at, Option<StopReason> stopReason) {
        var key = NodeLifecycleKey.nodeLifecycleKey(PEER);
        var value = NodeLifecycleValue.nodeLifecycleValue(newState,
                                                          ms(at),
                                                          "",
                                                          0,
                                                          Epoch.ZERO,
                                                          at)
                                      .withStopReason(stopReason);
        return new KVCommand.Put<>(key, value);
    }

    /// Mirrors `ClusterMembershipReducer.putJoinDeadline` — deadlineMs = at-millis +
    /// `MembershipFsmConfig.DEFAULT_JOIN_DEADLINE`; setAt = the HLC of the JOINING event.
    private static KVCommand<AetherKey> putJoinDeadline(HlcTimestamp at) {
        var deadlineMs = ms(at) + MembershipFsmConfig.DEFAULT_JOIN_DEADLINE.millis();
        return new KVCommand.Put<>(JoinDeadlineKey.joinDeadlineKey(PEER),
                                   JoinDeadlineValue.joinDeadlineValue(deadlineMs, at));
    }

    /// Mirrors `ClusterMembershipReducer.putDrainDeadline` — deadlineMs = at-millis +
    /// `MembershipFsmConfig.DEFAULT_DRAIN_TIMEOUT`; setAt = the HLC of the DRAINING event.
    private static KVCommand<AetherKey> putDrainDeadline(HlcTimestamp at) {
        var deadlineMs = ms(at) + MembershipFsmConfig.DEFAULT_DRAIN_TIMEOUT.millis();
        return new KVCommand.Put<>(DrainDeadlineKey.drainDeadlineKey(PEER),
                                   DrainDeadlineValue.drainDeadlineValue(deadlineMs, at));
    }

    private static KVCommand<AetherKey> removeJoinDeadline() {
        return new KVCommand.Remove<>(JoinDeadlineKey.joinDeadlineKey(PEER));
    }

    private static KVCommand<AetherKey> removeDrainDeadline() {
        return new KVCommand.Remove<>(DrainDeadlineKey.drainDeadlineKey(PEER));
    }
}
