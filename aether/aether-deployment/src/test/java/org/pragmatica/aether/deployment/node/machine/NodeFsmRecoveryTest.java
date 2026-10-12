// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT_B;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G3;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.PEER;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.PEER_TOKEN;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.SELF;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.SELF_TOKEN;

/// The rows of the node table that start from a committed row the node did not write in this run (N5, N9, N11, N12), the
/// forced cleanup of a falsely departed node (N17; NT-112, node half), and the input accounting (NT-78, FSM half).
class NodeFsmRecoveryTest {
    private static SliceObservedValue rowOf(NodeHarness h, Artifact artifact) {
        return h.observed(artifact).or((SliceObservedValue) null);
    }

    @Nested
    class CommittedRows {
        /// Red when: a goal of ACTIVE over a LOADED row whose slice is not in the store writes anything but a retryable FAILED
        /// typed as a store absence (N5), or starts an activation.
        @Test
        @DisplayName("N5 B-124: ACTIVE over a LOADED row with the slice gone is a retryable, typed FAILED")
        void n5_sliceNotInStore_isARetryableFailed() {
            var h = new NodeHarness();

            h.seedRow(ARTIFACT, SliceObservedState.LOADED, SELF_TOKEN, 2L, G1);
            h.permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G2).establish();

            var row = rowOf(h, ARTIFACT);

            assertThat(row.state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row.fatal()).as("an unload/activate crossing must not roll the blueprint back").isFalse();
            assertThat(row.failureReason().or("")).contains("not present in SliceStore");
            assertThat(h.localSteps()).doesNotContain(LocalStep.ACTIVATE);
        }

        /// Red when: an ACTIVE row whose slice is not in the store (a wiped node) is accepted as it is (no reload), or the reload
        /// does not continue the version chain.
        @Test
        @DisplayName("N11 B-133: an ACTIVE row with no slice in the store reloads and activates again")
        void n11_activeRowWithoutSlice_reloads() {
            var h = new NodeHarness();

            h.seedRow(ARTIFACT, SliceObservedState.ACTIVE, SELF_TOKEN, 4L, G1);
            h.permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1).establish();

