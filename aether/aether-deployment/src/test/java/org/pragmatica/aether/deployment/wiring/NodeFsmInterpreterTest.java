// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.fsm.ActivationWriteFault;
import org.pragmatica.aether.deployment.node.machine.LegCommand;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// The node FSM interpreter against the production `NodeFsm`, `Submitter`, `KvSnapshotter` and the real `KVStore` applier (phase-3 design 3,
/// 4): W-10 (snapshot at a revision), W-19 (core replay rule), W-07 (one `apply` per step), W-08 (timers through `FsmTimers`), W-12 (no
/// inline step), W-35 (interpreter arm), and the first arms of W-36 (the write probe through the interpreter). What these tests cannot
/// prove, because no route is bound in this PR, is named in the report: that the production `Fsm2Routes` stamp and deliver, and which
/// timeout the production wiring passes to `nodeFsmTimings`.
class NodeFsmInterpreterTest {
    private static boolean observedActive(List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .anyMatch(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                           && put.value() instanceof SliceObservedValue row
                                           && row.state() == SliceObservedState.ACTIVE);
    }

    @Nested
    class Entry {
        @Test
        void requestEntry_stepsQuorumEstablishedFromTheSnapshot_andRunsTheChain() {
            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();

                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);

                assertThat(rig.selfReady.get()).as("SelfReady is signalled once on entry").isEqualTo(1);
                assertThat(rig.localOps.steps()).containsSubsequence(LocalStep.LOAD,
                                                                   LocalStep.ACTIVATE,
                                                                   LocalStep.REGISTER_BRIDGE,
                                                                   LocalStep.BUILD_LEGS,
                                                                   LocalStep.REGISTER_CONFIG,
                                                                   LocalStep.PUBLISH_ROUTES,
                                                                   LocalStep.DESCRIBE_ACTIVE);
                assertThat(rig.interpreter.model().active()).isTrue();
                assertThat(rig.interpreter.model().instance(ORDERS).observed().isPresent()).isTrue();
            }
        }

        @Test
        void requestEntry_whileActive_readsNoSnapshot_andSignalsNoSecondSelfReady() {
            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
                var snapshotsBefore = rig.snapshots.get();

                rig.interpreter.requestEntry(true);
                rig.interpreter.requestEntry(false);
                rig.settle();

                assertThat(rig.snapshots.get()).as("the loaded-set supplier is read once per snapshot").isEqualTo(snapshotsBefore);
                assertThat(rig.metric("EnterRequested", "skipped_active")).isEqualTo(2);
                assertThat(rig.selfReady.get()).isEqualTo(1);
            }
        }

        @Test
        void aGenuineQuorumLoss_thenEntryAgain_snapshotsAgain_underANewIncarnation() {
            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
                var first = rig.interpreter.incarnation();

                rig.interpreter.deliver(new NodeFsmInput.QuorumDisappeared());
                rig.settle();
                assertThat(rig.interpreter.model().active()).isFalse();
                rig.interpreter.requestEntry(false);
                rig.settle();

                assertThat(rig.interpreter.incarnation()).isGreaterThan(first);
                assertThat(rig.snapshots.get()).isEqualTo(2);
                assertThat(rig.selfReady.get()).isEqualTo(2);
            }
        }
    }

    @Nested
    class SnapshotRevision {
        /// W-10: an input stamped at or below the snapshot's revision is in the snapshot already.
        @Test
        void aKvInputAtOrBelowTheSnapshotRevision_neverReachesStep_onePastItDoes() {
            try (var rig = new NodeRig()) {
                var revision = rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).applier.slot();

                rig.enter();
                var loads = rig.localOps.count(LocalStep.LOAD);

                rig.interpreter.deliverKv(new NodeFsmInput.TargetPut(BILLING.base(), BILLING.version()), revision, false);
                rig.interpreter.deliverKv(new NodeFsmInput.DesiredPut(BILLING, desired(SliceGoal.LOADED)), revision, false);
                rig.settle();

                assertThat(rig.metric("TargetPut", "dropped_revision")).isEqualTo(1);
                assertThat(rig.metric("DesiredPut", "dropped_revision")).isEqualTo(1);
                assertThat(rig.localOps.count(LocalStep.LOAD)).as("MT-10b: the stale goal started a load").isEqualTo(loads);

                rig.interpreter.deliverKv(new NodeFsmInput.TargetPut(BILLING.base(), BILLING.version()), revision + 1, false);
                rig.interpreter.deliverKv(new NodeFsmInput.DesiredPut(BILLING, desired(SliceGoal.LOADED)), revision + 1, false);
                rig.settle();

                rig.awaitThat(() -> rig.localOps.count(LocalStep.LOAD) == loads + 1);
            }
        }

        /// Review M2: a put-then-remove of a key absent from the snapshot must not be re-declared by the put's late arrival.
        @Test
        void aPutThenRemoveOfAKeyAbsentFromTheSnapshot_isNotRedeclared() {
            try (var rig = new NodeRig()) {
                var revision = rig.permit(BILLING).goal(ORDERS, SliceGoal.LOADED).applier.slot();

                rig.enter();
                var loads = rig.localOps.count(LocalStep.LOAD);

                rig.interpreter.deliverKv(new NodeFsmInput.DesiredPut(BILLING, desired(SliceGoal.ACTIVE)), revision - 1, false);
                rig.interpreter.deliverKv(new NodeFsmInput.DesiredRemove(BILLING), revision, false);
                rig.settle();

                assertThat(rig.localOps.count(LocalStep.LOAD)).as("the replayed put would have started BILLING").isEqualTo(loads);
                assertThat(rig.interpreter.model().instance(BILLING).desired().isPresent()).isFalse();
            }
        }

        /// The v5, v6 case of W-10: the snapshot holds the own row at version 6; the queued v5 put must not make the FSM act.
        @Test
        void aStaleObservedVersion_afterASnapshotAtALaterOne_emitsNoSubmit() {
            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
                rig.interpreter.deliver(new NodeFsmInput.QuorumDisappeared());
                rig.settle();
                var revision = rig.applier.slot();

                rig.interpreter.requestEntry(false);
                rig.settle();
                var applies = rig.cluster.applied.size();
                var loads = rig.localOps.count(LocalStep.LOAD);
                var stale = new SliceObservedValue(SliceObservedState.LOADING, 5, G1, PROCESS, Option.none(), false, 0, List.of(), BOOT_INCARNATION);

                rig.interpreter.deliverKv(new NodeFsmInput.ObservedPut(SELF, ORDERS, stale, SELF_TOKEN), revision - 1, false);
                rig.settle();

                assertThat(rig.cluster.applied).as("no Submit for a row the snapshot already supersedes").hasSize(applies);
                assertThat(rig.localOps.count(LocalStep.LOAD)).isEqualTo(loads);
            }
        }

        /// W-10 (e): the batch after the snapshot (slot r + 1) must reach `step`; with the stamp read off the field it would carry r.
        @Test
        void theBatchRightAfterTheSnapshot_reachesStep() {
            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
                var before = rig.countOf("TargetPut");

                rig.permit(BILLING);
                rig.settle();

                assertThat(rig.countOf("TargetPut")).as("slot r + 1 is stepped").isEqualTo(before + 1);
                assertThat(rig.metric("TargetPut", "dropped_revision")).isZero();
            }
        }

        /// W-19, core arm: a restore never runs while the engine is ACTIVE, so a replay-flagged input carries install-time values.
        @Test
        void aReplayFlaggedKvInput_isDropped_evenAboveTheSnapshotRevision() {
            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
                var revision = rig.applier.slot();
                var loads = rig.localOps.count(LocalStep.LOAD);

                rig.interpreter.deliverKv(new NodeFsmInput.TargetPut(BILLING.base(), BILLING.version()), revision + 5, true);
                rig.interpreter.deliverKv(new NodeFsmInput.DesiredPut(BILLING, desired(SliceGoal.LOADED)), revision + 5, true);
                rig.settle();

                assertThat(rig.metric("DesiredPut", "dropped_replay")).isEqualTo(1);
                assertThat(rig.localOps.count(LocalStep.LOAD)).as("MT-20a: applied as a fact (the worker rule)").isEqualTo(loads);
            }
        }

        @Test
        void aNonKvInput_isNeverFilteredByRevision() {
            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
                var before = rig.localOps.count(LocalStep.NOTIFY_CONFIG);

                rig.interpreter.deliver(new NodeFsmInput.ConfigChanged("any.key"));
                rig.settle();

                assertThat(rig.localOps.count(LocalStep.NOTIFY_CONFIG)).isGreaterThan(before);
            }
        }
    }

    @Nested
    class Writes {
        /// W-07: the Submits of one step leave in one `apply`.
        @Test
        void theSubmitsOfOneStep_leaveInOneApply() {
            try (var rig = new NodeRig()) {
                rig.localOps.held.add(LocalStep.LOAD);
                rig.permit(ORDERS).permit(BILLING).goal(ORDERS, SliceGoal.LOADED).goal(BILLING, SliceGoal.LOADED).enter();

                rig.awaitThat(() -> !rig.cluster.applied.isEmpty());

                assertThat(rig.cluster.applied.getFirst())
                    .as("MT-07: one apply per Submit would make this a single command")
                    .hasSize(2)
                    .allSatisfy(command -> assertThat(command).isInstanceOf(KVCommand.Put.class));
                assertThat(rig.cluster.applied.size()).as("both loading rows travelled together").isEqualTo(1);
                rig.localOps.release(LocalStep.LOAD);
            }
        }

        /// W-05 node arm: a failed write is settled into the same incarnation and correlation, so the FSM retries; an echo of 0 is stale.
        @Test
        void aFailedWrite_isSettledIntoTheSameIncarnation_soTheFsmRetriesAtOnce() {
            try (var rig = new NodeRig()) {
                var real = rig.cluster.script;
                var failures = new AtomicInteger();

                rig.cluster.script = commands -> failures.getAndIncrement() == 0
                                                 ? new ConsensusError.QuorumPaused(SELF).promise()
                                                 : real.apply(commands);
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();

                rig.awaitState(ORDERS, SliceObservedState.LOADED);

                assertThat(failures.get()).as("the second attempt went out without waiting for the 35 s bound").isGreaterThanOrEqualTo(2);
            }
        }

        /// W-12 (MT-01): the apply completes on another thread; the FSM is still stepped only on the consumer thread.
        @Test
        void completions_neverStepTheFsmOnTheCompletingThread() {
            try (var rig = new NodeRig()) {
                var real = rig.cluster.script;

                rig.localOps.asynchronous = true;
                rig.cluster.script = commands -> {
                    var promise = Promise.<List<Object>>promise();

                    Thread.ofVirtual().name("apply-completion").start(() -> promise.resolve(real.apply(commands).await()));

                    return promise;
                };
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();

                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
                rig.settle();

                assertThat(rig.stepThreads).as("the consumer is the only thread that steps").containsExactly("deploy-fsm-node");
            }
        }

        /// W-35, interpreter arm: the write-confirm timer is armed at the FSM's `writeAttemptMs`, which outlasts the engine's apply timeout.
        @Test
        void writeConfirm_isArmedAfterTheEnginesApplyTimeout() {
            try (var rig = new NodeRig()) {
                rig.cluster.script = _ -> Promise.<List<Object>>promise();
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
                rig.awaitThat(() -> !rig.cluster.applied.isEmpty());
                rig.settle();

                var delays = rig.clock.armed().stream().map(entry -> entry.delayMs).toList();

                assertThat(delays).contains(rig.timings.writeAttemptMs());
                assertThat(rig.timings.writeAttemptMs()).isGreaterThan(org.pragmatica.consensus.rabia.ProtocolConfig.DEFAULT_APPLY_TIMEOUT.millis());
            }
        }
    }

    /// T on FSM2 (phase-3 design 13.1): the tracer's fault sits where a node `Submit` becomes an `apply`. It leaves the first ACTIVE submission of the
    /// target unanswered and lets the probe through: that is the whole of why T is green on FSM2.
    @Nested
    class TracerFault {
        @AfterEach
        void disarm() {
            ActivationWriteFault.disarm();
        }

        @Test
        void theFirstActiveSubmission_isLeftUnanswered_andTheProbeResubmissionCommitsIt() {
            try (var rig = new NodeRig()) {
                ActivationWriteFault.arm(SELF, ORDERS);

                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> ActivationWriteFault.unansweredSubmissions() == 1);
                rig.settle();

                assertThat(rig.cluster.applied.stream().filter(NodeFsmInterpreterTest::observedActive).count()).as("the first ACTIVE submission never reached consensus").isZero();
                assertThat(rig.stateOf(ORDERS).or(SliceObservedState.LOADING)).isNotEqualTo(SliceObservedState.ACTIVE);

                rig.clock.advance(2_000L);
                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);

                assertThat(rig.cluster.applied.stream().filter(NodeFsmInterpreterTest::observedActive).count()).as("the probe resubmission got through, once").isEqualTo(1);
                assertThat(ActivationWriteFault.unansweredSubmissions()).as("and was not itself taken").isEqualTo(1);
            }
        }

        @Test
        void disarmed_everyActiveSubmissionReachesConsensus() {
            ActivationWriteFault.arm(SELF, ORDERS);
            ActivationWriteFault.disarm();

            try (var rig = new NodeRig()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);

                assertThat(ActivationWriteFault.unansweredSubmissions()).isZero();
                assertThat(rig.cluster.applied.stream().filter(NodeFsmInterpreterTest::observedActive).count()).isEqualTo(1);
            }
        }
    }

    @Nested
    class WriteProbe {
        private NodeRig hungActiveWrite() {
            var rig = new NodeRig();
            var real = rig.cluster.script;
            var lost = new AtomicInteger();
            Predicate<List<KVCommand<AetherKey>>> theFirstActiveWrite = commands -> observedActive(commands) && lost.getAndIncrement() == 0;

            rig.cluster.script = commands -> theFirstActiveWrite.test(commands)
                                             ? Promise.<List<Object>>promise()
                                             : real.apply(commands);

            return rig;
        }

        private long activeWrites(NodeRig rig) {
            synchronized (rig.cluster.applied) {
                return rig.cluster.applied.stream().filter(NodeFsmInterpreterTest::observedActive).count();
            }
        }

        private NodeRig lostActiveWrites() {
            var rig = new NodeRig();
            var real = rig.cluster.script;

            rig.cluster.script = commands -> observedActive(commands)
                                             ? Promise.<List<Object>>promise()
                                             : real.apply(commands);

            return rig;
        }

        /// W-36 (a), MT-35: `WRITE_PROBE` is scheduled through `FsmTimers`: the first probe is armed at 2 s beside the confirm bound.
        @Test
        void aWriteInFlight_armsTheConfirmBoundAndTheFirstProbe_throughTheTimerMap() {
            try (var rig = hungActiveWrite()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> activeWrites(rig) == 1);
                rig.settle();

                assertThat(rig.clock.armed().stream().map(entry -> entry.delayMs)).contains(2_000L, rig.timings.writeAttemptMs());
            }
        }

        /// W-36 (a): at most one live probe per write, however many probes fire; the series ends after the last scheduled probe.
        @Test
        void theProbeSeries_keepsOneLiveProbe_andEndsAfterTheLastOffset() {
            try (var rig = lostActiveWrites()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> activeWrites(rig) == 1);
                rig.settle();
                var armed = rig.interpreter.armedTimers();

                rig.clock.advance(2_000);
                rig.awaitThat(() -> activeWrites(rig) == 2);
                rig.settle();
                assertThat(rig.interpreter.armedTimers()).as("after probe 1 the next probe replaced it").isEqualTo(armed);

                rig.clock.advance(4_000);
                rig.awaitThat(() -> activeWrites(rig) == 3);
                rig.settle();
                assertThat(rig.interpreter.armedTimers()).as("after probe 2").isEqualTo(armed);

                rig.clock.advance(8_000);
                rig.awaitThat(() -> activeWrites(rig) == 4);
                rig.settle();
                assertThat(rig.interpreter.armedTimers()).as("the third probe was the last: its timer is spent").isEqualTo(armed - 1);
                assertThat(activeWrites(rig)).as("one attempt's own submission and three probes, no attempt consumed").isEqualTo(4);
            }
        }

        /// W-36 (c), MT-37: the probe resubmits the attempt's observed Put at the same version, under a new correlation.
        @Test
        void aProbeTick_resubmitsTheObservedPut_atTheSameVersion() {
            try (var rig = hungActiveWrite()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> activeWrites(rig) == 1);
                rig.settle();
                var first = (KVCommand.Put<?, ?>) rig.cluster.applied.stream().filter(NodeFsmInterpreterTest::observedActive).findFirst().orElseThrow().getFirst();

                rig.clock.advance(2_000);
                rig.awaitThat(() -> activeWrites(rig) == 2);

                var probe = (KVCommand.Put<?, ?>) rig.cluster.applied.stream().filter(NodeFsmInterpreterTest::observedActive).skip(1).findFirst().orElseThrow().getFirst();

                assertThat(((SliceObservedValue) probe.value()).version()).isEqualTo(((SliceObservedValue) first.value()).version());
                assertThat(probe.key()).isEqualTo(first.key());
                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
            }
        }

        /// W-36, the real-applier arm (spec NT-134 (b)): the original ACTIVE write and its probe resubmission both reach the REAL applier, in either
        /// order. The second is a fenced Put at a version already written: refused silently, so the node receives exactly one own `ObservedPut` for
        /// the ACTIVE row and the instance is ACTIVE once. Every submission is held and applied by hand, so the order is the test's.
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void theOriginalAndItsProbe_appliedInEitherOrder_leaveOneRow_andOneOwnObservedPut(boolean originalFirst) {
            try (var rig = new NodeRig()) {
                var real = rig.cluster.script;
                var held = new CopyOnWriteArrayList<List<KVCommand<AetherKey>>>();

                rig.cluster.script = commands -> {
                    if (observedActive(commands)) {
                        held.add(commands);

                        return Promise.<List<Object>>promise();
                    }

                    return real.apply(commands);
                };
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> held.size() == 1);
                rig.clock.advance(2_000);
                rig.awaitThat(() -> held.size() == 2);
                rig.settle();
                var before = rig.countOf("ObservedPut");

                var order = originalFirst
                            ? List.of(held.get(0), held.get(1))
                            : List.of(held.get(1), held.get(0));

                order.forEach(rig.applier::commit);
                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
                rig.settle();

                assertThat(rig.countOf("ObservedPut") - before).as("one own ObservedPut for the row; the fenced duplicate is refused silently").isEqualTo(1);
                assertThat(rig.stateOf(ORDERS)).isEqualTo(Option.some(SliceObservedState.ACTIVE));
            }
        }

        /// v-2204 F1: a round is counted when it leaves for the engine (`submitted`), the attempt and its probe resubmission apart. The settlement-side
        /// `accepted` arm sees almost none of a clean deploy's rounds, because the row echo clears the write first.
        @Test
        void everySubmitThatLeavesForTheEngine_isCountedAtSubmission_attemptAndProbeApart() {
            try (var rig = hungActiveWrite()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> activeWrites(rig) == 1);
                rig.settle();
                var attempts = rig.submitted("observed");

                rig.clock.advance(2_000);
                rig.awaitThat(() -> activeWrites(rig) == 2);
                rig.settle();

                assertThat(attempts).as("the attempts counted so far").isGreaterThanOrEqualTo(1);
                assertThat(rig.submitted("observed_probe")).as("the probe resubmission, counted apart").isEqualTo(1);
                assertThat(rig.submitted("observed")).as("the attempt count does not move with the probe").isEqualTo(attempts);
            }
        }

        /// W-36 (a): a probe tick whose write has since been confirmed is superseded (its timer was cancelled) and never reaches `step`.
        @Test
        void aSupersededProbeTick_neverReachesStep() {
            try (var rig = hungActiveWrite()) {
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> activeWrites(rig) == 1);
                rig.settle();
                var firstProbe = rig.clock.armed().stream().filter(entry -> entry.delayMs == 2_000L).findFirst().orElseThrow();

                rig.clock.advance(2_000);
                rig.awaitState(ORDERS, SliceObservedState.ACTIVE);
                rig.settle();
                var applies = rig.cluster.applied.size();
                var dropped = rig.metric("Timer", "dropped_superseded");

                ManualTimerService.run(firstProbe);
                rig.settle();

                assertThat(rig.metric("Timer", "dropped_superseded")).isEqualTo(dropped + 1);
                assertThat(rig.cluster.applied).as("no resubmission after the write was confirmed").hasSize(applies);
            }
        }

        /// W-36 (c), MT-36: the probe's own settlement carries the probe's correlation, so its failure is not the attempt's failure.
        @Test
        void aFailedProbeResubmission_isNotTheAttemptsFailure() {
            try (var rig = new NodeRig()) {
                var real = rig.cluster.script;
                var activeWrites = new AtomicInteger();

                rig.cluster.script = commands -> {
                    if (observedActive(commands)) {
                        return activeWrites.getAndIncrement() == 0
                               ? Promise.<List<Object>>promise()
                               : new ConsensusError.QuorumPaused(SELF).promise();
                    }

                    return real.apply(commands);
                };
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> activeWrites.get() == 1);

                rig.clock.advance(2_000);
                rig.awaitThat(() -> activeWrites.get() == 2);
                rig.settle();

                assertThat(activeWrites.get()).as("a failed probe consumes no attempt and starts no retry").isEqualTo(2);
            }
        }

        /// W-36 (b3), MT-39: the probe's batch is rebuilt from the probe-time builders, never the first-submission batch.
        @Test
        void aProbeBatch_isRebuiltFromTheBuildersAtProbeTime_notReplayedFromTheFirstSubmission() {
            try (var rig = hungActiveWrite()) {
                var legKey = new SliceTargetKey(AUDIT.base());
                var calls = new AtomicInteger();

                rig.localOps.script.put(LocalStep.BUILD_LEGS,
                                        () -> LocalOutcome.done(List.of(LegCommand.legCommand(LegCommand.LegKind.TOPIC_SUBSCRIPTION,
                                                                                              new KVCommand.Put<AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue>(legKey,
                                                                                                                                                                            SliceTargetValue.sliceTargetValue(AUDIT.version(),
                                                                                                                                                                                                              calls.incrementAndGet(),
                                                                                                                                                                                                              1))))));
                rig.permit(ORDERS).goal(ORDERS, SliceGoal.ACTIVE).enter();
                rig.awaitThat(() -> activeWrites(rig) == 1);
                rig.settle();

                rig.clock.advance(2_000);
                rig.awaitThat(() -> activeWrites(rig) == 2);

                var batches = rig.cluster.applied.stream().filter(NodeFsmInterpreterTest::observedActive).toList();
                var firstLeg = (KVCommand.Put<?, ?>) batches.get(0).get(1);
                var probeLeg = (KVCommand.Put<?, ?>) batches.get(1).get(1);

                assertThat(((SliceTargetValue) firstLeg.value()).targetInstances()).isEqualTo(1);
                assertThat(((SliceTargetValue) probeLeg.value()).targetInstances())
                    .as("the leg comes from a second BUILD_LEGS run at probe time")
                    .isEqualTo(2);
            }
        }
    }

    @Nested
    class Close {
        @Test
        void close_cancelsEveryTimer_stopsTheConsumer_andClosesTheLocalRuntime() {
            var rig = new NodeRig();

            rig.cluster.script = _ -> Promise.<List<Object>>promise();
            rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
            rig.awaitThat(() -> !rig.cluster.applied.isEmpty());
            rig.settle();
            assertThat(rig.interpreter.armedTimers()).isPositive();

            rig.close();

            assertThat(rig.clock.armed()).as("every armed timer was cancelled").isEmpty();
            assertThat(rig.localOps.closed).isTrue();
            assertThat(Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("deploy-fsm-node") && t.isAlive()).count())
                .as("no consumer thread is left")
                .isZero();
            assertThat(rig.interpreter.deliver(new NodeFsmInput.QuorumDisappeared())).as("a late input is refused, not queued").isFalse();
        }

        @Test
        void close_withoutStart_returnsAtOnce_andStillClosesTheLocalRuntime() {
            var rig = new NodeRig(false);

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), rig::close);

            assertThat(rig.localOps.closed).isTrue();
        }

        @Test
        void aSettlementThatArrivesAfterClose_isDropped_withoutError() {
            var rig = new NodeRig();
            var hung = Promise.<List<Object>>promise();

            rig.cluster.script = _ -> hung;
            rig.permit(ORDERS).goal(ORDERS, SliceGoal.LOADED).enter();
            rig.awaitThat(() -> !rig.cluster.applied.isEmpty());
            rig.close();
            var stepsAtClose = rig.stepped.size();
            var appliesAtClose = rig.cluster.applied.size();

            hung.fail(new ConsensusError.NodeInactive(SELF));
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> true);

            assertThat(rig.interpreter.mailboxDepth()).as("nothing queued behind a stopped consumer").isZero();
            assertThat(rig.stepped).as("the late settlement was never stepped").hasSize(stepsAtClose);
            assertThat(rig.cluster.applied).as("and nothing was submitted because of it").hasSize(appliesAtClose);
            assertThat(rig.metric("WriteSettled", "closed")).as("the refused offer is counted").isEqualTo(1);
        }
    }
}
