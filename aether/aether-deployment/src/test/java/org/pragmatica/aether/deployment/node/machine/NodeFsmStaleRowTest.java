// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G3;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.OTHER_PROCESS_TOKEN;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.SELF_TOKEN;

/// N0, the own-row incarnation check at Rebuild (spec R-3.4.1; NT-123 (c), (d), (e); NT-47's same-process arm). The stale rows
/// are written into the REAL applier, so an overwrite that is not exactly version + 1 is refused and shows as a missing row.
class NodeFsmStaleRowTest {
    private static final List<SliceObservedState> ROW_STATES = List.of(SliceObservedState.LOADING,
                                                                      SliceObservedState.LOADED,
                                                                      SliceObservedState.ACTIVATING,
                                                                      SliceObservedState.ACTIVE,
                                                                      SliceObservedState.DEACTIVATING,
                                                                      SliceObservedState.UNLOADING);
    private static final long STALE_VERSION = 5L;

    private static long staleRowMetrics(NodeHarness h) {
        return h.effectsOf(NodeFsmEffect.Metric.class)
                .stream()
                .filter(metric -> metric.name().equals("aether_deployment_fsm_stale_rows_total"))
                .filter(metric -> "node".equals(metric.labels().get("side")))
                .mapToLong(NodeFsmEffect.Metric::delta)
                .sum();
    }

    private static boolean removesAnything(NodeHarness h) {
        return h.effectsOf(NodeFsmEffect.Submit.class)
                .stream()
                .anyMatch(submit -> submit.commands().stream().anyMatch(KVCommand.Remove.class::isInstance));
    }

    private NodeHarness restarted(SliceObservedState rowState, SliceGoal goal) {
        var h = new NodeHarness();

        h.seedRow(ARTIFACT, rowState, OTHER_PROCESS_TOKEN, STALE_VERSION, G1);

        if (goal != null) {
            h.permit(ARTIFACT).goal(ARTIFACT, goal, G3);
        }

        return h.establish();
    }

    /// Red when: N0 does not overwrite a stale row at version + 1 with this process's incarnation (LOADING while a goal asks),
    /// removes it instead, or does not count it (`stale_rows_total{side=node}`). Every row state is tried.
    @Test
    @DisplayName("NT-123 (c) N0: a stale row under a goal is overwritten with LOADING at version + 1")
    void nt123c_staleRowWithAGoal_isOverwrittenAsLoading() {
        for (var goal : List.of(SliceGoal.ACTIVE, SliceGoal.LOADED)) {
            for (var rowState : ROW_STATES) {
                var h = restarted(rowState, goal);
                var first = h.observedPuts().getFirst();
                var label = goal + " over a stale " + rowState;

                assertThat(first.state()).as(label).isEqualTo(SliceObservedState.LOADING);
                assertThat(first.version()).as(label).isEqualTo(STALE_VERSION + 1L);
                assertThat(first.incarnation().bootToken()).as(label).isEqualTo(SELF_TOKEN);
                assertThat(first.forGeneration()).as(label).isEqualTo(G3);
                assertThat(removesAnything(h)).as(label).isFalse();
                assertThat(staleRowMetrics(h)).as(label).isEqualTo(1L);
                assertThat(h.observed(ARTIFACT).map(SliceObservedValue::incarnation).map(process -> process.bootToken()).or(-1L)).as(label).isEqualTo(SELF_TOKEN);
                assertThat(h.state(ARTIFACT).or(SliceObservedState.UNKNOWN)).as(label)
                                                                            .isEqualTo(goal == SliceGoal.ACTIVE
                                                                                       ? SliceObservedState.ACTIVE
                                                                                       : SliceObservedState.LOADED);
            }
        }
    }