            assertThat(h.writtenStates()).containsExactly(SliceObservedState.LOADING,
                                                          SliceObservedState.LOADED,
                                                          SliceObservedState.ACTIVATING,
                                                          SliceObservedState.ACTIVE);
            assertThat(h.observedPuts().getFirst().version()).isEqualTo(5L);
            assertThat(h.slicesInStore).contains(ARTIFACT);
        }

        /// Red when: a goal of LOADED over an ACTIVE row with the slice absent runs a teardown chain or writes anything but
        /// LOADED (N12).
        @Test
        @DisplayName("N12 B-143: LOADED over an ACTIVE row with no slice writes just LOADED")
        void n12_loadedGoalOverActiveWithoutSlice_writesLoaded() {
            var h = new NodeHarness();

            h.seedRow(ARTIFACT, SliceObservedState.ACTIVE, SELF_TOKEN, 4L, G1);
            h.goal(ARTIFACT, SliceGoal.LOADED, G2).establish();

            assertThat(h.writtenStates()).containsExactly(SliceObservedState.LOADED);
            assertThat(h.localOps()).isEmpty();
            assertThat(rowOf(h, ARTIFACT).forGeneration()).isEqualTo(G2);
        }

        /// Red when: the node re-runs a chain for a FAILED row of the generation already executed (N15 adopts and drives nothing).
        @Test
        @DisplayName("N15 R-3.4.2: a FAILED row of the executed generation starts no new chain")
        void n15_failedRowOfTheSameGeneration_startsNothing() {
            var h = new NodeHarness();

            h.script(LocalStep.LOAD, LocalOutcome.failed(org.pragmatica.lang.utils.Causes.cause("corrupt jar")))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.clearEffects().goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
            assertThat(h.localOps()).isEmpty();

            h.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(h.submitsOf(SliceObservedState.LOADING)).as("a new generation is a new attempt").hasSize(1);
        }
    }

    @Nested
    class Stuck {
        private NodeHarness stuckActivating(boolean serving) {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            var activating = h.observedPuts().stream().filter(put -> put.state() == SliceObservedState.ACTIVATING).findFirst().orElseThrow();
            var rec = h.model.instance(ARTIFACT)
                             .withObserved(Option.some(activating))
                             .withLastVersion(activating.version())
                             .withServing(serving);

            h.model = h.model.withInstance(ARTIFACT, rec);
            h.writeMode = NodeHarness.WriteMode.LOST;
            h.clearEffects();

            return h.fireCheck(activating.version());
        }

        /// Red when: the 90 s check on a committed ACTIVATING row that is serving does not rebuild the lost ACTIVE write
        /// (R-6.0): no DESCRIBE_ACTIVE step, or no ACTIVE Put at the next version.
        @Test
        @DisplayName("N9 B-138: a serving instance stuck at ACTIVATING has its ACTIVE write rebuilt")
        void n9_servingInstance_rebuildsTheActiveWrite() {
            var h = stuckActivating(true);

            assertThat(h.localSteps()).containsExactly(LocalStep.DESCRIBE_ACTIVE);
            assertThat(h.submitsOf(SliceObservedState.ACTIVE)).hasSize(1);
            assertThat(h.observedPuts().getFirst().version()).isEqualTo(h.record(ARTIFACT).observed().map(SliceObservedValue::version).or(0L) + 1L);
        }

        /// Red when: a check on an instance that is not serving writes (B-136 never forces ACTIVE on a loaded but unregistered
        /// slice) or warns without naming the node, the artifact, the goal and the committed state (6.3 step 6).
        @Test
        @DisplayName("N9 B-136: an ACTIVATING instance that is not serving only warns")
        void n9_notServing_warnsAndWritesNothing() {
            var h = stuckActivating(false);

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
            assertThat(h.localOps()).isEmpty();
            assertThat(h.effectsOf(NodeFsmEffect.Log.class)).anyMatch(log -> log.level() == NodeFsmEffect.Level.WARN
                                                                           && log.message().contains(SELF.id())
                                                                           && log.message().contains(ARTIFACT.asString())
                                                                           && log.message().contains("ACTIVE")
                                                                           && log.message().contains("ACTIVATING"));
        }
    }

    @Nested
    class Rule60 {
        private NodeFsmModel owing(NodeHarness h, SliceObservedState committed, long committedVersion, long owedVersion, long rowToken) {
            var row = new SliceObservedValue(committed,
                                             committedVersion,
                                             G1,
                                             new org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation(NodeHarness.BOOT_INCARNATION, rowToken),
                                             Option.none(),
                                             false,
                                             0,
                                             List.of(),
                                             h.wall);
            var note = FailureNote.failureNote("corrupt jar", false, G1);
            var rec = InstanceRecord.instanceRecord()
                                    .withDesired(Option.some(new SliceDesiredValue(SliceGoal.LOADED, G1, h.wall)))
                                    .withObserved(Option.some(row))
                                    .withLastVersion(committedVersion)
                                    .withChainGeneration(Option.some(G1))
                                    .withUnconfirmed(Option.some(Unconfirmed.unconfirmed(SliceObservedState.FAILED, owedVersion, Option.some(note))));

            return NodeFsmModel.nodeFsmModel().withActive(true, 1L).withInstance(ARTIFACT, rec);
        }

        private Transition redrive(NodeHarness h, NodeFsmModel model) {
            return Redrive.redrive(h.fsm, Transition.transition(model), ARTIFACT, 0);
        }

        /// Red when: R-6.0 step 3 or 4 is not applied: a FAILED write the committed row already shows (or has passed), or a
        /// teardown or terminal row, is overwritten instead of adopted.
        @Test
        @DisplayName("R-6.0 steps 3 and 4: a committed row at or past the write, or a teardown row, is adopted with no write")
        void redrive_adoptsCommittedRows() {
            var h = new NodeHarness();

            for (var committed : List.of(SliceObservedState.FAILED, SliceObservedState.UNLOADING, SliceObservedState.DEACTIVATING, SliceObservedState.UNLOADED)) {
                var step = redrive(h, owing(h, committed, 2L, 3L, NodeHarness.SELF_TOKEN));

                assertThat(step.effects().stream().filter(effect -> effect instanceof NodeFsmEffect.Submit)).as(committed.name()).isEmpty();
                assertThat(step.instance(ARTIFACT).unconfirmed().isEmpty()).as(committed.name()).isTrue();
            }

            var atTarget = redrive(h, owing(h, SliceObservedState.LOADING, 3L, 3L, NodeHarness.SELF_TOKEN));

            assertThat(atTarget.effects().stream().filter(effect -> effect instanceof NodeFsmEffect.Submit)).as("a row at the write's version").isEmpty();
        }

        /// Red when: R-6.0 step 5 is not applied: a row earlier in the chain does not get the FAILED write rebuilt at the next
        /// version, or a stale row (step 2) is adopted instead of treated as absent.
        @Test
        @DisplayName("R-6.0 steps 2 and 5: a row earlier in the chain gets the write rebuilt; a stale row is never adopted")
        void redrive_rebuildsForEarlierRows_andNeverAdoptsAStaleRow() {
            var h = new NodeHarness();

            for (var committed : List.of(SliceObservedState.LOADING, SliceObservedState.LOADED, SliceObservedState.ACTIVATING, SliceObservedState.ACTIVE)) {
                var step = redrive(h, owing(h, committed, 2L, 3L, NodeHarness.SELF_TOKEN));
                var submits = step.effects().stream().filter(NodeFsmEffect.Submit.class::isInstance).map(NodeFsmEffect.Submit.class::cast).toList();

                assertThat(submits).as(committed.name()).hasSize(1);
                assertThat(NodeHarness.putOf(submits.getFirst()).map(SliceObservedValue::version).or(0L)).as(committed.name()).isEqualTo(3L);
                assertThat(NodeHarness.putOf(submits.getFirst()).map(SliceObservedValue::state).or(SliceObservedState.UNKNOWN)).as(committed.name()).isEqualTo(SliceObservedState.FAILED);
            }

            var stale = redrive(h, owing(h, SliceObservedState.UNLOADING, 2L, 3L, NodeHarness.OTHER_PROCESS_TOKEN));

            assertThat(stale.effects().stream().filter(effect -> effect instanceof NodeFsmEffect.Submit)).as("a stale teardown row reads as absent").hasSize(1);
        }

        /// Red when: the remediation line does not name the node, the goal, the local write and the committed state (step 6).
        @Test
        @DisplayName("R-6.0 step 6: the remediation line names the node, goal, local and observed state")
        void redrive_namesNodeGoalLocalAndObserved() {
            var h = new NodeHarness();
            var step = redrive(h, owing(h, SliceObservedState.LOADING, 2L, 3L, NodeHarness.SELF_TOKEN));
            var line = step.effects().stream().filter(NodeFsmEffect.Log.class::isInstance).map(NodeFsmEffect.Log.class::cast).findFirst().orElseThrow().message();

            assertThat(line).contains(SELF.id(), "LOADED", "FAILED", "LOADING", ARTIFACT.asString());
        }
    }

    @Nested
    class Departed {
        /// Red when: the desired keys of a falsely departed node going away does not run the forced cleanup (N17), the node
        /// writes anything while it is cleaned up, or the leader's witnessed removal of its rows (C17b) leaves a record behind.
        @Test
        @DisplayName("NT-112 R-15 (node half): an idle node whose goals and rows are removed cleans up and writes nothing")
        void nt112_falselyDepartedNode_cleansUpLocally() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.clearEffects().removeGoal(ARTIFACT);

            assertThat(h.localSteps()).containsExactly(LocalStep.FORCED_CLEANUP);
            assertThat(h.slicesInStore).doesNotContain(ARTIFACT);
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();

            var committed = rowOf(h, ARTIFACT);

            h.applyBatch(List.of(new KVCommand.Remove<AetherKey>(SliceObservedKey.sliceObservedKey(SELF, ARTIFACT), Option.<Object>some(committed))));
            assertThat(h.observed(ARTIFACT).isEmpty()).as("the leader's witnessed Remove of the departed node's row, before the notification").isTrue();

            h.deliverCommitted(ARTIFACT);

            assertThat(h.observed(ARTIFACT).isEmpty()).as("the leader's witnessed Remove of the departed node's row " + h.effects).isTrue();
            assertThat(h.model.instances()).doesNotContainKey(ARTIFACT);
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
        }
    }

    @Nested
    class Accounting {
        /// Red when: an input does not increment inputs_total, carries the wrong labels, or a dropped input is counted as applied.
        @Test
        @DisplayName("NT-78 B-180 (FSM half): every input counts once in inputs_total, dropped or applied")
        void nt78_everyInput_countsOnce() {
            var h = new NodeHarness();
            var desired = new SliceDesiredValue(SliceGoal.LOADED, G1, 1L);
            var stale = TimerId.timerId(TimerId.TimerKind.CHAIN_BOUND, ARTIFACT, 99L, 1L);

            for (var input : List.<NodeFsmInput>of(new NodeFsmInput.DesiredPut(ARTIFACT, desired),
                                                   new NodeFsmInput.ConfigChanged("a.b"),
                                                   new NodeFsmInput.TimerFired(stale),
                                                   new NodeFsmInput.QuorumDisappeared())) {
                assertThat(counted(h.fsm.step(h.model, input), input)).as("Dormant " + input.getClass().getSimpleName()).isEqualTo("dropped");
            }

            h.establish().permit(ARTIFACT);

            var applied = List.<NodeFsmInput>of(new NodeFsmInput.DesiredPut(ARTIFACT, desired),
                                                new NodeFsmInput.DesiredRemove(ARTIFACT_B),
                                                new NodeFsmInput.TargetPut(ARTIFACT_B.base(), ARTIFACT_B.version()),
                                                new NodeFsmInput.TargetRemove(ARTIFACT_B.base()),
                                                new NodeFsmInput.RoutingPut(ARTIFACT_B.base(), ARTIFACT_B.version(), ARTIFACT_B.version()),
                                                new NodeFsmInput.RoutingRemove(ARTIFACT_B.base()),
                                                new NodeFsmInput.ConfigChanged("a.b"),
                                                new NodeFsmInput.ObservedRemove(PEER, ARTIFACT_B));

            for (var input : applied) {
                assertThat(counted(h.fsm.step(h.model, input), input)).as("Active " + input.getClass().getSimpleName()).isEqualTo("applied");
            }

            var dropped = List.<NodeFsmInput>of(new NodeFsmInput.TimerFired(stale),
                                                new NodeFsmInput.WriteSettled(1L, ARTIFACT, 424242L, NodeFsmInput.WriteOutcome.accepted()),
                                                new NodeFsmInput.LocalOpSettled(1L, ARTIFACT, 424242L, LocalOutcome.done()),
                                                new NodeFsmInput.QuorumEstablished(h.snapshot()),
                                                new NodeFsmInput.ReconcileActivation(h.snapshot()));

            for (var input : dropped) {
                assertThat(counted(h.fsm.step(h.model, input), input)).as("Active " + input.getClass().getSimpleName()).isEqualTo("dropped");
            }
        }

        private String counted(Transition transition, NodeFsmInput input) {
            var metrics = transition.effects()
                                    .stream()
                                    .filter(NodeFsmEffect.Metric.class::isInstance)
                                    .map(NodeFsmEffect.Metric.class::cast)
                                    .filter(metric -> metric.name().equals("aether_deployment_fsm_inputs_total"))
                                    .toList();

            assertThat(metrics).hasSize(1);
            assertThat(metrics.getFirst().labels()).containsEntry("fsm", "node").containsEntry("input", input.getClass().getSimpleName());
            assertThat(metrics.getFirst().delta()).isEqualTo(1L);

            return metrics.getFirst().labels().get("result");
        }
    }
}
