// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.deployment.cluster.fsm2.RoutedEvent;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// The cluster FSM interpreter against the production `ClusterFsm`, `Submitter`, `KvSnapshotter` and the real `KVStore` applier (phase-3
/// design 5): W-11 (Rebuild first, synchronously), W-07 (one `apply` per step, cluster arm), W-13 (the pre-submit self-check through the
/// interpreter), W-08 (cluster timers carry their incarnation), W-12 (no inline step), and the `Close` of the leader's interpreter.
class ClusterFsmInterpreterTest {
    private static boolean writesGoals(List<org.pragmatica.cluster.state.kvstore.KVCommand<org.pragmatica.aether.slice.kvstore.AetherKey>> batch) {
        return batch.stream()
                    .anyMatch(command -> command instanceof org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction<?, ?> tx
                                         && tx.mutations().stream().anyMatch(mutation -> mutation.key() instanceof org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey));
    }

    @Nested
    class Rebuild {
        /// W-11, MT-11: the snapshot is read and `Rebuild` stepped inside the dispatch of the step that asked for it, ahead of whatever was
        /// queued behind `Activate`.
        @Test
        void rebuild_isSteppedInsideTheActivationDispatch_aheadOfInputsQueuedBehindIt() {
            try (var rig = new ClusterRig()) {
                rig.holdConsumerAt("Flush");
                rig.interpreter.deliver(new ClusterInput.Flush());
                rig.awaitThat(() -> rig.countOf("Flush") == 1);
                rig.interpreter.activate();
                rig.target(ORDERS, 1);
                rig.target(BILLING, 1);
                // Deterministic fixture: one input stamped above any snapshot the Rebuild can take is queued behind Activate.
                var past = rig.applier.slot() + 1_000;

                rig.interpreter.deliverKv(new ClusterInput.KvRemove(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey(AUDIT.base())), past, false);
                rig.release();
                rig.settle();

                var order = List.copyOf(rig.stepped);
                var rebuild = order.indexOf("Rebuild");

                assertThat(rebuild).as("Rebuild was stepped").isGreaterThan(0);
                assertThat(order.get(rebuild + 1)).as("the Activate step that asked for it finishes next").isEqualTo("Activate");
                // The target puts are inside the snapshot and dropped; the goal puts may land after settle()'s barrier. The input stamped above the
                // snapshot is stepped, and it is stepped after the Rebuild: both halves are asserted, so the check cannot pass vacuously.
                assertThat(order).as("the queued input past the snapshot was stepped").contains("KvRemove");
                assertThat(order.indexOf("KvRemove")).as("and only after the Rebuild").isGreaterThan(rebuild);
                assertThat(order.subList(0, rebuild)).as("no input of the new activation precedes the Rebuild").doesNotContain("KvPut", "KvRemove");
                assertThat(rig.snapshots.get()).isEqualTo(1);
            }
        }

        /// W-11: each activation reads its own snapshot; `Activate -> Deactivate -> Activate` is two entries off two snapshots, not one.
        @Test
        void activateDeactivateActivate_readsTwoSnapshots_andEntersActiveTwice() {
            try (var rig = new ClusterRig()) {
                rig.activate();
                assertThat(rig.isActive()).isTrue();
                rig.interpreter.deactivate();
                rig.settle();
                assertThat(rig.interpreter.state()).isInstanceOf(ClusterState.Dormant.class);
                rig.activate();

                assertThat(rig.isActive()).isTrue();
                assertThat(rig.snapshots.get()).isEqualTo(2);
                assertThat(rig.countOf("Rebuild")).isEqualTo(2);
            }
        }

        @Test
        void kvInputsAtOrBelowTheSnapshotRevision_areDropped_andTheNextOneIsStepped() {
            try (var rig = new ClusterRig()) {
                var revision = rig.target(ORDERS, 1).applier.slot();

                rig.activate();

                rig.interpreter.deliverKv(new ClusterInput.KvRemove(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey(ORDERS.base())), revision, false);
                rig.interpreter.deliverKv(new ClusterInput.KvRemove(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey(BILLING.base())), revision + 1, false);
                rig.interpreter.deliverKv(new ClusterInput.KvRemove(new org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey(AUDIT.base())), revision + 9, true);
                rig.settle();

                assertThat(rig.metric("KvRemove", "dropped_revision")).isEqualTo(1);
                assertThat(rig.metric("KvRemove", "dropped_replay")).isEqualTo(1);
                assertThat(rig.countOf("KvRemove")).as("only the one past the snapshot was stepped").isEqualTo(1);
            }
        }
    }