    /// Red when: with no goal, N0 does not overwrite the stale row with the tombstone at version + 1, removes it, or does not
    /// arm the tombstone grace.
    @Test
    @DisplayName("NT-123 (c) N0: a stale row with no goal is overwritten with the UNLOADED tombstone at version + 1")
    void nt123c_staleRowWithNoGoal_isOverwrittenAsTheTombstone() {
        for (var rowState : ROW_STATES) {
            var h = restarted(rowState, null);
            var first = h.observedPuts().getFirst();

            assertThat(first.state()).as(rowState.name()).isEqualTo(SliceObservedState.UNLOADED);
            assertThat(first.version()).as(rowState.name()).isEqualTo(STALE_VERSION + 1L);
            assertThat(first.incarnation().bootToken()).as(rowState.name()).isEqualTo(SELF_TOKEN);
            assertThat(removesAnything(h)).as(rowState.name()).isFalse();
            assertThat(staleRowMetrics(h)).as(rowState.name()).isEqualTo(1L);
            assertThat(h.timersOf(TimerId.TimerKind.TOMBSTONE_GRACE)).as(rowState.name()).hasSize(1);
        }
    }

    /// Red when: a goal of UNLOADED over a stale row does not become the tombstone (the unload chain would try to tear down a
    /// slice this process never loaded).
    @Test
    @DisplayName("NT-123 (c) N0: a stale row under the UNLOADED goal is overwritten with the tombstone")
    void nt123c_staleRowUnderUnloadedGoal_isOverwrittenAsTheTombstone() {
        var h = restarted(SliceObservedState.ACTIVE, SliceGoal.UNLOADED);

        assertThat(h.writtenStates()).containsExactly(SliceObservedState.UNLOADED);
        assertThat(h.localSteps()).isEmpty();
        assertThat(h.observedPuts().getFirst().version()).isEqualTo(STALE_VERSION + 1L);
    }

    /// Red when: N0 writes anything for a row of the same process (partition heal), or counts it stale, or the row with an
    /// unknown token (0) is treated as stale. The applied generation is corroborated.
    @Test
    @DisplayName("NT-123 (d) N0: rows of the same process, or written without a token, are current and nothing is written")
    void nt123d_sameProcessRows_areCurrent() {
        for (var token : List.of(SELF_TOKEN, 0L)) {
            var h = new NodeHarness();

            h.seedRow(ARTIFACT, SliceObservedState.ACTIVE, token, STALE_VERSION, G1);
            h.slicesInStore.add(ARTIFACT);
            h.permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1).establish();

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("token " + token).isEmpty();
            assertThat(staleRowMetrics(h)).as("token " + token).isZero();
            assertThat(h.localOps()).as("token " + token).isEmpty();
            assertThat(h.record(ARTIFACT).appliedGeneration().or(G3)).as("token " + token).isEqualTo(G1);
            assertThat(h.record(ARTIFACT).lastVersion()).isEqualTo(STALE_VERSION);
        }
    }

    /// Red when: a node without a boot token overwrites any row, or does not raise `boot-token-absent` exactly once.
    @Test
    @DisplayName("NT-123 (e) L-17: with no boot token N0 is skipped and boot-token-absent is raised once")
    void nt123e_tokenLessNode_skipsN0AndWarns() {
        var h = new NodeHarness(0L);

        h.seedRow(ARTIFACT, SliceObservedState.LOADED, OTHER_PROCESS_TOKEN, STALE_VERSION, G1);
        h.slicesInStore.add(ARTIFACT);
        h.permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1).establish();

        assertThat(h.tokens.self()).isZero();
        assertThat(h.effectsOf(NodeFsmEffect.Warn.class).stream().filter(warn -> warn.code().equals("boot-token-absent"))).hasSize(1);
        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("the row is taken as this process's own").isEmpty();
        assertThat(staleRowMetrics(h)).isZero();

        var noGoal = new NodeHarness(0L);

        noGoal.seedRow(ARTIFACT, SliceObservedState.ACTIVE, OTHER_PROCESS_TOKEN, STALE_VERSION, G1).establish();

        assertThat(noGoal.effectsOf(NodeFsmEffect.Submit.class)).as("no tombstone overwrite without a token").isEmpty();
        assertThat(noGoal.effectsOf(NodeFsmEffect.Warn.class).stream().filter(warn -> warn.code().equals("boot-token-absent"))).hasSize(1);
    }
}
