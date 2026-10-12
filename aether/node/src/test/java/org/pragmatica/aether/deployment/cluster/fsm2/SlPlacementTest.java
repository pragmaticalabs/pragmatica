// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect.Warn;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;

/// Placement per committed target (reconcile step 3), on the real applier. NT-05, 06, 10, 12, 13, 14, 15, 16, 29, 41, 43, 104.
class SlPlacementTest {
    private static final Artifact X1 = artifact("org.example:x1:1.0.0");
    private static final Artifact X2 = artifact("org.example:x2:1.0.0");
    private static final Artifact X3 = artifact("org.example:x3:1.0.0");
    private static final Artifact X4 = artifact("org.example:x4:1.0.0");

    @Nested
    class Least {
        /// NT-13. Red when: with loads {core-1:2, core-2:0, core-3:1} a scale +1 does not pick core-2.
        @Test
        void place_picksTheLeastLoadedNode() {
            var h = threeCores();

            seedGoal(h, CORE_1, X1, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_1, X2, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_3, X3, SliceGoal.LOADED, G1);
            seedTarget(h, ORDERS, 1, 1);

            h.activate();

            assertThat(hosts(h, ORDERS)).containsExactly(CORE_2);
        }

        /// NT-13. Red when: with equal loads the smallest id is not picked (ties by NodeId).
        @Test
        void place_breaksTiesByNodeId() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            h.activate();

