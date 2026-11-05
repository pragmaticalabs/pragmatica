// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT_B;

/// R-3.4.8 / NT-76: `step` emits no transition outside the N table. A seeded random walk drives the production FSM through
/// goals, step outcomes, lost and unnotified writes, timers and quorum cycles against the real applier, and every change the
/// applier made to the node's row is checked against [NodeTransitionTable] and the version chain.
class NodeFsmTableTest {
    private static final List<LocalStep> STEPS = List.of(LocalStep.LOAD,
                                                         LocalStep.ACTIVATE,
                                                         LocalStep.REGISTER_BRIDGE,
                                                         LocalStep.BUILD_LEGS,
                                                         LocalStep.REGISTER_CONFIG,
                                                         LocalStep.PUBLISH_ROUTES,
                                                         LocalStep.DESCRIBE_ACTIVE,
                                                         LocalStep.PREPARE_TEARDOWN,
                                                         LocalStep.UNPUBLISH,
                                                         LocalStep.DEACTIVATE,
                                                         LocalStep.UNLOAD);
    private static final List<Long> WAITS = List.of(1_000L, 2_000L, 30_000L, 31_000L, 90_000L, 120_000L, 150_000L);
    private static final java.util.Set<String> EDGES = new java.util.HashSet<>();
    private static final long SEEDS = 300L;
    private static final int STEPS_PER_WALK = 90;

    /// Red when: any write the FSM makes takes the committed row along an edge the spec's table does not have, skips a
    /// version, restarts a chain without a Remove, or names a state outside the observed vocabulary (ROUTING).
    @Test
    @DisplayName("NT-76 B-175: no emitted transition leaves the N table")
    void nt76_randomWalks_neverLeaveTheTable() {
        EDGES.clear();

        for (var seed = 0L; seed < SEEDS; seed++) {
            walk(seed);
        }

        assertThat(EDGES).as("the walk is not vacuous: it exercises the table's main edges")
                         .contains("none>LOADING",
                                   "LOADING>LOADED",
                                   "LOADED>ACTIVATING",
                                   "ACTIVATING>ACTIVE",
                                   "ACTIVE>DEACTIVATING",
                                   "DEACTIVATING>LOADED",
                                   "ACTIVE>UNLOADING",
                                   "UNLOADING>UNLOADED",
                                   "UNLOADED>removed",
                                   "LOADING>FAILED",
                                   "ACTIVE>ACTIVE");
    }

    private void walk(long seed) {
        var random = new Random(seed);
        var h = new NodeHarness();
        var generation = new long[] {1L};

        h.establish();
        h.permit(ARTIFACT).permit(ARTIFACT_B);

        for (var i = 0; i < STEPS_PER_WALK; i++) {
            act(random, h, generation);
        }

        check(h, seed);
        h.history.forEach(change -> EDGES.add(edge(change)));
    }

    private static String edge(NodeHarness.Applied change) {
        return change.before().map(row -> row.state().name()).or("none") + ">" + change.after().map(row -> row.state().name()).or("removed");
    }

    private void act(Random random, NodeHarness h, long[] generation) {
        switch (random.nextInt(11)) {
            case 0, 1, 2 -> h.goal(pick(random), goalOf(random), new Generation(5L, generation[0]++));
            case 3, 4 -> h.advance(WAITS.get(random.nextInt(WAITS.size())));
            case 5 -> h.script(STEPS.get(random.nextInt(STEPS.size())), outcomeOf(random));
            case 6 -> quorumCycle(h);
            case 7 -> h.writeMode = modeOf(random);
            case 8 -> h.removeGoal(pick(random));
            case 9 -> h.deliverCommitted(pick(random));
            default -> h.advance(30_000L);
        }
    }

    private static void quorumCycle(NodeHarness h) {
        h.feed(new NodeFsmInput.QuorumDisappeared());
        h.establish();
    }

    private static Artifact pick(Random random) {
        return random.nextBoolean() ? ARTIFACT : ARTIFACT_B;
    }

    private static SliceGoal goalOf(Random random) {
        return SliceGoal.values()[random.nextInt(3)];
    }

    private static LocalOutcome outcomeOf(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> LocalOutcome.failed(Causes.cause("boom"));
            case 1 -> LocalOutcome.timedOut();
            default -> LocalOutcome.done();
        };
    }

    private static NodeHarness.WriteMode modeOf(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> NodeHarness.WriteMode.LOST;
            case 1 -> NodeHarness.WriteMode.APPLY_NO_NOTIFY;
            case 2 -> NodeHarness.WriteMode.FAIL;
            default -> NodeHarness.WriteMode.APPLY;
        };
    }

    private void check(NodeHarness h, long seed) {
        for (var change : h.history) {
            var label = "seed " + seed + " " + change.artifact() + " " + change.before().map(SliceObservedValue::state) + " -> "
                        + change.after().map(SliceObservedValue::state);

            change.after().onPresent(after -> assertThat(NodeTransitionTable.allows(change.before().map(SliceObservedValue::state), after.state())).as(label).isTrue());
            change.after().onPresent(after -> assertThat(isSuccessor(change, after)).as(label + " at version " + after.version()).isTrue());
            change.after().onPresent(after -> assertThat(after.incarnation()).as(label).isEqualTo(h.process));
            assertThat(change.after().isPresent() || change.before().map(row -> row.state() == SliceObservedState.UNLOADED).or(false))
                .as(label + ": only a tombstone is ever Removed")
                .isTrue();
        }
    }

    /// The next version of a committed row; on an absent key any version lands as a first write (L-08, spec 6.4): the fence
    /// only guards a key that exists.
    private static boolean isSuccessor(NodeHarness.Applied change, SliceObservedValue after) {
        return change.before().map(before -> after.version() == before.version() + 1L).or(after.version() >= 1L);
    }

    /// The oracle itself rejects the edges the spec never draws, so a test that passes is not passing a table that allows all.
    @Test
    @DisplayName("NT-76 B-175: the oracle rejects the edges the table does not have")
    void nt76_oracle_rejectsForbiddenEdges() {
        assertThat(NodeTransitionTable.allows(Option.none(), SliceObservedState.ACTIVE)).isFalse();
        assertThat(NodeTransitionTable.allows(Option.some(SliceObservedState.LOADED), SliceObservedState.ACTIVE)).isFalse();
        assertThat(NodeTransitionTable.allows(Option.some(SliceObservedState.LOADING), SliceObservedState.ACTIVATING)).isFalse();
        assertThat(NodeTransitionTable.allows(Option.some(SliceObservedState.ACTIVE), SliceObservedState.UNLOADED)).isFalse();
        assertThat(NodeTransitionTable.allows(Option.some(SliceObservedState.ACTIVE), SliceObservedState.DEACTIVATING)).isTrue();
    }
}
