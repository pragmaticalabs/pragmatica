// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;

/// v-2172 adversarial sequences. Each `defect_*` test asserts the behaviour the spec asks for and is RED at e9371909f; each
/// `control_*` test is the same sequence without the triggering step and is GREEN, so the harness can see the outcome.
class V2172AdversarialTest {
    private static NodeFsmTimings withChainBound(long chainBoundMs) {
        var d = NodeFsmTimings.nodeFsmTimings();

        return new NodeFsmTimings(d.loadMs(), d.activateMs(), d.deactivateMs(), d.unloadMs(), chainBoundMs, d.activatingCheckMs(),
                                  d.tombstoneGraceMs(), d.parkedBoundMs(), d.writeAttemptMs(), d.writeAttempts(), d.failedRetryMs(),
                                  d.failedCycles());
    }

    /// Goal removed while the node is Dormant (the DesiredRemove is dropped, B-101): the Rebuild must still apply N17 to a
    /// slice the store holds (R-3.4.1 step 4 reconciles every own pair; N17 = own desired gone + loaded slice -> forced cleanup).
    @Nested
    class GoalRemovedWhileDormant {
        @Test
        @DisplayName("DEFECT A: a suspended ACTIVE slice whose goal went away during the outage is never cleaned up")
        void defect_suspendedSlice_goalRemovedWhileDormant_isCleanedUpAtEntry() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            assertThat(h.state(ARTIFACT).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.ACTIVE);

            h.feed(new NodeFsmInput.QuorumDisappeared());
            assertThat(h.record(ARTIFACT).suspended()).as("precondition: suspended on the quorum loss").isTrue();

            h.removeGoal(ARTIFACT);
            h.clearEffects().establish();

            assertThat(h.localSteps()).as("N17 at entry: the loaded slice with no goal is force-cleaned")
                                      .contains(LocalStep.FORCED_CLEANUP);
            assertThat(h.model.isLoaded(ARTIFACT)).isFalse();
        }

        @Test
        @DisplayName("DEFECT A': a LOADED slice whose goal went away during the outage stays loaded")
        void defect_loadedSlice_goalRemovedWhileDormant_isCleanedUpAtEntry() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.feed(new NodeFsmInput.QuorumDisappeared());
            h.removeGoal(ARTIFACT);
            h.clearEffects().establish();

