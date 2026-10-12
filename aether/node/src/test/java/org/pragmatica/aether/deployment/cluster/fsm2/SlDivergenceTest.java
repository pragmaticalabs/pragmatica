// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Goal/state divergence (6.1, C2): the re-issue at T and 2T, the retraction after 3T, and the clocks that decide the age.
/// NT-31, 32, 33, 34.
class SlDivergenceTest {
    private static final long T_LOAD = 120_000L;
    private static final long T_DEACTIVATE = 30_000L;
    private static final long T_UNLOAD = 120_000L;

    /// The pair core-1 / ORDERS, placed at t0 and stuck in LOADING. Returns the harness at t0.
    private FsmHarness stuckLoading() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        h.activate();
        node(h, CORE_1, 11L).writes(ORDERS, SliceObservedState.LOADING);
        h.drain();

        return h;
    }

    private FsmHarness at(FsmHarness h, long ms) {
        h.advance(ms);

        return h.deliver(new ClusterInput.Reconcile());
    }

    private long judgmentWrites(FsmHarness h) {
        return h.submits()
                .stream()
                .flatMap(s -> s.transaction()
                               .mutations()
                               .stream())
                .filter(m -> m.key() instanceof ArtifactJudgmentKey)
                .count();
    }

    private long reissues(FsmHarness h) {
        return h.submits()
                .stream()
                .flatMap(s -> s.transaction()
                               .mutations()
                               .stream())
                .filter(m -> m.key()
                              .equals(new SliceDesiredKey(CORE_1, ORDERS)) && m.expected()
                                                                               .equals(m.replacement()))
                .count();
    }

    private boolean isRetraction(FsmHarness h) {
        return h.submits()
                .stream()
                .anyMatch(s -> s.transaction()
                                .mutations()
                                .stream()
                                .anyMatch(m -> m.key() instanceof ArtifactJudgmentKey)
                               && s.transaction()
                                   .mutations()
                                   .stream()
                                   .anyMatch(m -> m.key()
                                                   .equals(new SliceDesiredKey(CORE_1, ORDERS)) && m.replacement()
                                                                                                    .map(v -> ((SliceDesiredValue) v).goal() == SliceGoal.UNLOADED)
                                                                                                    .or(false)));
    }

    @Nested
    class Loading {
        /// NT-31, NT-34 boundary and re-issue arms. Red when: at 3T + 1 ms there is no retraction plus `unresponsive` in ONE
        /// transaction; the 3T boundary is off (a retraction at 3T or 3T - 1 ms); the age resets on a re-issue (no retraction at 3T + 1
        /// ms after the re-issues at T and 2T); or a re-issue is repeated within a threshold.
        @Test
        void loading_isRetractedAfter3T_andNotBefore() {
            var h = stuckLoading();

            at(h, T_LOAD - 1);
            assertThat(reissues(h)).as("nothing before T").isZero();
            at(h, 1);
            assertThat(reissues(h)).as("not at exactly T").isZero();
            at(h, 1);
            assertThat(reissues(h)).as("exactly one re-issue after T").isEqualTo(1);
            at(h, T_LOAD + 10_000L);
            assertThat(reissues(h)).as("and one at 2T, none repeated").isEqualTo(2);
            at(h, T_LOAD - 10_002L);
            assertThat(judgmentWrites(h)).as("at 3T - 1 ms: nothing").isZero();
            at(h, 1);
            assertThat(judgmentWrites(h)).as("at 3T: nothing (strictly more than 3T)").isZero();
            at(h, 1);
            assertThat(isRetraction(h)).as("at 3T + 1 ms: goal UNLOADED and the judgment in one transaction").isTrue();
            assertThat(h.model().judgment(ORDERS).map(ArtifactJudgmentValue::unresponsive).or(Map.of())).containsKey(CORE_1);
            assertThat(h.warns("slice-unresponsive")).hasSize(1);
        }
    }

    @Nested
    class Deactivating {
        /// NT-32. Red when: at 90 s + 1 ms there is no retraction, or any observed removal is submitted for this live node.
        @Test
        void deactivating_isRetractedAfter90s_withoutRemovingTheRow() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.DEACTIVATING, G1, 0L);
            h.activate();
            at(h, 3 * T_DEACTIVATE);
            assertThat(judgmentWrites(h)).as("not at exactly 90 s").isZero();
            at(h, 1);
            assertThat(isRetraction(h)).isTrue();
            assertThat(h.submits()
                        .stream()
                        .flatMap(s -> s.transaction()
                                       .mutations()
                                       .stream())
                        .filter(m -> m.key() instanceof SliceObservedKey)).isEmpty();
        }
    }

    @Nested
    class Unloading {
        /// NT-33. Red when: at 6 min + 1 ms there is no `unresponsive` record, a generation bump is written, or any observed removal is submitted.
        @Test
        void unloading_isRecordedAfter3T_withoutABumpOrARemoval() {
            var h = threeCores();

            seedGoal(h, CORE_1, ORDERS, SliceGoal.UNLOADED, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.UNLOADING, G1, 0L);
            h.activate();
            at(h, 3 * T_UNLOAD);
            assertThat(judgmentWrites(h)).isZero();
            at(h, 1);
            assertThat(h.model().judgment(ORDERS).map(ArtifactJudgmentValue::unresponsive).or(Map.of())).containsKey(CORE_1);
            assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS)).map(SliceDesiredValue::generation)).as("no generation bump")
                      .isEqualTo(Option.some(G1));
            assertThat(h.submits()
                        .stream()
                        .flatMap(s -> s.transaction()
                                       .mutations()
                                       .stream())
                        .filter(m -> m.key() instanceof SliceDesiredKey && !m.expected()
                                                                             .equals(m.replacement()) || m.key() instanceof SliceObservedKey)).as("only identical re-issues touch the goal")
                      .isEmpty();
        }
    }

    @Nested
    class Clocks {
        /// NT-34 skew arm. Red when: a monotonic clock that ran far ahead of the wall clock retracts the pair (the age is the minimum of both).
        @Test
        void monotonicLeap_withoutTheWallClock_retractsNothing() {
            var h = stuckLoading();

            h.monoNs += 10 * T_LOAD * 1_000_000L;
            h.deliver(new ClusterInput.Reconcile());
            assertThat(judgmentWrites(h)).isZero();
            assertThat(reissues(h)).isZero();
        }

        /// NT-34 wall-step arm. Red when: a wall clock step forward (or back) retracts the pair on the strength of the wall term alone.
        @Test
        void wallStep_withoutTheMonotonicClock_retractsNothing() {
            var h = stuckLoading();

            h.wallMs += 10 * T_LOAD;
            h.deliver(new ClusterInput.Reconcile());
            h.wallMs -= 20 * T_LOAD;
            h.deliver(new ClusterInput.Reconcile());
            h.wallMs += 10 * T_LOAD;
            assertThat(judgmentWrites(h)).isZero();
            assertThat(reissues(h)).isZero();
        }

        /// NT-34 admission arm. Red when: a healed node is re-issued before T, or retracted before 3T, after its re-admission.
        @Test
        void readmission_restartsTheClock() {
            var h = stuckLoading();

            at(h, T_LOAD - 10_000L);
            h.deliver(new ClusterInput.NodeJoined(CORE_1, admission(2L, 11L)));
            at(h, 20_000L);
            assertThat(reissues(h)).as("T has passed since the goal but not since the re-admission").isZero();
            at(h, 3 * T_LOAD - T_LOAD - 30_000L);
            assertThat(judgmentWrites(h)).as("3T has passed since the goal but not since the re-admission").isZero();
            assertThat(reissues(h)).as("T since the re-admission has passed: one re-issue").isLessThanOrEqualTo(2);
            at(h, T_LOAD * 2L);
            assertThat(isRetraction(h)).as("3T after the re-admission").isTrue();
        }

        /// NT-34 CI-15b arm. Red when: a pair whose row turns stale on learning a token is retracted before 3T from that moment.
        @Test
        void learningATokenThatMakesTheRowStale_restartsTheClock() {
            var h = stuckLoading();
            var tokens = BootTokens.bootTokens(1L);

            h.admissions.put(CORE_1, admission(1L, 0L));
            h.deliver(new ClusterInput.NodeJoined(CORE_1, admission(1L, 0L)));
            at(h, 2 * T_LOAD);
            tokens.admit(CORE_1, 222L);
            var learnedAt = h.monoNs;

            h.deliver(new ClusterInput.ProcessKnown(CORE_1, tokens.tokenOf(CORE_1)));
            assertThat(h.model()
                        .observed(SlicePair.slicePair(CORE_1, ORDERS))
                        .map(row -> Rows.isStale(h.model(),
                                                 SlicePair.slicePair(CORE_1, ORDERS),
                                                 row))).as("a different known token: the row turns stale")
                      .isEqualTo(Option.some(true));
            at(h, 3 * T_LOAD - 1);
            assertThat(judgmentWrites(h)).as("before 3T from the moment the token was learned").isZero();
            at(h, 2);
            assertThat(h.monoNs - learnedAt).isGreaterThan(3 * T_LOAD * 1_000_000L);
            assertThat(isRetraction(h)).isTrue();
        }
    }
}