            assertThat(hosts(h, ORDERS)).containsExactly(CORE_1, CORE_2);
        }
    }

    @Nested
    class Occupation {
        private FsmHarness twoCores() {
            return harness(CORE_1, CORE_2);
        }

        private void stalePrefersFirst(FsmHarness h) {
            seedTarget(h, ORDERS, 1, 1);
            h.activate();
        }

        /// NT-14. Red when: a node holding a current LOADING..DEACTIVATING row under goal UNLOADED (or FAILED, or UNLOADING)
        /// is chosen although core-2 is vacant.
        @Test
        void place_skipsANodeWhoseRowOccupiesThePair() {
            for (var state : List.of(SliceObservedState.LOADING, SliceObservedState.LOADED, SliceObservedState.ACTIVATING, SliceObservedState.ACTIVE, SliceObservedState.DEACTIVATING, SliceObservedState.FAILED, SliceObservedState.UNLOADING)) {
                var h = twoCores();

                seedGoal(h, CORE_1, ORDERS, SliceGoal.UNLOADED, G1);
                seedRow(h, CORE_1, ORDERS, state, G1, 0L);
                stalePrefersFirst(h);

                assertThat(hosts(h, ORDERS)).as("row state %s occupies", state).containsExactly(CORE_2);
            }
        }

        /// NT-14. Red when: a goal LOADED/ACTIVE with no observation yet does not occupy (the node is picked a second time, or the count is exceeded).
        @Test
        void place_countsAGoalWithNoObservationAsPlaced() {
            var h = twoCores();

            seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, G1);
            seedTarget(h, ORDERS, 1, 1);
            h.activate();
            h.advanceBySeconds(10);

            assertThat(hosts(h, ORDERS)).containsExactly(CORE_1);
        }

        /// NT-14. Red when: an `unresponsive` node is chosen.
        @Test
        void place_skipsAnUnresponsiveNode() {
            var h = twoCores();

            seedJudgment(h, ORDERS, new ArtifactJudgmentValue(false, java.util.Map.of(), java.util.Map.of(CORE_1, new UnresponsiveEntry(G1, SliceGoal.ACTIVE, FsmHarness.START_WALL))));
            stalePrefersFirst(h);

            assertThat(hosts(h, ORDERS)).containsExactly(CORE_2);
        }

        /// NT-14. Red when: a row stale on positive evidence blocks placement (the node is skipped).
        @Test
        void place_isNotBlockedByAStaleRow() {
            var h = twoCores();

            h.admissions.put(CORE_1, admission(2L, 7L));
            seedGoal(h, CORE_1, ORDERS, SliceGoal.UNLOADED, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.UNLOADING, G1, 8L);
            stalePrefersFirst(h);

            assertThat(hosts(h, ORDERS)).as("the stale row reads as absent, so core-1 (smallest id) is vacant").containsExactly(CORE_1);
        }
    }

    @Nested
    class ScaleDown {
        /// NT-15. Red when: scale-down picks a victim off the least-loaded node, or goes below minInstances.
        @Test
        void scaleDown_takesTheMostLoadedNodeFirst() {
            var h = threeCores();

            seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_2, ORDERS, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_3, ORDERS, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_2, X1, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_2, X2, SliceGoal.LOADED, G1);
            seedTarget(h, ORDERS, 2, 1);
            h.activate();

            assertThat(goalOf(h, CORE_2, ORDERS)).as("the most-loaded node is the victim").isEqualTo(Option.some(SliceGoal.UNLOADED));
            assertThat(hosts(h, ORDERS)).containsExactly(CORE_1, CORE_3);
        }

        /// NT-15. Red when: the count falls below minInstances.
        @Test
        void scaleDown_stopsAtMinInstances() {
            var h = threeCores();

            seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_2, ORDERS, SliceGoal.LOADED, G1);
            seedGoal(h, CORE_3, ORDERS, SliceGoal.LOADED, G1);
            seedTarget(h, ORDERS, 1, 2);
            h.activate();

            assertThat(hosts(h, ORDERS)).as("3 placed, target 1, min 2: one goes").hasSize(2);
        }
    }

    @Nested
    class Validity {
        /// NT-16. Red when: an invalid placement yields a goal, or no WARN (the ERROR of the old code) names it.
        @Test
        void place_refusesAnInvalidPlacement_withAWarning() {
            var h = threeCores();

            h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
            h.activate();

            assertThat(countGoals(h, ORDERS)).isZero();
            assertThat(h.warns("invalid-placement")).isNotEmpty();
        }
    }

    @Nested
    class Rebuild {
        /// NT-05. Red when: a permanently failed artifact gets a goal in the first reconcile after a leader change.
        @Test
        void rebuild_doesNotPlaceAPermanentlyFailedArtifact() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            seedJudgment(h, ORDERS, new ArtifactJudgmentValue(true, java.util.Map.of(), java.util.Map.of()));
            h.activate();

            assertThat(countGoals(h, ORDERS)).isZero();
            assertThat(h.submits()).isEmpty();
        }

        /// NT-06. Red when: an observed LOADED at rebuild gets no ACTIVE goal.
        @Test
        void rebuild_activatesALoadedRow() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADED, G1, 0L);
            h.activate();

            assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.ACTIVE));
        }

        /// NT-43. Red when: a committed target that no blueprint of the view names gets no goal.
        @Test
        void rebuild_placesACommittedTarget_withoutItsBlueprint() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            h.activate();

            assertThat(hosts(h, ORDERS)).hasSize(2);
        }
    }

    @Nested
    class PerNodeTransactions {
        /// NT-43. Red when: one node's refused transaction voids another node's goals (they are separate transactions).
        @Test
        void refusal_ofOneNodesGoals_doesNotVoidTheOthers() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            h.policy = FsmHarness.SettlePolicy.HOLD;
            h.activate();
            var held = List.copyOf(h.held.values());

            assertThat(held).as("one transaction per node").hasSize(2);
            h.seed(new SliceDesiredKey(CORE_1, ORDERS), Fixtures.desired(SliceGoal.UNLOADED, new org.pragmatica.aether.slice.kvstore.AetherValue.Generation(1L, 99L)));
            h.policy = FsmHarness.SettlePolicy.APPLY;
            var verdicts = new java.util.ArrayList<ClusterInput.Settlement>();

            for (var submit : held) {
                var verdict = h.applyTransaction(submit);

                verdicts.add(verdict);
                h.settleHeld(submit, verdict);
            }

            assertThat(verdicts).as("exactly core-1's transaction is refused").filteredOn(v -> v instanceof ClusterInput.Settlement.Refused).hasSize(1);
            assertThat(verdicts).filteredOn(v -> v instanceof ClusterInput.Settlement.Accepted).hasSize(1);
            assertThat(goalOf(h, CORE_2, ORDERS)).as("core-2's goals committed").isEqualTo(Option.some(SliceGoal.LOADED));
            assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS)).map(SliceDesiredValue::generation).or(G1))
                    .as("core-1 was re-derived above the foreign generation").isGreaterThan(new org.pragmatica.aether.slice.kvstore.AetherValue.Generation(1L, 99L));
        }
    }

    @Nested
    class VersionChange {
        /// NT-10. Red when: old-version instances keep a non-UNLOADED goal after a new-version target (no rolling update).
        @Test
        void newTarget_unloadsTheOldVersion() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            seedGoal(h, CORE_2, ORDERS, SliceGoal.ACTIVE, G1);
            h.activate();
            h.seed(new SliceTargetKey(ORDERS_V2.base()), target(ORDERS_V2, 2, 1, "CORE_ONLY"));
            h.drain();

            assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
            assertThat(goalOf(h, CORE_2, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
            assertThat(hosts(h, ORDERS_V2)).as("the new version is placed").hasSize(2);
        }

        /// NT-10 control. Red when: during a rolling update (a routing exists) the old version is unloaded by the target change.
        @Test
        void newTarget_keepsTheOldVersion_duringARollingUpdate() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            h.seedQuietly(new VersionRoutingKey(ORDERS.base()), new VersionRoutingValue(ORDERS.version(), ORDERS_V2.version(), 10, 90, FsmHarness.START_WALL));
            h.activate();
            h.seed(new SliceTargetKey(ORDERS_V2.base()), target(ORDERS_V2, 2, 1, "CORE_ONLY"));
            h.drain();

            assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.ACTIVE));
        }

        /// NT-12. Red when: a routing remove leaves a non-current version without goal UNLOADED.
        @Test
        void routingRemove_unloadsTheNonCurrentVersions() {
            var h = threeCores();

            seedTarget(h, ORDERS_V2, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            seedGoal(h, CORE_2, ORDERS_V2, SliceGoal.ACTIVE, G1);
            h.seedQuietly(new VersionRoutingKey(ORDERS.base()), new VersionRoutingValue(ORDERS.version(), ORDERS_V2.version(), 100, 0, FsmHarness.START_WALL));
            h.activate();
            assertThat(goalOf(h, CORE_1, ORDERS)).as("the rolling update keeps v1").isEqualTo(Option.some(SliceGoal.ACTIVE));

            h.seedRemove(new VersionRoutingKey(ORDERS.base()));
            h.drain();

            assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
            assertThat(goalOf(h, CORE_2, ORDERS_V2)).as("the current version stays").isEqualTo(Option.some(SliceGoal.ACTIVE));
        }
    }

    @Nested
    class Draining {
        /// NT-29. Red when: a blueprint with an instance on a draining node is placed by the reconcile (a second instance
        /// appears beside the drain's replacement), or no ReconciliationAdjustment is routed for the replacement.
        @Test
        void drain_placesTheReplacement_andRoutesAnAdjustment() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G1, 0L);
            h.seams = h.seams.withDraining(Set.of(CORE_1));
            h.activate();

            assertThat(hosts(h, ORDERS)).as("the original plus exactly one replacement").containsExactly(CORE_1, CORE_2);
            assertThat(h.effects(ClusterEffect.Route.class).stream()
                        .map(ClusterEffect.Route::event)
                        .filter(RoutedEvent.ReconciliationAdjustment.class::isInstance)
                        .map(RoutedEvent.ReconciliationAdjustment.class::cast)
                        .filter(adjustment -> adjustment.artifact().equals(ORDERS)))
                    .as("one adjustment, the drain's: the reconcile did not place the blueprint on its own")
                    .singleElement()
                    .satisfies(adjustment -> {
                        assertThat(adjustment.current()).isEqualTo(1);
                        assertThat(adjustment.desired()).isEqualTo(2);
                    });
        }

        /// NT-41. Red when: after the replacement is ACTIVE the original gets no goal UNLOADED, or no 2 s next step is scheduled.
        @Test
        void drain_unloadsTheOriginal_afterTheReplacementIsActive() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G1, 0L);
            h.seams = h.seams.withDraining(Set.of(CORE_1));
            h.activate();

            assertThat(h.effects(ClusterEffect.Schedule.class).stream()
                        .filter(schedule -> schedule.timer().kind() == TimerId.Kind.DRAIN_STEP)).as("a 2 s next step")
                    .anyMatch(schedule -> schedule.delayMs() == 2_000L);

            node(h, CORE_2, 22L).follows(ORDERS);
            h.drain();
            node(h, CORE_2, 22L).follows(ORDERS);
            h.drain();
            h.advance(2_000L);

            assertThat(stateOf(h, CORE_2, ORDERS)).isEqualTo(SliceObservedState.ACTIVE);
            assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
        }

        /// NT-41 (repeats). Red when: a repeated drain report starts a second loop and issues a second replacement.
        @Test
        void drainReport_repeatsDoNotDoubleIssue() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G1, 0L);
            h.seams = h.seams.withDraining(Set.of(CORE_1));
            h.activate();
            h.deliver(new ClusterInput.NodeDraining(CORE_1));
            h.deliver(new ClusterInput.NodeDraining(CORE_1));
            h.advance(2_000L);

            assertThat(hosts(h, ORDERS)).as("one replacement, however often the report repeats or the loop steps").containsExactly(CORE_1, CORE_2);
            assertThat(h.effects(ClusterEffect.Schedule.class).stream().filter(schedule -> schedule.timer().kind() == TimerId.Kind.DRAIN_STEP).count())
                    .as("one loop: the start, plus the one step that fired").isEqualTo(2);
        }
    }

    @Nested
    class Vacancy {
        /// NT-104 (cluster arm). Red when: a vacant pair (a current UNLOADED tombstone, no desired key) is not eligible, or its goal is
        /// not written at a generation above the tombstone's, or placement waits for the tombstone's Remove.
        @Test
        void tombstone_doesNotBlockPlacement() {
            var h = threeCores();

            seedRow(h, CORE_1, ORDERS, SliceObservedState.UNLOADED, new org.pragmatica.aether.slice.kvstore.AetherValue.Generation(1L, 5L), 0L);
            seedTarget(h, ORDERS, 1, 1);
            h.activate();

            assertThat(hosts(h, ORDERS)).as("core-1 has the smallest id and is vacant").containsExactly(CORE_1);
            assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS)).map(SliceDesiredValue::generation).or(new org.pragmatica.aether.slice.kvstore.AetherValue.Generation(0L, 0L)))
                    .isGreaterThan(new org.pragmatica.aether.slice.kvstore.AetherValue.Generation(1L, 5L));
            assertThat(h.store.get(new SliceObservedKey(CORE_1, ORDERS)).isPresent()).as("the tombstone was not removed by the leader").isTrue();
        }
    }
}
