// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #2864 (F4 E09): an ALL_OR_NOTHING blueprint whose BILLING slice is healthy on three nodes and whose ORDERS slice fails fatally
/// is rolled back (#970 terminal ROLLED_BACK, #922). Once the rollback is decided nothing of that blueprint is promoted: no batch at
/// or after the one that carries the ROLLED_BACK outcome writes goal ACTIVE, and at quiescence no observed or desired row of
/// either slice remains.
class RollbackNoPromotionTest {
    private static final String APP = "org.example:app:1.0.0";
    private static final long TICK_SECONDS = Timing.DEFAULT.tick() / 1_000L;
    private static final List<Artifact> SLICES = List.of(ORDERS, BILLING);

    private static final AppBlueprintValue BLUEPRINT = blueprint(APP,
                                                                 false,
                                                                 "attempt-1",
                                                                 slice(ORDERS, 3, 1, Set.of()),
                                                                 slice(BILLING, 3, 1, Set.of()));

    // Red when: the healthy slice's LOADED rows arrive in the drain that decides the rollback, and the activation they derive
    // rides the same flush as the ROLLED_BACK outcome (dev9/dev10: goal ACTIVE on 3 nodes in the terminal batch).
    @Test
    void healthyLoadedInRollbackDrain_isNotPromoted() {
        var h = active();
        var nodes = nodes(h);

        publish(h, BLUEPRINT);
        nodes.forEach(node -> node.follows(BILLING));
        nodes.getFirst().fails(ORDERS, true);
        h.drain();

        quiesce(h, nodes);
        assertTerminalWithoutPromotion(h);
    }

    // Red when: the healthy slice's LOADED rows arrive while the rollback is submitted and not yet settled, and the activation
    // they derive is written after the ROLLED_BACK outcome commits.
    @Test
    void healthyLoadedWhileRollbackInFlight_isNotPromoted() {
        var h = active();
        var nodes = nodes(h);

        publish(h, BLUEPRINT);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        nodes.getFirst().fails(ORDERS, true);
        h.drain();
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("control: the rollback is in flight").hasSize(1);
        nodes.forEach(node -> node.follows(BILLING));
        h.drain();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        List.copyOf(h.held.values()).forEach(submit -> h.settle(submit, h.applyHeld(submit.correlationId())));
        h.drain();

        quiesce(h, nodes);
        assertTerminalWithoutPromotion(h);
    }

    private static List<NodeSim> nodes(FsmHarness h) {
        return List.of(node(h, CORE_1, 11), node(h, CORE_2, 12), node(h, CORE_3, 13));
    }

    /// The nodes follow their goals, and remove their observed row once it reports UNLOADED, as the node FSM does.
    private static void quiesce(FsmHarness h, List<NodeSim> nodes) {
        for (var round = 0; round < 20; round++) {
            for (var node : nodes) {
                for (var artifact : SLICES) {
                    node.follows(artifact);
                }
            }

            h.drain();
            removeUnloadedRows(h);
            h.drain();
            h.advanceBySeconds(TICK_SECONDS);
        }
    }

    private static void removeUnloadedRows(FsmHarness h) {
        for (var nodeId : List.of(CORE_1, CORE_2, CORE_3)) {
            for (var artifact : SLICES) {
                var key = new SliceObservedKey(nodeId, artifact);

                h.store.get(key)
                       .filter(SliceObservedValue.class::isInstance)
                       .map(SliceObservedValue.class::cast)
                       .filter(row -> row.state() == SliceObservedState.UNLOADED)
                       .onPresent(_ -> h.seedRemove(key));
            }
        }
    }

    private static void assertTerminalWithoutPromotion(FsmHarness h) {
        assertThat(outcomeStatus(h)).as("control: the blueprint was rolled back")
                                    .isEqualTo(Option.some(DeploymentOutcomeStatus.ROLLED_BACK));

        var terminal = firstTerminalStep(h);

        assertThat(terminal).as("control: a step carries the ROLLED_BACK outcome").isGreaterThanOrEqualTo(0);
        assertThat(activeGoalsFrom(h, terminal)).as("goal ACTIVE written at or after the ROLLED_BACK batch").isEmpty();
        assertThat(residue(h)).as("observed/desired rows left after the rollback").isEmpty();
    }

    private static int firstTerminalStep(FsmHarness h) {
        for (var i = 0; i < h.steps.size(); i++) {
            if (h.steps.get(i).stream().anyMatch(RollbackNoPromotionTest::writesRolledBack)) {
                return i;
            }
        }

        return -1;
    }

    private static boolean writesRolledBack(ClusterEffect effect) {
        return effect instanceof ClusterEffect.Submit submit && submit.transaction()
                                                                      .mutations()
                                                                      .stream()
                                                                      .anyMatch(mutation -> mutation.key() instanceof DeploymentOutcomeKey
                                                                                            && mutation.replacement()
                                                                                                       .filter(DeploymentOutcomeValue.class::isInstance)
                                                                                                       .map(DeploymentOutcomeValue.class::cast)
                                                                                                       .map(value -> value.status() == DeploymentOutcomeStatus.ROLLED_BACK)
                                                                                                       .or(false));
    }

    private static List<String> activeGoalsFrom(FsmHarness h, int from) {
        var found = new ArrayList<String>();

        for (var i = from; i < h.steps.size(); i++) {
            var step = i;

            for (var effect : h.steps.get(i)) {
                if (effect instanceof ClusterEffect.Submit submit) {
                    submit.transaction()
                          .mutations()
                          .stream()
                          .filter(mutation -> mutation.key() instanceof SliceDesiredKey)
                          .filter(mutation -> mutation.replacement()
                                                      .filter(SliceDesiredValue.class::isInstance)
                                                      .map(SliceDesiredValue.class::cast)
                                                      .map(goal -> goal.goal() == SliceGoal.ACTIVE)
                                                      .or(false))
                          .forEach(mutation -> found.add("step " + step + " " + mutation.key()));
                }
            }
        }

        return found;
    }

    private static List<String> residue(FsmHarness h) {
        var found = new ArrayList<String>();

        for (var nodeId : List.of(CORE_1, CORE_2, CORE_3)) {
            for (var artifact : SLICES) {
                h.store.get(new SliceObservedKey(nodeId, artifact)).onPresent(value -> found.add("observed " + nodeId.id() + " " + artifact + " " + value));
                h.store.get(new SliceDesiredKey(nodeId, artifact)).onPresent(value -> found.add("desired " + nodeId.id() + " " + artifact + " " + value));
            }
        }

        return found;
    }

    private static Option<DeploymentOutcomeStatus> outcomeStatus(FsmHarness h) {
        return h.store.get(new DeploymentOutcomeKey(blueprintId(APP)))
                      .filter(DeploymentOutcomeValue.class::isInstance)
                      .map(DeploymentOutcomeValue.class::cast)
                      .map(DeploymentOutcomeValue::status);
    }
}
