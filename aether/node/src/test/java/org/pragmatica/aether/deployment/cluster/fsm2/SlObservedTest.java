// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;

/// Observed removes (C11), stray rows (R-3.3.11), and the leader's removal discipline. NT-30, 93, 105, 120.
class SlObservedTest {
    private static final Generation G7 = new Generation(1L, 7L);

    @Nested
    class Removal {
        /// NT-30. Red when: an observed remove leaves the index entry, or with goal UNLOADED the desired key is not GC'd.
        @Test
        void observedRemove_withGoalUnloaded_garbageCollectsTheDesiredKey() {
            var h = threeCores();

            seedGoal(h, CORE_1, ORDERS, SliceGoal.UNLOADED, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.UNLOADED, G1, 0L);
            h.activate();
            assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS)).isPresent()).as("kept while the tombstone stands").isTrue();

            h.seedRemove(new SliceObservedKey(CORE_1, ORDERS));
            h.drain();

            assertThat(h.model().observed(SlicePair.slicePair(CORE_1, ORDERS)).isPresent()).as("the index entry is gone").isFalse();
            assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS)).isPresent()).as("the desired key is garbage collected").isFalse();
        }

        /// NT-30. Red when: with a live goal the 1 s reconcile arm is missing (the pair is not re-derived).
        @Test
        void observedRemove_withALiveGoal_reconcilesInOneSecond() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G1, 0L);
            h.activate();
            h.clearLog();

            h.seedRemove(new SliceObservedKey(CORE_1, ORDERS));
            h.drain();

            assertThat(h.effects(ClusterEffect.Schedule.class)).anyMatch(schedule -> schedule.timer().kind() == TimerId.Kind.RECONCILE && schedule.delayMs() == 1_000L);
            assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS)).isPresent()).as("a live goal keeps its desired key").isTrue();
        }

        /// NT-30. Red when: a permanently failed artifact schedules the 1 s reconcile.
        @Test
        void observedRemove_ofAPermanentlyFailedArtifact_schedulesNothing() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedJudgment(h, ORDERS, new ArtifactJudgmentValue(true, Map.of(), Map.of()));
            seedGoal(h, CORE_1, ORDERS, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G1, 0L);
            h.activate();
            h.clearLog();

            h.seedRemove(new SliceObservedKey(CORE_1, ORDERS));
            h.drain();

            assertThat(h.effects(ClusterEffect.Schedule.class)).noneMatch(schedule -> schedule.delayMs() == 1_000L);
        }
    }

    @Nested
    class Strays {
        /// NT-120. Red when: an adopted current ACTIVE row is adopted at another generation, re-issued, retracted, marked
        /// unresponsive, or makes the node start a chain.
        @Test
        void adoptedActiveRow_isSettled_andNeverTouchedAgain() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G7, 0L);
            h.activate();

            assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS))).isEqualTo(Option.some(new SliceDesiredValue(SliceGoal.ACTIVE, G7, h.wallMs)));
            var submits = h.submits().size();

            h.advanceBySeconds(660);

            assertThat(h.submits()).as("no re-issue, retraction or judgment in eleven minutes").hasSize(submits);
            assertThat(h.model().judgment(ORDERS).isPresent()).isFalse();
            assertThat(h.model().desired(SlicePair.slicePair(CORE_1, ORDERS)).map(SliceDesiredValue::generation)).isEqualTo(Option.some(G7));
        }

        /// NT-105 (b). Red when: a current stray live row on a counting node is not adopted.
        @Test
        void liveStray_isAdopted() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedRow(h, CORE_2, ORDERS, SliceObservedState.LOADED, G7, 0L);
            h.activate();

            assertThat(goalOf(h, CORE_2, ORDERS)).isEqualTo(Option.some(SliceGoal.LOADED));
            assertThat(h.model().desired(SlicePair.slicePair(CORE_2, ORDERS)).map(SliceDesiredValue::generation)).isEqualTo(Option.some(G7));
            assertThat(countGoals(h, ORDERS)).as("the adoption is the placement: nothing is placed beside it").isEqualTo(1);
        }

        /// NT-105 (c). Red when: a surplus stray is not condemned with `stray-instance`.
        @Test
        void surplusStray_isCondemned() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G7, 0L);
            seedRow(h, CORE_2, ORDERS, SliceObservedState.ACTIVE, G7, 0L);
            h.activate();

            assertThat(goalOf(h, CORE_1, ORDERS)).as("the first is adopted").isEqualTo(Option.some(SliceGoal.ACTIVE));
            assertThat(goalOf(h, CORE_2, ORDERS)).as("the surplus is condemned").isEqualTo(Option.some(SliceGoal.UNLOADED));
            assertThat(h.warns("stray-instance")).hasSize(1);
        }

        /// NT-105 (c). Red when: a stray of a version no committed target permits is adopted instead of condemned.
        @Test
        void unpermittedStray_isCondemned() {
            var h = threeCores();

            seedTarget(h, ORDERS_V2, 1, 1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, G7, 0L);
            h.activate();

            assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
            assertThat(h.warns("stray-instance")).hasSize(1);
        }

        /// NT-105 (g). Red when: an UNLOADED row with no desired key is condemned or raises `stray-instance`.
        @Test
        void tombstoneWithoutADesiredKey_isNotAStray() {
            var h = threeCores();

            seedTarget(h, ORDERS_V2, 1, 1);
            seedRow(h, CORE_1, ORDERS, SliceObservedState.UNLOADED, G7, 0L);
            h.activate();

            assertThat(h.model().hasStray(SlicePair.slicePair(CORE_1, ORDERS))).isFalse();
            assertThat(h.warns("stray-instance")).isEmpty();
            assertThat(h.submits().stream().flatMap(s -> s.transaction().mutations().stream()).filter(m -> m.key() instanceof SliceDesiredKey && m.key().asString().contains("orders:1.0.0")))
                    .isEmpty();
        }

        /// NT-105 (d). Red when: the stray rows of a worker that is not counted are adopted or condemned, or when it rejoins
        /// (appears in its community's roster) its current rows are not adopted, with no write other than the adoption.
        @Test
        void rejoin_ofTheSameProcess_adoptsItsRows_withNoOtherWrite() {
            var h = harness(CORE_1);
            var w1 = node("worker-1");
            var w2 = node("worker-2");

            h.seams = h.seams.withLocalAlive(Option.some(Set.of(w2)));
            h.seedQuietly(new ActivationDirectiveKey(w1), worker("c"));
            h.seedQuietly(new ActivationDirectiveKey(w2), worker("c"));
            h.seedQuietly(new GovernorAnnouncementKey("c"), roster(w2, false, w2));
            seedTarget(h, ORDERS, 2, 1);
            seedRow(h, w1, ORDERS, SliceObservedState.ACTIVE, G7, 5L);
            h.activate();
            var before = h.submits(ClusterModel.SubmitKind.GOALS).stream().filter(s -> s.transaction().mutations().stream().anyMatch(m -> m.key().equals(new SliceDesiredKey(w1, ORDERS)))).count();

            assertThat(goalOf(h, w1, ORDERS)).as("not counted: neither adopted nor condemned").isEqualTo(Option.empty());
            assertThat(before).isZero();

            h.seed(new GovernorAnnouncementKey("c"), roster(w2, false, w1, w2));
            h.drain();

            assertThat(goalOf(h, w1, ORDERS)).isEqualTo(Option.some(SliceGoal.ACTIVE));
            assertThat(h.model().desired(SlicePair.slicePair(w1, ORDERS)).map(SliceDesiredValue::generation)).isEqualTo(Option.some(G7));
            assertThat(h.warns("stray-instance")).isEmpty();
        }

        /// NT-105 (e). Red when: a stray present at the rebuild of a new leader is not adopted by its first reconcile.
        @Test
        void leaderChange_adoptsAStrayFoundAtRebuild() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            h.activate();
            h.deliver(new ClusterInput.Deactivate());
            seedRow(h, CORE_3, ORDERS, SliceObservedState.ACTIVE, G7, 0L);
            h.activate();

            assertThat(h.model().desired(SlicePair.slicePair(CORE_3, ORDERS)).map(SliceDesiredValue::generation)).as("adopted, or the instance already placed")
                    .isNotEqualTo(Option.empty());
        }
    }

    @Nested
    class InFlight {
        /// NT-105 (a). Red when: back-to-back reconciles over-place (the pending goals are not counted as placed).
        @Test
        void backToBackReconciles_doNotOverPlace() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            h.policy = FsmHarness.SettlePolicy.HOLD;
            h.activate();
            var submits = h.submits().size();

            h.deliver(new ClusterInput.Reconcile());
            h.deliver(new ClusterInput.Reconcile());
            h.deliver(new ClusterInput.Reconcile());

            assertThat(submits).isEqualTo(2);
            assertThat(h.submits()).as("the goals in flight count as placed").hasSize(2);
            assertThat(h.model().pendingGoals.size()).isEqualTo(2);
        }

        /// NT-105 (f). Red when: a goal transaction that failed leaves its `pendingGoals` entry, so `placed(a)` over-counts and
        /// the next reconcile does not place again.
        @Test
        void failedGoalTransaction_clearsItsPendingEntry_andIsRederived() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            h.policy = FsmHarness.SettlePolicy.HOLD;
            h.activate();
            var first = List.copyOf(h.held.values());

            h.settleHeld(first.get(0), new ClusterInput.Settlement.Failed("lost"));
            h.settleHeld(first.get(1), new ClusterInput.Settlement.Failed("lost"));

            assertThat(h.submits()).as("a failed write is not retried in a hot loop: the next reconcile waits one retry base")
                                   .hasSize(2);
            h.advance(Timing.DEFAULT.retryBase());

            assertThat(h.submits()).as("both are submitted again").hasSize(4);
            assertThat(h.model().pendingGoals.size()).as("and counted once").isEqualTo(2);
        }

        /// NT-105 (f). Red when: after the 5-resubmit bound the pair is resubmitted at once (it waits for the tick), or its pending
        /// entry survives.
        @Test
        void refusedBeyondTheBound_waitsForTheTick() {
            var h = threeCores();

            seedTarget(h, ORDERS, 1, 1);
            h.policy = FsmHarness.SettlePolicy.HOLD;
            h.activate();
            var leader = h.committedLeader();

            for (var round = 0; round < 6; round++) {
                var open = List.copyOf(h.held.values());

                h.held.clear();
                open.forEach(submit -> h.settleHeld(submit, new ClusterInput.Settlement.Refused(leader, Map.of())));
            }

            assertThat(h.submits()).as("one original and five resubmissions").hasSize(6);
            assertThat(h.model().pendingGoals.isEmpty()).as("beyond the bound the entry is dropped").isTrue();

            h.advanceBySeconds(31);

            assertThat(h.submits()).as("the tick re-derives").hasSize(7);
        }
    }

    @Nested
    class Discipline {
        /// NT-93 (cluster arm). Red when: the leader removes an observed key of a node that is not recorded departed, or at
        /// other than the exact committed value.
        @Test
        void leader_removesObservedKeys_onlyOfDepartedNodes() {
            var h = threeCores();

            seedTarget(h, ORDERS, 2, 1);
            seedGoal(h, CORE_2, ORDERS, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_2, ORDERS, SliceObservedState.ACTIVE, G1, 0L);
            seedRow(h, CORE_1, BILLING, SliceObservedState.UNLOADED, G1, 0L);
            h.seedQuietly(new NodeDepartureKey(CORE_3), new NodeDepartureValue(DepartureState.ADMITTED, 1L, process(0L), Option.none(), FsmHarness.START_WALL));
            seedGoal(h, CORE_3, BILLING, SliceGoal.ACTIVE, G1);
            seedRow(h, CORE_3, BILLING, SliceObservedState.ACTIVE, G1, 0L);
            h.activate();
            h.advanceBySeconds(300);
            assertThat(removedObserved(h)).as("nothing is removed while nobody departed").isEmpty();

            h.deliver(new ClusterInput.NodeRemoved(CORE_2));

            var exact = h.store.get(new SliceObservedKey(CORE_2, ORDERS));
            assertThat(removedObserved(h)).containsExactly(new SliceObservedKey(CORE_2, ORDERS));
            assertThat(exact.isPresent()).as("removed at the exact value").isFalse();
            assertThat(h.store.get(new SliceObservedKey(CORE_1, BILLING)).isPresent()).as("the live node's tombstone is the node's to remove").isTrue();
            assertThat(h.store.get(new SliceObservedKey(CORE_3, BILLING)).isPresent()).as("a re-admitted node (ADMITTED record) keeps its rows").isTrue();
        }

        private List<Object> removedObserved(FsmHarness h) {
            return h.submits().stream()
                    .flatMap(submit -> submit.transaction().mutations().stream())
                    .filter(mutation -> mutation.key() instanceof SliceObservedKey && mutation.replacement().isEmpty())
                    .map(KVCommand.Mutation::key)
                    .map(Object.class::cast)
                    .toList();
        }
    }
}
