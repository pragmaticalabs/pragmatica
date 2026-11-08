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
import org.pragmatica.consensus.NodeId;
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
    private static final Artifact INVENTORY = artifact("org.example:inventory:1.0.0");
    private static final List<Artifact> SLICES = List.of(ORDERS, BILLING, INVENTORY);

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

    // Red when: the unload of a removed blueprint is dropped at the flush because the instance's row moved in that drain, twice (the
    // blueprint removal, then the target removal), and only the orphan audit, every tenth tick, derives it again (dev7: observed and
    // desired rows of the healthy slice left on one node, MON-i live=1 bound=0). The second move is a re-report of the same state.
    @Test
    void unloadDroppedByMovedRow_isDerivedAgainBeforeTheAudit() {
        var h = active();
        var nodes = nodes(h);
        var racer = nodes.getLast();

        rollbackHeld(h, nodes);
        releaseHeld(h, () -> racer.writes(BILLING, SliceObservedState.LOADED));
        assertThat(h.submits(SubmitKind.BLUEPRINT_REMOVAL)).as("control: the blueprint removal is in flight").hasSize(1);
        releaseHeld(h, () -> racer.writes(BILLING, SliceObservedState.LOADED));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        assertThat(goal(h, CORE_3, BILLING)).as("control: both unloads of the racing instance were dropped")
                                            .isEqualTo(Option.some(SliceGoal.LOADED));

        quiesce(h, nodes, 3);
        assertTerminalWithoutPromotion(h);
    }

    // Red when: the unload of a removed blueprint is refused because the instance's row moved before it applied, the moved pair is
    // released to the next reconcile, and only the orphan audit, every tenth tick, derives it again.
    @Test
    void unloadRefusedByMovedRow_isDerivedAgainBeforeTheAudit() {
        var h = active();
        var nodes = nodes(h);
        var racer = nodes.getLast();

        rollbackHeld(h, nodes);
        releaseHeld(h, () -> {});
        racer.writes(BILLING, SliceObservedState.LOADED);
        h.drain();
        releaseHeld(h, () -> {});
        h.policy = FsmHarness.SettlePolicy.APPLY;
        assertThat(goal(h, CORE_3, BILLING)).as("control: the unload of the racing instance was refused")
                                            .isEqualTo(Option.some(SliceGoal.LOADED));

        quiesce(h, nodes, 3);
        assertTerminalWithoutPromotion(h);
    }

    // Pins the gate's reach (#2864): a blueprint that does not declare the rolled-back artifacts, deployed while the rollback is in
    // flight, still promotes. Red when: the gate drops goal ACTIVE for every artifact while any rollback is pending.
    @Test
    void unrelatedBlueprintDuringRollback_stillPromotes() {
        var h = active();
        var nodes = nodes(h);

        rollbackHeld(h, nodes);
        publish(h, blueprint("org.example:other:1.0.0", false, "attempt-2", slice(INVENTORY, 3, 1, Set.of())));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        List.copyOf(h.held.values()).forEach(submit -> h.settle(submit, h.applyHeld(submit.correlationId())));
        h.drain();

        quiesce(h, nodes);
        assertActiveOnAllCores(h, INVENTORY);
    }

    // Pins the gate's end (#2864): once the rollback has settled and its targets are removed, a fresh deployment of the rolled-back
    // artifact promotes. Red when: the gate outlives the rollback (the in-flight draft or the owner test never clears).
    @Test
    void freshDeploymentAfterRollback_promotes() {
        var h = active();
        var nodes = nodes(h);

        publish(h, BLUEPRINT);
        nodes.forEach(node -> node.follows(BILLING));
        nodes.getFirst().fails(ORDERS, true);
        h.drain();
        quiesce(h, nodes);
        assertTerminalWithoutPromotion(h);

        publish(h, blueprint("org.example:app:1.0.1", false, "attempt-3", slice(BILLING, 3, 1, Set.of())));
        quiesce(h, nodes);

        assertActiveOnAllCores(h, BILLING);
    }

    private static void assertActiveOnAllCores(FsmHarness h, Artifact artifact) {
        for (var nodeId : List.of(CORE_1, CORE_2, CORE_3)) {
            assertThat(goal(h, nodeId, artifact)).as("goal of " + artifact + " on " + nodeId.id()).isEqualTo(Option.some(SliceGoal.ACTIVE));
        }
    }

    /// The healthy slice is LOADING on every node and ORDERS fails fatally on the first; every write from here on is held.
    private static void rollbackHeld(FsmHarness h, List<NodeSim> nodes) {
        publish(h, BLUEPRINT);
        nodes.forEach(node -> node.writes(BILLING, SliceObservedState.LOADING));
        h.drain();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        nodes.getFirst().fails(ORDERS, true);
        h.drain();
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("control: the rollback is in flight").hasSize(1);
    }

    /// The held writes commit, `between` runs after their notifications are queued and before their settlements, then one drain.
    private static void releaseHeld(FsmHarness h, Runnable between) {
        var held = List.copyOf(h.held.values());
        var settlements = held.stream().map(submit -> h.applyHeld(submit.correlationId())).toList();

        between.run();

        for (var i = 0; i < held.size(); i++) {
            h.settle(held.get(i), settlements.get(i));
        }

        h.drain();
    }

    private static Option<SliceGoal> goal(FsmHarness h, NodeId nodeId, Artifact artifact) {
        return h.store.get(new SliceDesiredKey(nodeId, artifact))
                      .filter(SliceDesiredValue.class::isInstance)
                      .map(SliceDesiredValue.class::cast)
                      .map(SliceDesiredValue::goal);
    }

    private static List<NodeSim> nodes(FsmHarness h) {
        return List.of(node(h, CORE_1, 11), node(h, CORE_2, 12), node(h, CORE_3, 13));
    }

    /// The nodes follow their goals, and remove their observed row once it reports UNLOADED, as the node FSM does.
    private static void quiesce(FsmHarness h, List<NodeSim> nodes) {
        quiesce(h, nodes, 20);
    }

    /// As [#quiesce(FsmHarness, List)], for `ticks` reconcile ticks.
    private static void quiesce(FsmHarness h, List<NodeSim> nodes, int ticks) {
        for (var round = 0; round < ticks; round++) {
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
