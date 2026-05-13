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
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

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
        return new HlcTimestamp(HlcTimestamp.pack(millis * 1000L, 0), "test");
    }

    /// Extracts the wall-clock millis derivation used inside the reducer for state-AtMs fields.
    private static long ms(HlcTimestamp at) {
        return at.physicalMicros() / 1000L;
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
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1));
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.onDuty(PEER, ms(T1)));
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.ON_DUTY, T1));
            assertEmitted(outcome, MembershipDomainEvent.NODE_ON_DUTY);
        }

        @Test void untracked_swimFaulty_isNop_bootstrapSafe() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1)), state);
        }

        @Test void untracked_swimDeparted_isNop() {
            assertNop(reducer.apply(state, new SwimDeparted(PEER, 1L, T1)), state);
        }

        @Test void untracked_slotClaimed_entersJoining() {
            var outcome = reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1));
            assertEntersJoining(outcome, Option.some(SLOT_ID), T1);
        }

        @Test void untracked_operatorDrain_isNop_unknownPeer() {
            assertNop(reducer.apply(state, new OperatorDrain(PEER, DrainReason.OPERATOR_DRAIN, T1)), state);
        }

        @Test void untracked_operatorDecommissionGraceful_isNop() {
            assertNop(reducer.apply(state, new OperatorDecommission(PEER, false, T1)), state);
        }

        @Test void untracked_operatorDecommissionForce_writesDecommissioned() {
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, true, T1));
            assertDecommissioned(outcome, T1, "operator-forced");
        }

        @Test void untracked_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void untracked_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1)), state);
        }
    }

    @Nested @DisplayName("Provisioning × *")
    class FromProvisioning {
        private final Provisioning state = MembershipFsmState.provisioning(PEER, SLOT_ID);

        @Test void provisioning_swimHealthy_isErr() {
            assertIllegal(state, new SwimHealthy(PEER, 1L, T1));
        }

        @Test void provisioning_swimFaulty_isErr() {
            assertIllegal(state, new SwimFaulty(PEER, 1L, T1));
        }

        @Test void provisioning_swimDeparted_isErr() {
            assertIllegal(state, new SwimDeparted(PEER, 1L, T1));
        }

        @Test void provisioning_slotClaimed_entersJoining() {
            var outcome = reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1));
            assertEntersJoining(outcome, Option.some(SLOT_ID), T1);
        }

        @Test void provisioning_operatorDrain_isErr() {
            assertIllegal(state, new OperatorDrain(PEER, DrainReason.OPERATOR_DRAIN, T1));
        }

        @Test void provisioning_operatorDecommission_isErr() {
            assertIllegal(state, new OperatorDecommission(PEER, false, T1));
        }

        @Test void provisioning_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void provisioning_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1)), state);
        }
    }

    @Nested @DisplayName("Joining × *")
    class FromJoining {
        private final Joining state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));

        @Test void joining_swimHealthy_promotesToOnDuty_leaderInitiated() {
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1));
            assertOnDuty(outcome, T1);
            assertThat(outcome.writes()).contains(removeSlot(SLOT_ID));
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
            assertEmitted(outcome, MembershipDomainEvent.NODE_ON_DUTY);
        }

        @Test void joining_swimHealthy_withoutSlot_writesLifecycleOnly() {
            var slotless = MembershipFsmState.joining(PEER, ms(T0), Option.none());
            var outcome = reducer.apply(slotless, new SwimHealthy(PEER, 1L, T1));
            assertOnDuty(outcome, T1);
            assertThat(outcome.writes()).hasSize(1);
            assertThat(outcome.writes()).noneMatch(c -> c instanceof KVCommand.Remove<?>);
        }

        @Test void joining_swimFaulty_isNop_transientDuringBoot() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1)), state);
        }

        @Test void joining_swimDeparted_writesDecommissioned() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1));
            assertDecommissioned(outcome, T1, "swim-departed");
        }

        @Test void joining_slotClaimed_isNop_idempotentReDelivery() {
            assertNop(reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1)), state);
        }

        @Test void joining_operatorDrain_entersDraining() {
            var outcome = reducer.apply(state, new OperatorDrain(PEER, DrainReason.OPERATOR_DRAIN, T1));
            assertDraining(outcome, T1, DrainReason.OPERATOR_DRAIN);
            assertThat(outcome.effects()).contains(new InvokeDrain(PEER, DrainReason.OPERATOR_DRAIN));
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }

        @Test void joining_operatorDecommissionGraceful_writesDecommissioned() {
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, false, T1));
            assertDecommissioned(outcome, T1, "operator-decommission");
            assertThat(outcome.writes()).contains(removeSlot(SLOT_ID));
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }

        @Test void joining_operatorDecommissionForce_writesDecommissioned() {
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, true, T1));
            assertDecommissioned(outcome, T1, "operator-forced");
        }

        @Test void joining_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void joining_joinDeadlineExpired_writesDecommissioned_joinTimeout() {
            var outcome = reducer.apply(state, new JoinDeadlineExpired(PEER, T1));
            assertDecommissioned(outcome, T1, "join-timeout");
            assertThat(outcome.writes()).contains(removeSlot(SLOT_ID));
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }
    }

    @Nested @DisplayName("OnDuty × *")
    class FromOnDuty {
        private final OnDuty state = MembershipFsmState.onDuty(PEER, ms(T0));

        @Test void onDuty_swimHealthy_isNop_reConfirmation() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T1)), state);
        }

        @Test void onDuty_swimFaulty_writesDecommissioned_smokingGun() {
            var outcome = reducer.apply(state, new SwimFaulty(PEER, 1L, T1));
            assertDecommissioned(outcome, T1, "swim-faulty");
            assertThat(outcome.writes()).hasSize(1);
        }

        @Test void onDuty_swimDeparted_writesDecommissioned() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1));
            assertDecommissioned(outcome, T1, "swim-departed");
        }

        @Test void onDuty_slotClaimed_isErr() {
            assertIllegal(state, new SlotClaimed(PEER, SLOT_ID, T1));
        }

        @Test void onDuty_operatorDrain_entersDraining() {
            var outcome = reducer.apply(state, new OperatorDrain(PEER, DrainReason.OPERATOR_DRAIN, T1));
            assertDraining(outcome, T1, DrainReason.OPERATOR_DRAIN);
            assertThat(outcome.effects()).contains(new InvokeDrain(PEER, DrainReason.OPERATOR_DRAIN));
        }

        @Test void onDuty_operatorDecommissionForce_directToDecommissioned_noDrainCoordinator() {
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, true, T1));
            assertDecommissioned(outcome, T1, "operator-forced");
            // Q2=A: force is a direct transition; no DrainCoordinator effect.
            assertThat(outcome.effects()).noneMatch(e -> e instanceof InvokeDrain);
            assertThat(outcome.effects()).noneMatch(e -> e instanceof CancelDrain);
        }

        @Test void onDuty_operatorDecommissionGraceful_routesThroughDraining() {
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, false, T1));
            assertDraining(outcome, T1, DrainReason.OPERATOR_DRAIN);
            assertThat(outcome.effects()).contains(new InvokeDrain(PEER, DrainReason.OPERATOR_DRAIN));
        }

        @Test void onDuty_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void onDuty_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1)), state);
        }
    }

    @Nested @DisplayName("Draining × *")
    class FromDraining {
        private final Draining state = MembershipFsmState.draining(PEER, ms(T0), DrainReason.OPERATOR_DRAIN);

        @Test void draining_swimHealthy_isNop_drainOwnsOutcome() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T1)), state);
        }

        @Test void draining_swimFaulty_isNop_drainOwnsOutcome() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1)), state);
        }

        @Test void draining_swimDeparted_writesDecommissioned_hardOverridesDrain() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1));
            assertDecommissioned(outcome, T1, "swim-departed");
            assertThat(outcome.effects()).contains(new CancelDrain(PEER));
        }

        @Test void draining_slotClaimed_isErr() {
            assertIllegal(state, new SlotClaimed(PEER, SLOT_ID, T1));
        }

        @Test void draining_operatorDrain_isNop_alreadyDraining() {
            assertNop(reducer.apply(state, new OperatorDrain(PEER, DrainReason.OPERATOR_DRAIN, T1)), state);
        }

        @Test void draining_operatorDecommissionForce_cancelsDrainAndDecommissions() {
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, true, T1));
            assertDecommissioned(outcome, T1, "operator-forced");
            assertThat(outcome.effects()).contains(new CancelDrain(PEER));
        }

        @Test void draining_operatorDecommissionGraceful_isNop_alreadyDraining() {
            assertNop(reducer.apply(state, new OperatorDecommission(PEER, false, T1)), state);
        }

        @Test void draining_drainOutcomeSuccess_writesDecommissioned_emitsNodeDrained() {
            var outcome = reducer.apply(state, new DrainOutcome(PEER, true, T1));
            assertDecommissioned(outcome, T1, null);  // null reason check — see assertion below
            assertEmitted(outcome, MembershipDomainEvent.NODE_DRAINED);
        }

        @Test void draining_drainOutcomeFailure_writesFailedDrain_emitsNodeDrainFailed() {
            var outcome = reducer.apply(state, new DrainOutcome(PEER, false, T1));
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.failedDrain(PEER, ms(T1)));
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.FAILED_DRAIN, T1));
            assertEmitted(outcome, MembershipDomainEvent.NODE_DRAIN_FAILED);
        }

        @Test void draining_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1)), state);
        }
    }

    @Nested @DisplayName("Decommissioned × *")
    class FromDecommissioned {
        // Use a decommissionedAt far enough in the past (T_AGED) to be beyond the 60s
        // revival TTL — preserves the historical "zombie" semantics for the non-revival cells.
        private static final long T_AGED = 0L;
        private static final HlcTimestamp T_LATE = at(120_000L);

        private final Decommissioned state = MembershipFsmState.decommissioned(PEER, T_AGED);

        @Test void decommissioned_swimHealthy_isNop_zombie_pastTtl() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T_LATE)), state);
        }

        @Test void decommissioned_swimFaulty_isNop() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1)), state);
        }

        @Test void decommissioned_swimDeparted_isNop() {
            assertNop(reducer.apply(state, new SwimDeparted(PEER, 1L, T1)), state);
        }

        @Test void decommissioned_slotClaimed_isErr() {
            assertIllegal(state, new SlotClaimed(PEER, SLOT_ID, T1));
        }

        @Test void decommissioned_operatorDrain_isNop() {
            assertNop(reducer.apply(state, new OperatorDrain(PEER, DrainReason.OPERATOR_DRAIN, T1)), state);
        }

        @Test void decommissioned_operatorDecommission_isNop_idempotent() {
            assertNop(reducer.apply(state, new OperatorDecommission(PEER, false, T1)), state);
            assertNop(reducer.apply(state, new OperatorDecommission(PEER, true, T1)), state);
        }

        @Test void decommissioned_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void decommissioned_joinDeadlineExpired_isNop_waitingForGc() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1)), state);
        }
    }

    /// H.3 (spec §H): the revival path is eliminated entirely. `MembershipView` is now
    /// authoritative for "alive" — a peer in KV `DECOMMISSIONED` stays operator-decommissioned
    /// regardless of SWIM gossip. The pre-H ELT-fixture fast-restart pattern (docker kill →
    /// docker start with same NodeId) now requires either an explicit operator-clear of the
    /// KV entry or waiting for `DecommissionedAtomGc` retention. Test class kept as a
    /// regression assertion: SwimHealthy on Decommissioned must NOT produce a write.
    @Nested @DisplayName("Decommissioned × SwimHealthy (H.3: no revival)")
    class DecommissionedRevival {
        private static final long NOW = 1_000_000L;
        private static final HlcTimestamp NOW_HLC = at(NOW);

        @Test void decommissioned_swimHealthy_isNop_hSeriesNoRevival() {
            var state = MembershipFsmState.decommissioned(PEER, NOW - 30_000L);
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, NOW_HLC)), state);
        }

        @Test void decommissioned_swimHealthy_pastRetention_isNop_hSeriesNoRevival() {
            var state = MembershipFsmState.decommissioned(PEER, NOW - 600_000L);
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, NOW_HLC)), state);
        }
    }

    @Nested @DisplayName("FailedDrain × *")
    class FromFailedDrain {
        private final FailedDrain state = MembershipFsmState.failedDrain(PEER, ms(T0));

        @Test void failedDrain_swimHealthy_isNop() {
            assertNop(reducer.apply(state, new SwimHealthy(PEER, 1L, T1)), state);
        }

        @Test void failedDrain_swimFaulty_isNop() {
            assertNop(reducer.apply(state, new SwimFaulty(PEER, 1L, T1)), state);
        }

        @Test void failedDrain_swimDeparted_writesDecommissioned() {
            var outcome = reducer.apply(state, new SwimDeparted(PEER, 1L, T1));
            assertDecommissioned(outcome, T1, "swim-departed");
        }

        @Test void failedDrain_slotClaimed_isErr() {
            assertIllegal(state, new SlotClaimed(PEER, SLOT_ID, T1));
        }

        @Test void failedDrain_operatorDrain_isNop_operatorRecoveryRequired() {
            assertNop(reducer.apply(state, new OperatorDrain(PEER, DrainReason.OPERATOR_DRAIN, T1)), state);
        }

        @Test void failedDrain_operatorDecommission_clearsFailedDrainMarker() {
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, false, T1));
            assertDecommissioned(outcome, T1, "operator-override");
        }

        @Test void failedDrain_drainOutcome_isErr() {
            assertIllegal(state, new DrainOutcome(PEER, true, T1));
        }

        @Test void failedDrain_joinDeadlineExpired_isNop() {
            assertNop(reducer.apply(state, new JoinDeadlineExpired(PEER, T1)), state);
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
            var first = reducer.apply(state, event);
            var second = reducer.apply(state, event);
            assertThat(first).isEqualTo(second);
        }

        @Test void onDutyToDecommissioned_thenSwimFaulty_isNopFromTerminalState() {
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            var event = new SwimFaulty(PEER, 1L, T1);
            var first = reducer.apply(state, event);
            var second = reducer.apply(first.newState(), event);
            assertThat(second.writes()).isEmpty();
            assertThat(second.effects()).isEmpty();
            assertThat(second.newState()).isEqualTo(first.newState());
        }

        @Test void joiningToOnDuty_thenSwimHealthy_isNopFromOnDuty() {
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var event = new SwimHealthy(PEER, 1L, T1);
            var first = reducer.apply(state, event);
            var second = reducer.apply(first.newState(), event);
            assertThat(second.writes()).isEmpty();
            assertThat(second.effects()).isEmpty();
        }

        @Test void drainOutcomeSuccess_thenReapply_isErr_atMostOnceContract() {
            // DrainOutcome is at-most-once per spec §4.1 row 4 — re-apply on Decommissioned is err.
            var state = MembershipFsmState.draining(PEER, ms(T0), DrainReason.OPERATOR_DRAIN);
            var event = new DrainOutcome(PEER, true, T1);
            var first = reducer.apply(state, event);
            assertThatThrownBy(() -> reducer.apply(first.newState(), event)).isInstanceOf(IllegalStateException.class);
        }
    }

    // =================================================================================
    // Specific scenarios called out by the spec.
    // =================================================================================

    @Nested @DisplayName("Smoking-gun replay (spec §1.1)")
    class SmokingGunReplay {
        @Test void onDutyVictim_singleFaultyObservation_writesDecommissioned() {
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            var outcome = reducer.apply(state, new SwimFaulty(PEER, 7L, T1));
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.DECOMMISSIONED, T1));
            assertEmitted(outcome, MembershipDomainEvent.NODE_FAILED);
            // H.4 cure: subsequent SwimHealthy on the Decommissioned state is nop forever
            // (revival path eliminated). Re-apply SwimFaulty: also nop (already decommissioned).
            var reapply = reducer.apply(outcome.newState(), new SwimFaulty(PEER, 7L, T2));
            assertThat(reapply.writes()).isEmpty();
        }
    }

    @Nested @DisplayName("Force decommission direct path (Q2=A)")
    class ForceDecommissionDirect {
        @Test void onDuty_operatorDecommissionForce_singleAtomicWrite_noDrainCoordinator() {
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            var outcome = reducer.apply(state, new OperatorDecommission(PEER, true, T1));

            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.decommissioned(PEER, ms(T1)));
            assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.DECOMMISSIONED, T1));
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
            var outcome = reducer.apply(state, new SlotClaimed(PEER, SLOT_ID, T1));

            var expectedDelay = MembershipFsmConfig.DEFAULT_JOIN_DEADLINE;
            assertThat(outcome.effects()).contains(new ScheduleTimer(PEER, TimerKind.JOIN_DEADLINE, expectedDelay));
        }

        @Test void joinDeadlineExpired_inJoining_transitionsToDecommissioned() {
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var outcome = reducer.apply(state, new JoinDeadlineExpired(PEER, T1));

            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.decommissioned(PEER, ms(T1)));
            assertEmittedWithReason(outcome, MembershipDomainEvent.NODE_FAILED, "join-timeout");
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }

        @Test void joiningExit_emitsCancelTimer_preventingStaleFire() {
            // Any exit from Joining must cancel the join-deadline timer (spec R4).
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1));
            assertThat(outcome.effects()).contains(new CancelTimer(PEER, TimerKind.JOIN_DEADLINE));
        }

        @Test void joinDeadlineExpired_outsideJoining_isHarmlessNop() {
            // Stale-fire after Joining exit must be a no-op against every non-Joining state.
            var onDuty = MembershipFsmState.onDuty(PEER, ms(T0));
            assertNop(reducer.apply(onDuty, new JoinDeadlineExpired(PEER, T2)), onDuty);

            var decommissioned = MembershipFsmState.decommissioned(PEER, ms(T0));
            assertNop(reducer.apply(decommissioned, new JoinDeadlineExpired(PEER, T2)), decommissioned);
        }
    }

    @Nested @DisplayName("Leader-initiated promotion (Q1=A)")
    class LeaderInitiatedPromotion {
        @Test void joining_swimHealthy_promotesToOnDuty_noSelfWriteEvent() {
            // No self-write event exists in MembershipFsmEvent vocabulary — verified at compile time
            // by the sealed hierarchy. This test asserts the leader-observed SwimHealthy fires the promotion.
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some(SLOT_ID));
            var outcome = reducer.apply(state, new SwimHealthy(PEER, 1L, T1));

            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.onDuty(PEER, ms(T1)));
            assertThat(outcome.writes()).contains(putLifecycle(NodeLifecycleState.ON_DUTY, T1));
            assertThat(outcome.writes()).contains(removeSlot(SLOT_ID));
            assertEmitted(outcome, MembershipDomainEvent.NODE_ON_DUTY);
        }

        @Test void joining_swimHealthy_observedByLeader_fromAnyObserverIdentity() {
            // The reducer does not care who the observer was — it consumes `SwimHealthy` events
            // produced by the leader's local SWIM. This test documents that semantic.
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.none());
            var leaderObservation = new SwimHealthy(PEER, 1L, T1);  // observer identity is implicit
            assertThat(reducer.apply(state, leaderObservation).newState()).isEqualTo(MembershipFsmState.onDuty(PEER, ms(T1)));
            // A second, different observer (also the leader, different incarnation) is nop on OnDuty.
            var reapply = reducer.apply(MembershipFsmState.onDuty(PEER, ms(T1)), new SwimHealthy(LEADER, 2L, T2));
            assertThat(reapply.writes()).isEmpty();
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
        assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.JOINING, expectedJoinedAt));
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
        assertThat(outcome.newState()).isEqualTo(MembershipFsmState.decommissioned(PEER, ms(expectedAt), expectedSwimDriven));
        assertThat(outcome.writes()).contains(putLifecycle(NodeLifecycleState.DECOMMISSIONED, expectedAt));
        if (expectedReasonOrNull != null) {
            assertEmittedWithReason(outcome, MembershipDomainEvent.NODE_FAILED, expectedReasonOrNull);
        }
    }

    private static void assertDraining(Outcome outcome, HlcTimestamp expectedAt, DrainReason expectedReason) {
        assertThat(outcome.newState()).isEqualTo(MembershipFsmState.draining(PEER, ms(expectedAt), expectedReason));
        assertThat(outcome.writes()).containsExactly(putLifecycle(NodeLifecycleState.DRAINING, expectedAt));
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
        assertThatThrownBy(() -> reducer.apply(state, event)).isInstanceOf(IllegalStateException.class);
    }

    private static KVCommand<AetherKey> putLifecycle(NodeLifecycleState newState, HlcTimestamp at) {
        var key = NodeLifecycleKey.nodeLifecycleKey(PEER);
        var value = NodeLifecycleValue.nodeLifecycleValue(newState,
                                                          ms(at),
                                                          "",
                                                          0,
                                                          Epoch.ZERO,
                                                          at);
        return new KVCommand.Put<>(key, value);
    }

    private static KVCommand<AetherKey> removeSlot(String slotId) {
        return new KVCommand.Remove<>(ProvisioningSlotKey.provisioningSlotKey(slotId));
    }
}