    @Nested
    class Writes {
        /// W-07, MT-07: two goal Submits of one step leave in one `apply`.
        @Test
        void theSubmitsOfOneStep_leaveInOneApply_andEachIsSettled() {
            try (var rig = new ClusterRig()) {
                rig.activate();
                rig.target(ORDERS, 2);

                rig.awaitThat(() -> rig.routed(RoutedEvent.DeploymentStarted.class) == 2);

                var goalBatches = rig.cluster.applied.stream().filter(ClusterFsmInterpreterTest::writesGoals).toList();

                assertThat(goalBatches).as("MT-07: one apply per Submit would make two batches").hasSize(1);
                assertThat(goalBatches.getFirst()).as("both goal transactions in the one batch").hasSize(2);
            }
        }

        /// W-13 through the interpreter: another committed leader means nothing is submitted, and the FSM learns it from the settlements.
        @Test
        void aMismatchedLeader_withholdsTheStep_andFeedsBackRefusalAndWitnessStale() {
            try (var rig = new ClusterRig()) {
                rig.activate();
                var applies = rig.cluster.applied.size();

                rig.muteLeaderNotifications = true;
                rig.applier.commitLeader(new LeaderValue(OTHER, 9L));
                rig.target(ORDERS, 1);

                rig.awaitThat(() -> rig.countOf("WitnessStale") == 1);
                assertThat(rig.cluster.applied).as("MT-12: nothing built on the stale witness reached the engine").hasSize(applies);
                assertThat(rig.countOf("WriteSettled")).as("the withheld Submit was settled Refused").isGreaterThanOrEqualTo(1);
            }
        }

        /// W-12 (MT-01), cluster arm: the apply completes on another thread; the FSM is still stepped only on the consumer thread.
        @Test
        void completions_neverStepTheFsmOnTheCompletingThread() {
            try (var rig = new ClusterRig()) {
                var real = rig.cluster.script;

                rig.cluster.script = commands -> {
                    var promise = Promise.<List<Object>>promise();

                    Thread.ofVirtual().name("apply-completion").start(() -> promise.resolve(real.apply(commands).await()));

                    return promise;
                };
                rig.activate();
                rig.target(ORDERS, 1);

                rig.awaitThat(() -> rig.routed(RoutedEvent.DeploymentStarted.class) == 1);
                rig.settle();

                assertThat(rig.stepThreads).containsExactly("deploy-fsm-cluster");
            }
        }
    }

    @Nested
    class Timers {
        /// W-08: the cluster `Timer` carries the incarnation it was armed in; a timer of a previous incarnation is dropped on arrival.
        @Test
        void aTimerOfAPreviousIncarnation_isDroppedOnArrival() {
            try (var rig = new ClusterRig()) {
                rig.activate();
                var oldTimers = rig.clock.armed();

                rig.interpreter.deactivate();
                rig.settle();
                rig.activate();
                var before = rig.countOf("Timer");

                oldTimers.forEach(ManualTimerService::run);
                rig.settle();

                assertThat(rig.countOf("Timer")).as("no stale tick reached step").isEqualTo(before);
                assertThat(rig.metric("Timer", "dropped_superseded")).isEqualTo(oldTimers.size());
            }
        }

        @Test
        void thePostActivationTimer_firesAsATimerInput_inItsIncarnation() {
            try (var rig = new ClusterRig()) {
                rig.activate();

                rig.clock.advance(2_000);
                rig.settle();

                assertThat(rig.countOf("Timer")).isGreaterThanOrEqualTo(1);
                assertThat(rig.metric("Timer", "dropped_stale")).isZero();
            }
        }
    }

    @Nested
    class Close {
        @Test
        void close_deactivatesTheFsm_cancelsTimers_andStopsTheConsumer() {
            var rig = new ClusterRig();

            rig.activate();
            assertThat(rig.clock.armed()).isNotEmpty();

            rig.close();

            assertThat(rig.countOf("Deactivate")).as("the FSM went Dormant through its own Deactivate").isEqualTo(1);
            assertThat(rig.interpreter.state()).isInstanceOf(ClusterState.Dormant.class);
            assertThat(rig.clock.armed()).isEmpty();
            assertThat(Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("deploy-fsm-cluster") && t.isAlive()).count()).isZero();
            assertThat(rig.interpreter.deliver(new ClusterInput.Flush())).isFalse();
        }
    }
}