            assertThat(h.localSteps()).contains(LocalStep.FORCED_CLEANUP);
        }

        @Test
        @DisplayName("control A: the same removal while Active runs N17")
        void control_goalRemovedWhileActive_isCleanedUp() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.clearEffects().removeGoal(ARTIFACT);

            assertThat(h.localSteps()).contains(LocalStep.FORCED_CLEANUP);
        }
    }

    /// R-1.6.3: with no new goal the node Removes its own tombstone once the grace has passed. The grace timer belongs to the
    /// old incarnation and is dropped on a quorum cycle (R-3.1.3), so the Rebuild must re-arm it.
    @Nested
    class TombstoneAcrossQuorumCycle {
        private NodeHarness unloadedThenGoalGone() {
            var h = new NodeHarness();

            h.rowArtifacts.add(ARTIFACT);
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1).goal(ARTIFACT, SliceGoal.UNLOADED, G2);
            assertThat(h.state(ARTIFACT).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.UNLOADED);
            h.removeGoal(ARTIFACT);

            return h;
        }

        @Test
        @DisplayName("DEFECT B: a tombstone with no goal is never removed after a quorum cycle inside its grace")
        void defect_tombstone_quorumCycleInsideGrace_isStillRemoved() {
            var h = unloadedThenGoalGone();

            h.advance(10_000L).feed(new NodeFsmInput.QuorumDisappeared());
            h.establish();
            h.advance(h.timings.tombstoneGraceMs() + 10_000L);

            assertThat(h.observed(ARTIFACT).isEmpty()).as("the tombstone is GC'd after the grace").isTrue();
        }

        @Test
        @DisplayName("control B: without the quorum cycle the tombstone is removed")
        void control_tombstone_noCycle_isRemoved() {
            var h = unloadedThenGoalGone();

            h.advance(h.timings.tombstoneGraceMs() + 10_000L);

            assertThat(h.observed(ARTIFACT).isEmpty()).isTrue();
        }
    }

    /// B-157/B-158: a park survives a quorum cycle; its escalation timer (old incarnation) does not, and nothing re-arms it.
    @Nested
    class ParkEscalationAcrossQuorumCycle {
        private static boolean escalated(NodeHarness h) {
            return h.effectsOf(NodeFsmEffect.Warn.class)
                    .stream()
                    .anyMatch(warn -> warn.code().equals(OperatorWarningCode.SLICE_START_AWAITING_TARGET.code()));
        }

        @Test
        @DisplayName("DEFECT C: a park carried across a quorum cycle never raises slice-start-awaiting-target")
        void defect_parkCarriedAcrossCycle_stillEscalates() {
            var h = new NodeHarness();

            h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(5_000L).feed(new NodeFsmInput.QuorumDisappeared());
            h.establish();
            h.advance(h.timings.parkedBoundMs() + 10_000L);

            assertThat(h.record(ARTIFACT).parked().isPresent()).as("precondition: still parked").isTrue();
            assertThat(escalated(h)).as("the escalation is raised once the park is older than the bound").isTrue();
        }

        @Test
        @DisplayName("control C: without the cycle the park escalates")
        void control_park_noCycle_escalates() {
            var h = new NodeHarness();

            h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(h.timings.parkedBoundMs() + 10_000L);

            assertThat(escalated(h)).isTrue();
        }
    }

    /// R-3.4.2: a goal that arrives while a write is in flight is recorded and re-evaluated when the work ends. When the
    /// in-flight write is the tombstone Remove, its confirmation by the ObservedRemove notification (or its FER exhaustion)
    /// never re-evaluates the recorded goal.
    @Nested
    class GoalPendingBehindTombstoneRemove {
        private NodeHarness tombstoneRemoveInFlight(int lostAttempts) {
            var h = new NodeHarness();
            var removes = new int[] {0};

            h.rowArtifacts.add(ARTIFACT);
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1).goal(ARTIFACT, SliceGoal.UNLOADED, G2);
            h.writeScript = submitted -> submitted.submit().commands().stream().anyMatch(org.pragmatica.cluster.state.kvstore.KVCommand.Remove.class::isInstance)
                                         && removes[0]++ < lostAttempts
                                         ? NodeHarness.WriteMode.LOST
                                         : null;
            h.advance(h.timings.tombstoneGraceMs() + 1_000L);
            assertThat(h.record(ARTIFACT).write().map(InFlight::purpose).or(InFlight.Purpose.CHAIN))
                .as("precondition: the Remove is in flight")
                .isEqualTo(InFlight.Purpose.TOMBSTONE_REMOVE);
            h.goal(ARTIFACT, SliceGoal.LOADED, NodeHarness.G3);
            assertThat(h.record(ARTIFACT).desiredPending()).as("precondition: the new goal waits for the Remove").isTrue();

            return h;
        }

        @Test
        @DisplayName("DEFECT E: a goal recorded behind a tombstone Remove is never started once the Remove commits")
        void defect_goalBehindCommittedRemove_isStarted() {
            var h = tombstoneRemoveInFlight(1);

            h.clearEffects().advance(h.timings.writeAttemptMs() + 1_000L);

            assertThat(h.observed(ARTIFACT).isEmpty() || h.submitsOf(SliceObservedState.LOADING).size() == 1)
                .as("precondition: the retried Remove committed").isTrue();
            assertThat(h.submitsOf(SliceObservedState.LOADING)).as("the recorded goal starts its chain").hasSize(1);
        }

        @Test
        @DisplayName("DEFECT E': a goal recorded behind a tombstone Remove that exhausts (FER) is never started")
        void defect_goalBehindExhaustedRemove_isStarted() {
            var h = tombstoneRemoveInFlight(3);

            h.clearEffects().advance(h.timings.writeAttemptMs() * h.timings.writeAttempts() + 1_000L);

            assertThat(h.submitsOf(SliceObservedState.LOADING)).as("the recorded goal starts its chain").hasSize(1);
        }

        @Test
        @DisplayName("control E: a goal that arrives after the Remove committed starts at once")
        void control_goalAfterRemove_isStarted() {
            var h = new NodeHarness();

            h.rowArtifacts.add(ARTIFACT);
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1).goal(ARTIFACT, SliceGoal.UNLOADED, G2);
            h.advance(h.timings.tombstoneGraceMs() + 1_000L);
            assertThat(h.observed(ARTIFACT).isEmpty()).isTrue();
            h.clearEffects().goal(ARTIFACT, SliceGoal.LOADED, NodeHarness.G3);

            assertThat(h.submitsOf(SliceObservedState.LOADING)).hasSize(1);
        }
    }

    /// N17 "forced cleanup" and the author's own reading (report [guess] 4: "N17 writes nothing"); R-6.0 step 5 rebuilds only
    /// "with the goal still asking for it". A failure owed before the goal went away must not be written after it.
    @Nested
    class FailureOwedAcrossGoalRemoval {
        @Test
        @DisplayName("DEFECT D: a deferred FAILED is written (and DeploymentFailed routed) after the goal was removed")
        void defect_deferredFailure_isNotWrittenAfterGoalRemoval() {
            var h = new NodeHarness(NodeHarness.SELF_TOKEN, withChainBound(10_000L));

            h.establish().permit(ARTIFACT);
            h.loseWritesOf(SliceObservedState.LOADING);
            h.goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.advance(10_000L);
            assertThat(h.record(ARTIFACT).deferredFailure().isPresent()).as("precondition: the bound failed the chain mid-write").isTrue();

            h.removeGoal(ARTIFACT);
            h.writeScript = _ -> null;
            h.clearEffects().advance(30_000L);

            assertThat(h.submitsOf(SliceObservedState.FAILED)).as("no FAILED row after N17").isEmpty();
            assertThat(h.effectsOf(NodeFsmEffect.Route.class))
                .noneMatch(route -> route.event() instanceof NodeFsmEffect.RoutedEvent.DeploymentFailed);
        }

        @Test
        @DisplayName("control D: with the goal still present the deferred FAILED is written")
        void control_deferredFailure_goalPresent_isWritten() {
            var h = new NodeHarness(NodeHarness.SELF_TOKEN, withChainBound(10_000L));

            h.establish().permit(ARTIFACT);
            h.loseWritesOf(SliceObservedState.LOADING);
            h.goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.advance(10_000L);
            h.writeScript = _ -> null;
            h.clearEffects().advance(30_000L);

            assertThat(h.submitsOf(SliceObservedState.FAILED)).hasSize(1);
        }

        @Test
        @DisplayName("DEFECT D': N19 FAILED retries keep writing after the goal was removed")
        void defect_failedRetries_stopWhenTheGoalIsRemoved() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT);
            h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")));
            h.loseWritesOf(SliceObservedState.FAILED);
            h.goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(h.timings.writeAttemptMs() * h.timings.writeAttempts());
            assertThat(h.record(ARTIFACT).unconfirmed().isPresent()).as("precondition: FAILED owed, cycling").isTrue();

            h.removeGoal(ARTIFACT);
            h.clearEffects().advance(h.timings.failedRetryMs() + 1_000L);

            assertThat(h.submitsOf(SliceObservedState.FAILED)).as("R-6.0 step 5: the goal no longer asks").isEmpty();
        }
    }
}
