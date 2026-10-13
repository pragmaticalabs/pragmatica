// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G3;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.SELF;

/// Observed writes (spec R-3.4.4, R-3.4.9, R-1.6.3, N19): the retry bound, the FAILED cycles, the version chain against the
/// real applier, the generation a chain stamps, and the tombstone. NT-67, NT-68, NT-87, NT-93, NT-97, NT-104, NT-109.
class NodeFsmWriteTest {
    private static SliceObservedValue row(NodeHarness h) {
        return h.observed(ARTIFACT).or((SliceObservedValue) null);
    }

    @Nested
    class Retry {
        /// Red when: a lost write is not attempted exactly three times at the attempt bound, a retry is not the same
        /// version-fenced Put, or the final cause is not the typed exhaustion (the FAILED reason is its message, not fatal).
        @Test
        @DisplayName("NT-68 B-151: three attempts with the same Put, then a typed retryable FAILED")
        void nt68_threeAttempts_sameVersion_typedFinalCause() {
            var h = new NodeHarness();

            h.loseWritesOf(SliceObservedState.LOADING).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(h.timings.writeAttempts() * h.timings.writeAttemptMs());

            var loading = h.effectsOf(NodeFsmEffect.Submit.class)
                           .stream()
                           .filter(submit -> NodeHarness.putOf(submit).map(put -> put.state() == SliceObservedState.LOADING).or(false))
                           .toList();

            assertThat(loading).extracting(NodeFsmEffect.Submit::attempt).containsExactly(1, 2, 3);
            assertThat(loading).extracting(NodeFsmEffect.Submit::commands).containsOnly(loading.getFirst().commands());
            assertThat(loading).extracting(NodeFsmEffect.Submit::correlation).doesNotHaveDuplicates();
            assertThat(NodeHarness.putOf(loading.getFirst()).map(SliceObservedValue::version).or(0L)).isEqualTo(1L);

            var failed = row(h);

            assertThat(failed.state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(failed.fatal()).isFalse();
            assertThat(failed.failureReason().or("")).isEqualTo(NodeFsmError.WriteExhausted.FACTORY.apply(ARTIFACT, 1L, 3).message());
        }

        /// Red when: the FAILED write is not retried in 6 cycles 2 s apart, a cycle is not 3 attempts, the CRITICAL line is
        /// missing or comes before the last cycle ended.
        @Test
        @DisplayName("NT-67 B-150: the FAILED row is retried in 6 cycles 2 s apart, then CRITICAL")
        void nt67_failedRetry_sixCyclesThenCritical() {
            var h = new NodeHarness();

            h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")))
             .loseWritesOf(SliceObservedState.FAILED)
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.LOADED, G1);

            var timings = h.timings;
            var cycle = timings.writeAttempts() * timings.writeAttemptMs();

            assertThat(h.submitsOf(SliceObservedState.FAILED)).hasSize(1);
            assertThat(h.effectsOf(NodeFsmEffect.Log.class)).noneMatch(log -> log.level() == NodeFsmEffect.Level.CRITICAL);

            h.advance(timings.failedCycles() * cycle + (timings.failedCycles() - 1) * timings.failedRetryMs() - 1L);

            assertThat(h.effectsOf(NodeFsmEffect.Log.class)).as("not before the last cycle ended")
                                                           .noneMatch(log -> log.level() == NodeFsmEffect.Level.CRITICAL);

            h.advance(1L);

            assertThat(h.submitsOf(SliceObservedState.FAILED)).as("one first attempt per cycle").hasSize(timings.failedCycles());
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)
                        .stream()
                        .filter(submit -> NodeHarness.putOf(submit).map(put -> put.state() == SliceObservedState.FAILED).or(false)))
                .hasSize(timings.failedCycles() * timings.writeAttempts());
            assertThat(h.effectsOf(NodeFsmEffect.Schedule.class)
                        .stream()
                        .filter(schedule -> schedule.timer().kind() == TimerId.TimerKind.FAILED_RETRY))
                .hasSize(timings.failedCycles() - 1)
                .allMatch(schedule -> schedule.delayMs() == timings.failedRetryMs());
            assertThat(h.effectsOf(NodeFsmEffect.Log.class).stream().filter(log -> log.level() == NodeFsmEffect.Level.CRITICAL)).hasSize(1);
            assertThat(h.timers).as("nothing is left armed").isEmpty();
        }

        /// R-6.0 step 3: a FAILED row that commits late between two cycles is adopted: no further cycle writes. Red when a
        /// cycle is still submitted after the row is seen committed.
        @Test
        @DisplayName("NT-67 R-6.0: a FAILED row that lands between cycles is adopted and stops the retries")
        void nt67_lateCommittedFailed_isAdopted() {
            var h = new NodeHarness();

            h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")))
             .loseWritesOf(SliceObservedState.FAILED)
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(3 * h.timings.writeAttemptMs());

            var lostFailed = h.submitsOf(SliceObservedState.FAILED).getFirst();

            h.clearEffects();
            h.applyBatch(lostFailed.commands().stream().map(command -> (KVCommand<AetherKey>) command).toList());
            h.deliverCommitted(ARTIFACT);
            h.advance(h.timings.failedRetryMs() + h.timings.failedCycles() * 3 * h.timings.writeAttemptMs());

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
            assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(h.record(ARTIFACT).unconfirmed().isEmpty()).isTrue();
        }
    }

    @Nested
    class Remedy {
        /// Red when: a re-issued identical goal whose committed row lags the FAILED write the node gave up on does not run
        /// R-6.0 (no FAILED Put at the next version), or starts a new chain instead.
        @Test
        @DisplayName("NT-67 R-3.4.2: a re-issue after the FAILED cycles ran out rebuilds the FAILED write")
        void reissue_afterTheCyclesRanOut_rebuildsTheFailedWrite() {
            var h = new NodeHarness();

            h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")))
             .loseWritesOf(SliceObservedState.FAILED)
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(h.timings.failedCycles() * 3 * h.timings.writeAttemptMs() + h.timings.failedCycles() * h.timings.failedRetryMs());

            assertThat(row(h).state()).as("the FAILED row never landed").isEqualTo(SliceObservedState.LOADING);

            h.writeScript = _ -> null;
            h.clearEffects().goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(h.writtenStates()).containsExactly(SliceObservedState.FAILED);
            assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row(h).version()).isEqualTo(2L);
            assertThat(h.localOps()).isEmpty();
        }

        /// Red when: a chain that failed while its ACTIVE write was still in flight writes FAILED over the write (two writes in
        /// flight on the key), writes it twice, or loses the chain's own reason.
        @Test
        @DisplayName("R-3.4.9 (2): a failure that arrives while a write is in flight waits for it, and is written once")
        void failureDuringAnInFlightWrite_waitsForIt() {
            var h = new NodeHarness(NodeHarness.SELF_TOKEN, shortChain());

            h.loseWritesOf(SliceObservedState.ACTIVE).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.advance(h.timings.chainBoundMs());

            assertThat(h.submitsOf(SliceObservedState.FAILED)).as("the bound has fired but the ACTIVE write is still in flight").isEmpty();
            assertThat(h.record(ARTIFACT).deferredFailure().isPresent()).isTrue();

            h.advance(3 * h.timings.writeAttemptMs());

            assertThat(h.submitsOf(SliceObservedState.FAILED)).hasSize(1);
            assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row(h).failureReason().or("")).contains("did not finish within");
            assertThat(h.record(ARTIFACT).deferredFailure().isEmpty()).isTrue();
        }

        private NodeFsmTimings shortChain() {
            var d = NodeFsmTimings.nodeFsmTimings();

            return new NodeFsmTimings(d.loadMs(),
                                      d.activateMs(),
                                      d.deactivateMs(),
                                      d.unloadMs(),
                                      40_000L,
                                      d.activatingCheckMs() * 10L,
                                      d.tombstoneGraceMs(),
                                      d.parkedBoundMs(),
                                      d.writeAttemptMs(),
                                      d.writeAttempts(),
                                      d.failedRetryMs(),
                                      d.failedCycles());
        }
    }

    @Nested
    class Chain {
        /// Red when: a late ACTIVATING(v3) is applied while the key exists after ACTIVE(v4): the committed row regresses.
        @Test
        @DisplayName("NT-87 R-3.4.9 G-D.6: a late ACTIVATING does not regress the row while the key exists")
        void nt87_lateActivating_isRefusedByTheFence() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            var late = h.submitsOf(SliceObservedState.ACTIVATING).getFirst();

            assertThat(row(h).state()).isEqualTo(SliceObservedState.ACTIVE);
            assertThat(row(h).version()).isEqualTo(4L);

            h.applyBatch(late.commands().stream().map(command -> (KVCommand<AetherKey>) command).toList());

            assertThat(row(h).state()).isEqualTo(SliceObservedState.ACTIVE);
            assertThat(row(h).version()).isEqualTo(4L);
        }

        /// Red when: the versions of a chain are not consecutive from 1, the applier refuses any of the node's writes (the
        /// committed version lags the number of Puts), or an early WriteSettled re-drives or advances the chain before the
        /// node's own notification.
        @Test
        @DisplayName("NT-97 R-3.4.9: consecutive versions, no refused write, and an early WriteSettled changes nothing")
        void nt97_versionsAreConsecutive_andAnEarlySettleWaitsForTheNotification() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.goal(ARTIFACT, SliceGoal.LOADED, G2);
            h.goal(ARTIFACT, SliceGoal.UNLOADED, G3);

            var versions = h.observedPuts().stream().map(SliceObservedValue::version).toList();

            assertThat(versions).isEqualTo(java.util.stream.LongStream.rangeClosed(1L, versions.size()).boxed().toList());
            assertThat(row(h).version()).as("the applier accepted every Put").isEqualTo((long) versions.size());

            var early = new NodeHarness();

            early.writeMode = NodeHarness.WriteMode.APPLY_NO_NOTIFY;
            early.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(early.effectsOf(NodeFsmEffect.Submit.class)).as("settled early: no re-drive").hasSize(1);
            assertThat(early.localOps()).as("settled early: the chain does not advance").isEmpty();

            early.writeMode = NodeHarness.WriteMode.APPLY;
            early.deliverCommitted(ARTIFACT);

            assertThat(early.localSteps()).containsExactly(LocalStep.LOAD);
            assertThat(early.writtenStates()).containsExactly(SliceObservedState.LOADING, SliceObservedState.LOADED);
        }

        /// Red when: N1 does not write at version + 1 over the tombstone (the applier would refuse a first write), or waits for
        /// a Remove, or removes the tombstone itself.
        @Test
        @DisplayName("NT-104 R-1.6.3: a new goal over a tombstone writes LOADING at version + 1")
        void nt104_newGoalOverATombstone_continuesTheChain() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.goal(ARTIFACT, SliceGoal.UNLOADED, G2);

            var tombstone = row(h);

            assertThat(tombstone.state()).isEqualTo(SliceObservedState.UNLOADED);

            h.clearEffects().goal(ARTIFACT, SliceGoal.LOADED, G3);

            assertThat(h.writtenStates()).startsWith(SliceObservedState.LOADING);
            assertThat(h.observedPuts().getFirst().version()).isEqualTo(tombstone.version() + 1L);
            assertThat(row(h).state()).isEqualTo(SliceObservedState.LOADED);
            assertThat(h.effects.stream().filter(effect -> effect instanceof NodeFsmEffect.Submit submit
                                                         && submit.commands().stream().anyMatch(KVCommand.Remove.class::isInstance))).isEmpty();
        }

        /// Red when: a mid-chain goal change stamps the ending write with the new generation.
        @Test
        @DisplayName("NT-109 R-08: a goal change mid-chain does not restamp the ending write")
        void nt109_midChainGoalChange_keepsTheChainGeneration() {
            var h = new NodeHarness();

            h.hold(LocalStep.LOAD).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.goal(ARTIFACT, SliceGoal.ACTIVE, G2);
            h.settle(LocalStep.LOAD, LocalOutcome.done());

            var puts = h.observedPuts();

            assertThat(puts).filteredOn(put -> put.state() == SliceObservedState.LOADED).extracting(SliceObservedValue::forGeneration).containsExactly(G1);
            assertThat(puts).filteredOn(put -> put.state() == SliceObservedState.ACTIVATING || put.state() == SliceObservedState.ACTIVE)
                            .extracting(SliceObservedValue::forGeneration)
                            .containsOnly(G2);
            assertThat(row(h).state()).isEqualTo(SliceObservedState.ACTIVE);
        }
    }

    @Nested
    class Tombstone {
        private NodeHarness tombstoned() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.goal(ARTIFACT, SliceGoal.UNLOADED, G2);

            return h;
        }

        /// Red when: the tombstone Remove is early, unwitnessed (the applier would refuse it), or never happens; control: a
        /// bare Remove is refused by the applier.
        @Test
        @DisplayName("NT-93 R-1.6.3: the tombstone is Removed after the grace, witnessed by its exact value")
        void nt93_tombstoneRemove_isWitnessedAndAfterTheGrace() {
            var h = tombstoned();
            var tombstone = row(h);

            assertThat(tombstone.state()).isEqualTo(SliceObservedState.UNLOADED);
            assertThat(h.timersOf(TimerId.TimerKind.TOMBSTONE_GRACE)).hasSize(1);

            h.applyBatch(List.of(new KVCommand.Remove<AetherKey>(SliceObservedKey.sliceObservedKey(SELF, ARTIFACT))));

            assertThat(row(h)).as("a bare Remove is refused by the applier").isEqualTo(tombstone);

            h.clearEffects().advance(h.timings.tombstoneGraceMs() - 1L);

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("not before the grace").isEmpty();

            h.advance(1L);

            var removes = h.effectsOf(NodeFsmEffect.Submit.class);

            assertThat(removes).hasSize(1);
            assertThat(removes.getFirst().commands()).hasSize(1);
            assertThat(removes.getFirst().commands().getFirst()).isInstanceOf(KVCommand.Remove.class);
            assertThat(((KVCommand.Remove<AetherKey>) removes.getFirst().commands().getFirst()).witness().or((Object) null)).isEqualTo(tombstone);
            assertThat(h.observed(ARTIFACT).isEmpty()).as("the applier accepted the witnessed Remove").isTrue();
            assertThat(h.record(ARTIFACT).lastVersion()).as("the chain restarts at 1 after a Remove").isZero();
        }

        /// Red when: a newer goal does not cancel the pending Remove, so the tombstone is Removed although a goal now asks for
        /// the pair. The goal is parked (its target is gone) so that no chain overwrites the tombstone first and the Remove
        /// would be the only thing that could take it away.
        @Test
        @DisplayName("NT-93 R-1.6.3: a newer goal cancels the pending tombstone Remove")
        void nt93_newerGoal_cancelsTheRemove() {
            var h = tombstoned();
            var tombstone = row(h);

            h.feed(new NodeFsmInput.TargetRemove(ARTIFACT.base()));
            h.goal(ARTIFACT, SliceGoal.LOADED, G3);

            assertThat(h.record(ARTIFACT).parked().isPresent()).isTrue();

            h.clearEffects().advance(h.timings.tombstoneGraceMs() * 2L);

            assertThat(h.effects.stream().filter(effect -> effect instanceof NodeFsmEffect.Submit submit
                                                         && submit.commands().stream().anyMatch(KVCommand.Remove.class::isInstance))).isEmpty();
            assertThat(row(h)).as("the tombstone is still the committed row").isEqualTo(tombstone);
        }

        /// Red when: the Remove is issued with an own write in flight on the key (R-3.4.9 (2)).
        @Test
        @DisplayName("NT-93 R-3.4.9: no tombstone Remove while an own write is in flight")
        void nt93_noRemoveWhileAWriteIsInFlight() {
            var h = tombstoned();
            var timer = h.timersOf(TimerId.TimerKind.TOMBSTONE_GRACE).getFirst();
            var inFlight = InFlight.inFlight(99L, 7L, SliceObservedState.LOADING, List.of(), InFlight.Purpose.CHAIN, 0);
            var busy = h.model.withInstance(ARTIFACT, h.model.instance(ARTIFACT).withWrite(org.pragmatica.lang.Option.some(inFlight)));
            var step = h.fsm.step(busy, new NodeFsmInput.TimerFired(timer));

            assertThat(step.effects().stream().filter(effect -> effect instanceof NodeFsmEffect.Submit)).isEmpty();
            assertThat(step.dropped()).isTrue();
        }
    }
}
