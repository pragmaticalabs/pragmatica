// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.deployment.cluster.fsm2.RoutedEvent;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.lang.Option;
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

    /// W-12 (the production caller of `offerCoalescing`, M1, MT-13b) and W-33 (the interpreter half of the seam tests, D-4 (ii), G-3, G-4).
    @Nested
    class SeamDelivery {
        private static final AetherValue.ProcessIncarnation P1 = new AetherValue.ProcessIncarnation(1L, 0x0AL);

        private long coreSuspicionMs() {
            return ClusterRig.bounds().coreSuspicion() / 1_000_000L + 1_000L;
        }

        private Seams withCoreView(Set<NodeId> view) {
            return Seams.UNWIRED
                    .withCountedCores(Set.of(SELF, ClusterRig.CORE_2, ClusterRig.CORE_3))
                    .withReady(Set.of(SELF, ClusterRig.CORE_2, ClusterRig.CORE_3))
                    .withCoreView(Option.some(view));
        }

        private ClusterModel model(ClusterRig rig) {
            return ((ClusterState.Active) rig.interpreter.state()).model();
        }

        /// W-12 / M1 / MT-13b through the production interpreter: of two queued samples only the newer survives, and it stays behind the input
        /// that was enqueued before it.
        @Test
        void refreshSeams_dropsTheOlderSample_andNeverOvertakesAnEarlierInput() {
            try (var rig = new ClusterRig()) {
                rig.activate();
                var flushes = rig.countOf("Flush");

                rig.holdConsumerAt("Flush");
                rig.interpreter.deliver(new ClusterInput.Flush());
                rig.awaitThat(() -> rig.countOf("Flush") == flushes + 1);
                var before = rig.countOf("SeamsRefreshed");

                rig.interpreter.refreshSeams(withCoreView(Set.of(SELF, ClusterRig.CORE_2)));
                rig.interpreter.deliver(new ClusterInput.NodeRemoved(ClusterRig.CORE_2));
                rig.interpreter.refreshSeams(withCoreView(Set.of(SELF)));
                rig.release();
                rig.settle();

                assertThat(rig.countOf("SeamsRefreshed") - before).as("the older sample was dropped").isEqualTo(1);
                var order = List.copyOf(rig.stepped);

                assertThat(order.lastIndexOf("SeamsRefreshed")).as("the surviving sample is behind NodeRemoved").isGreaterThan(order.lastIndexOf("NodeRemoved"));
            }
        }

        @Test
        void refreshSeams_afterClose_isRefusedAndCountedClosed() {
            var rig = new ClusterRig();

            rig.close();

            assertThat(rig.interpreter.refreshSeams(withCoreView(Set.of(SELF)))).isFalse();
            assertThat(rig.metric("SeamsRefreshed", "closed")).isEqualTo(1);
        }

        /// W-33 (D-4 (ii)): the `Rebuild` snapshot carries no `joined` seed, so a committed DEPARTED core that the leader's own view lists at
        /// `Rebuild` is still departed right after it; only a full window of continuous listing, measured after `Rebuild`, joins it.
        @Test
        void theRebuildSeed_neverSupersedesADepartedRecord_onlyAFullWindowAfterRebuildDoes() {
            try (var rig = new ClusterRig()) {
                assertThat(ClusterInput.Snapshot.class.getRecordComponents())
                        .extracting(RecordComponent::getName)
                        .as("the snapshot has no joined seed (the membership view is not a seed)")
                        .doesNotContain("joined", "inherited", "members");
                rig.departed(ClusterRig.CORE_2, P1);
                rig.supplied = withCoreView(Set.of(SELF, ClusterRig.CORE_2, ClusterRig.CORE_3));

                rig.activate();

                assertThat(model(rig).isDeparted(ClusterRig.CORE_2)).as("MT-26: seeded from the Rebuild view it would already be joined").isTrue();

                rig.clock.advance(coreSuspicionMs());
                rig.settle();

                assertThat(model(rig).isDeparted(ClusterRig.CORE_2)).as("control: a full window after Rebuild supersedes the record").isFalse();
            }
        }

        /// CI-15b through the production interpreter: the token the seam learned reaches the node's admission, and the join process keeps the
        /// boot incarnation it had.
        @Test
        void processKnown_carriesTheLearnedToken_toTheNodesAdmission() {
            try (var rig = new ClusterRig()) {
                rig.activate();

                assertThat(rig.interpreter.processKnown(ClusterRig.CORE_2, 0x77L)).isTrue();
                rig.settle();

                assertThat(model(rig).admission(ClusterRig.CORE_2).map(admission -> admission.process().bootToken())).isEqualTo(Option.some(0x77L));
            }
        }

        /// W-33: a `SeamsRefreshed.coreView` reaches the FSM's liveness clock. With no view at `Rebuild`, the clock starts when a sample
        /// that lists the core arrives, and the same window then completes.
        @Test
        void aCoreViewDeliveredAfterRebuild_startsTheLivenessClock() {
            try (var rig = new ClusterRig()) {
                rig.departed(ClusterRig.CORE_2, P1);
                rig.activate();
                assertThat(model(rig).seams().coreView()).isEqualTo(Option.none());

                rig.interpreter.refreshSeams(withCoreView(Set.of(SELF, ClusterRig.CORE_2)));
                rig.settle();
                assertThat(model(rig).seams().coreView()).as("the sample reached the model").isEqualTo(Option.some(Set.of(SELF, ClusterRig.CORE_2)));
                assertThat(model(rig).isDeparted(ClusterRig.CORE_2)).isTrue();

                rig.clock.advance(coreSuspicionMs());
                rig.settle();

                assertThat(model(rig).isDeparted(ClusterRig.CORE_2)).as("the window ran from the delivered sample").isFalse();
            }
        }

        /// W-33 (G-4): `coreView` at `Rebuild` is in the model before the first reconcile, because the seams are read in the same dispatch as
        /// the snapshot, so the deferral of C17b for a liveness-pending core has its view.
        @Test
        void theCoreViewAtRebuild_isInTheModelBeforeTheFirstReconcile() {
            try (var rig = new ClusterRig()) {
                var view = withCoreView(Set.of(SELF, ClusterRig.CORE_2));

                rig.supplied = view;
                rig.activate();

                assertThat(rig.atRebuild).as("the model held the view when the Rebuild step ended").isEqualTo(view);
                var order = List.copyOf(rig.stepped);

                assertThat(order.indexOf("Rebuild")).isLessThan(order.indexOf("Reconcile"));
            }
        }

        /// W-33 (G-3): evidence observed after `Rebuild` reaches the FSM: a committed fact is stepped as a `KvPut` (the announcement route itself is
        /// `Fsm2Routes`' W-01), and a `localAlive` sample is stepped as `SeamsRefreshed` and held by the model.
        @Test
        void aKvPutAndALocalAliveSample_observedAfterRebuild_reachTheFsm() {
            try (var rig = new ClusterRig()) {
                rig.activate();
                var puts = rig.countOf("KvPut");

                rig.target(ORDERS, 1);
                rig.interpreter.refreshSeams(Seams.UNWIRED.withLocalAlive(Option.some(Set.of(SELF))));
                rig.settle();

                assertThat(rig.countOf("KvPut")).as("the committed fact was stepped").isGreaterThan(puts);
                assertThat(model(rig).seams().localAlive()).as("the localAlive sample was stepped").isEqualTo(Option.some(Set.of(SELF)));
            }
        }
    }

    /// B14, design 4.4: `onStateRestored` under an Active leader's FSM. A restore implies PASSIVE first, so the leader's FSM is normally Dormant when
    /// it fires and nothing happens; if it is not, it is re-incarnated in one dispatch.
    @Nested
    class StateRestored {
        private long incarnationOf(ClusterRig rig) {
            return ((ClusterState.Active) rig.interpreter.state()).model().incarnation();
        }

        @Test
        void aRestoreUnderAnActiveFsm_reincarnatesIt_deactivateThenActivate_andReadsAFreshSnapshot() {
            try (var rig = new ClusterRig()) {
                rig.activate();
                var before = incarnationOf(rig);
                var snapshots = rig.snapshots.get();

                rig.interpreter.stateRestored();
                rig.awaitThat(() -> rig.metric("StateRestored", "reincarnated") == 1);
                rig.settle();

                assertThat(rig.isActive()).isTrue();
                assertThat(incarnationOf(rig)).as("a fresh incarnation: no slice outage, the stale timers and settlements die").isGreaterThan(before);
                assertThat(rig.snapshots.get()).as("and a fresh snapshot").isEqualTo(snapshots + 1);
                assertThat(rig.stepped).containsSubsequence("Rebuild", "Activate", "Deactivate", "Rebuild", "Activate");
            }
        }

        /// An activation whose snapshot read failed rests in Rebuilding, a leader's FSM all the same: the restore re-incarnates it, and this time the
        /// snapshot is read.
        @Test
        void aRestoreWhileRebuilding_reincarnatesIt_too() {
            try (var rig = new ClusterRig()) {
                rig.failNextSnapshot = true;
                rig.interpreter.activate();
                rig.settle();
                assertThat(rig.interpreter.state()).isInstanceOf(ClusterState.Rebuilding.class);

                rig.interpreter.stateRestored();
                rig.awaitThat(() -> rig.metric("StateRestored", "reincarnated") == 1);
                rig.settle();

                assertThat(rig.isActive()).isTrue();
            }
        }

        /// The reason the rule is expected never to fire: Dormant is the ordinary state at a restore.
        @Test
        void aRestoreUnderADormantFsm_doesNothing_andIsCountedSkipped() {
            try (var rig = new ClusterRig()) {
                rig.interpreter.stateRestored();
                rig.settle();

                assertThat(rig.metric("StateRestored", "skipped_dormant")).isEqualTo(1);
                assertThat(rig.interpreter.state()).isInstanceOf(ClusterState.Dormant.class);
                assertThat(rig.stepped).as("no Activate was made up").doesNotContain("Activate", "Rebuild");
                assertThat(rig.snapshots.get()).isZero();
            }
        }

        @Test
        void aRestoreAfterClose_isRefused_andCountedClosed() {
            var rig = new ClusterRig();

            rig.close();

            assertThat(rig.interpreter.stateRestored()).isFalse();
            assertThat(rig.metric("StateRestored", "closed")).isEqualTo(1);
        }
    }

    /// W-27: a stop is not held by an inline `Route` subscriber (P3-Q19) past the cluster's stop bound. The subscriber here is the step observer
    /// the rig blocks in, which runs on the consumer exactly where a `Route` subscriber does.
    @Nested
    class Stop {
        @Test
        void aConsumerStuckInASubscriber_doesNotHoldTheStopPastTheBound_andTheStopSaysSo() {
            try (var logs = LogCapture.capture(FsmRuntime.class, Level.ALL, false)) {
                var rig = new ClusterRig(400L);

                rig.activate();
                rig.holdConsumerAt("WitnessStale");
                rig.interpreter.deliver(new ClusterInput.WitnessStale());
                rig.awaitThat(() -> rig.countOf("WitnessStale") == 1);

                var started = System.nanoTime();

                rig.interpreter.close();

                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("W-27: held past the bound").isLessThan(Duration.ofSeconds(4));
                assertThat(logs.entries()).as("W-27: no WARN").anySatisfy(line -> assertThat(line.message()).contains("deployment-fsm-stop-timeout").contains("400 ms"));
                rig.release();
                rig.awaitThat(() -> rig.interpreter.state() instanceof ClusterState.Dormant);
            }
        }

        @Test
        void aStopOfAnIdleLeader_deactivatesFirst_andLogsNoStopTimeout() {
            try (var logs = LogCapture.capture(FsmRuntime.class, Level.ALL, false)) {
                var rig = new ClusterRig(400L);

                rig.activate();
                rig.interpreter.close();

                assertThat(rig.interpreter.state()).isInstanceOf(ClusterState.Dormant.class);
                assertThat(logs.entries()).noneMatch(line -> line.message().contains("deployment-fsm-stop-timeout"));
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
