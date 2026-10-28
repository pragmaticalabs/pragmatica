// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT_B;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G3;

/// Pins for the L2 entry gate: the node-machine lines a full-line mutation run (v-2463 T1) left alive. Each test names the
/// line it holds; the mutation that must turn it red is the one recorded in the PR.
class NodeFsmEntryGatePinsTest {
    /// Red when: `Observations.desiredPut` lets a newer goal that is NOT the UNLOADED goal leave a pending tombstone Remove
    /// armed (R-3.4.2: a newer goal cancels it), or lets a newer UNLOADED goal cancel it.
    @Test
    @DisplayName("R-3.4.2: a newer non-UNLOADED goal cancels the pending tombstone Remove; a newer UNLOADED goal does not")
    void desiredPut_cancelsThePendingTombstone_onlyForAGoalThatIsNotUnloaded() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1).goal(ARTIFACT, SliceGoal.UNLOADED, G2);

        assertThat(h.record(ARTIFACT).tombstoneArmed()).as("precondition: the tombstone grace is armed").isNotZero();

        var armed = h.record(ARTIFACT).tombstoneArmed();

        h.clearEffects().goal(ARTIFACT, SliceGoal.UNLOADED, G3);

        assertThat(h.record(ARTIFACT).tombstoneArmed()).as("a newer UNLOADED goal leaves the pending tombstone armed").isEqualTo(armed);
        assertThat(h.effectsOf(NodeFsmEffect.Cancel.class)).as("and cancels no timer").isEmpty();

        h.goal(ARTIFACT, SliceGoal.LOADED, new org.pragmatica.aether.slice.kvstore.AetherValue.Generation(5L, 4L));

        assertThat(h.record(ARTIFACT).tombstoneArmed()).as("a newer LOADED goal cancels it").isZero();
        assertThat(h.effectsOf(NodeFsmEffect.Cancel.class)).as("by cancelling the grace timer").isNotEmpty();
    }

    /// Red when: `Redrive.rebuildForward` starts an unconfirmed ACTIVE write's chain as anything but REDRIVE_ACTIVE, or any other
    /// unconfirmed write's chain as anything but SINGLE_WRITE (the kind decides whether the chain is bounded).
    @Test
    @DisplayName("R-6.0 step 5: a rebuilt ACTIVE write runs as REDRIVE_ACTIVE, any other rebuilt write as SINGLE_WRITE")
    void redrive_picksTheChainKind_byTheUnconfirmedState() {
        var h = new NodeHarness().establish().permit(ARTIFACT);
        var active = rebuild(h, SliceObservedState.ACTIVE, SliceObservedState.ACTIVATING);
        var loaded = rebuild(h, SliceObservedState.LOADED, SliceObservedState.LOADING);

        assertThat(boundTimers(active)).as("REDRIVE_ACTIVE is a bounded chain: its bound timer is armed").hasSize(1);
        assertThat(boundTimers(loaded)).as("SINGLE_WRITE is not bounded").isEmpty();
    }

    private static List<NodeFsmEffect.Schedule> boundTimers(Transition step) {
        return step.effects()
                   .stream()
                   .filter(NodeFsmEffect.Schedule.class::isInstance)
                   .map(NodeFsmEffect.Schedule.class::cast)
                   .filter(schedule -> schedule.timer().kind() == TimerId.TimerKind.CHAIN_BOUND)
                   .toList();
    }

    private static Transition rebuild(NodeHarness h, SliceObservedState owed, SliceObservedState committed) {
        var row = new SliceObservedValue(committed, 2L, G1, new ProcessIncarnation(NodeHarness.BOOT_INCARNATION, NodeHarness.SELF_TOKEN), Option.none(), false, 0, List.of(), h.wall);
        var rec = InstanceRecord.instanceRecord()
                                .withDesired(Option.some(new SliceDesiredValue(SliceGoal.ACTIVE, G1, h.wall)))
                                .withObserved(Option.some(row))
                                .withLastVersion(2L)
                                .withUnconfirmed(Option.some(Unconfirmed.unconfirmed(owed, 3L, Option.none())));
        var model = h.model.withInstance(ARTIFACT, rec);

        return Redrive.redrive(h.fsm, Transition.transition(model), ARTIFACT, 0);
    }

    /// Red when: a quorum loss keeps the serving flag of an instance it just unpublished (`serving && !serves` weakened to
    /// `serving || !serves`), or raises it on an instance that never served.
    @Test
    @DisplayName("spec 3.4: a quorum loss carries a serving instance as suspended and clears its serving flag; a non-serving one stays non-serving")
    void quorumLoss_clearsTheServingFlag_ofWhatItUnpublished() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1).permit(ARTIFACT_B).goal(ARTIFACT_B, SliceGoal.LOADED, G1);

        assertThat(h.record(ARTIFACT).serving()).as("precondition: the ACTIVE instance serves").isTrue();
        assertThat(h.record(ARTIFACT_B).serving()).as("precondition: the LOADED instance does not").isFalse();

        h.feed(new NodeFsmInput.QuorumDisappeared());

        assertThat(h.record(ARTIFACT).suspended()).as("the serving instance is carried as suspended").isTrue();
        assertThat(h.record(ARTIFACT).serving()).as("and no longer serves").isFalse();
        assertThat(h.record(ARTIFACT_B).suspended()).isFalse();
        assertThat(h.record(ARTIFACT_B).serving()).as("an instance that never served is not raised to serving").isFalse();
    }
}
