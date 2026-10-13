// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT_B;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT_C;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.leg;

/// The transition table N1 to N18 (spec R-3.4.3), asserted on the pure step function with the real applier behind the
/// node's observed key: NT-49 to NT-61, NT-64 to NT-66, NT-69 to NT-77.
class NodeFsmChainTest {
    private static NodeHarness active() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        return h;
    }

    private static SliceObservedState stateOf(NodeHarness h) {
        return h.state(ARTIFACT).or(SliceObservedState.UNKNOWN);
    }

    @Nested
    class Load {
        /// Red when: an own observed put routes no StateTransition, a transition does not name the previous state, or a
        /// peer's put routes one.
        @Test
        @DisplayName("NT-49 B-118: an own observed put routes a StateTransition")
        void nt49_ownObservedPut_routesAStateTransition() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.peerRow(NodeHarness.PEER, ARTIFACT, SliceObservedState.LOADED, NodeHarness.PEER_TOKEN, 0L);

            var transitions = h.effectsOf(NodeFsmEffect.Route.class)
                               .stream()
                               .map(NodeFsmEffect.Route::event)
                               .map(NodeFsmEffect.RoutedEvent.StateTransitioned.class::cast)
                               .toList();

            assertThat(transitions).extracting(NodeFsmEffect.RoutedEvent.StateTransitioned::from)
                                   .containsExactly(SliceObservedState.LOADING, SliceObservedState.LOADING);
            assertThat(transitions).extracting(NodeFsmEffect.RoutedEvent.StateTransitioned::to)
                                   .containsExactly(SliceObservedState.LOADING, SliceObservedState.LOADED);
        }

        /// Red when: an observed UNKNOWN causes a write, or raises no WARN (N18).
        @Test
        @DisplayName("NT-50 B-119: an observed UNKNOWN drives nothing and warns")
        void nt50_observedUnknown_warnsAndWritesNothing() {
            var h = new NodeHarness();
            var unknown = new SliceObservedValue(SliceObservedState.UNKNOWN,
                                                 1L,
                                                 G1,
                                                 h.process,
                                                 Option.none(),
                                                 false,
                                                 0,
                                                 List.of(),
                                                 h.wall);

            h.establish().permit(ARTIFACT).feed(new NodeFsmInput.ObservedPut(NodeHarness.SELF, ARTIFACT, unknown, h.tokens.self()));
            h.goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
            assertThat(h.localOps()).isEmpty();
            assertThat(h.effectsOf(NodeFsmEffect.Log.class)).anyMatch(log -> log.level() == NodeFsmEffect.Level.WARN
                                                                           && log.message().contains("UNKNOWN"));
        }

        /// Red when: a load over its 2 minute bound does not end in a FAILED row, or the bound is not the LOADING timeout.
        @Test
        @DisplayName("NT-51 B-120: a load over its bound ends in a FAILED row")
        void nt51_loadOver120s_endsInAFailedRow() {
            var h = new NodeHarness();

            h.hold(LocalStep.LOAD).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);

            var load = h.localOps().stream().filter(op -> op.step() == LocalStep.LOAD).findFirst().orElseThrow();

            assertThat(load.timeoutMs()).isEqualTo(SliceState.LOADING.timeout().map(TimeSpan::millis).or(-1L));
            assertThat(load.timeoutMs()).isEqualTo(2 * 60_000L);

            h.settle(LocalStep.LOAD, LocalOutcome.timedOut());

            var row = h.observed(ARTIFACT).or((SliceObservedValue) null);

            assertThat(row.state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row.fatal()).as("a timeout is intermittent").isFalse();
            assertThat(row.failureReason().or("")).contains("LOAD");
        }

        /// Red when: a failed LOADING write (three lost attempts) writes fatal=true. Control: a load that itself fails
        /// with an unrecognised cause is classified PERMANENT (B-121), so the test can tell the two apart.
        @Test
        @DisplayName("NT-52 B-121: a failed LOADING write is a retryable FAILED, a failed load is classified")
        void nt52_failedLoadingWrite_isNotFatal() {
            var lost = new NodeHarness();

            lost.loseWritesOf(SliceObservedState.LOADING).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            lost.advance(3 * lost.timings.writeAttemptMs());

            var row = lost.observed(ARTIFACT).or((SliceObservedValue) null);

            assertThat(row.state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row.fatal()).as("a write that never confirmed does not condemn the artifact").isFalse();

            var failedLoad = new NodeHarness();

            failedLoad.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("disk full")))
                      .establish()
                      .permit(ARTIFACT)
                      .goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(failedLoad.observed(ARTIFACT).map(SliceObservedValue::fatal).or(false)).isTrue();
        }

        /// Red when: a duplicate goal during a load starts a second load or a second LOADING write.
        @Test
        @DisplayName("NT-53 B-122: a duplicate goal during a load starts nothing")
        void nt53_duplicateGoalDuringALoad_startsNothing() {
            var h = new NodeHarness();

            h.hold(LocalStep.LOAD).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.goal(ARTIFACT, SliceGoal.LOADED, G2);
            h.settle(LocalStep.LOAD, LocalOutcome.done());

            assertThat(h.localSteps().stream().filter(step -> step == LocalStep.LOAD).count()).isEqualTo(1L);
            assertThat(h.submitsOf(SliceObservedState.LOADING)).hasSize(1);
            assertThat(h.writtenStates()).containsExactly(SliceObservedState.LOADING, SliceObservedState.LOADED);
        }

        /// Red when: a failed load is not evicted (no EVICT step), so a retry would find the stale entry.
        @Test
        @DisplayName("NT-77 B-178: a failed load is evicted")
        void nt77_failedLoad_isEvicted() {
            var h = new NodeHarness();

            h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(h.localSteps()).contains(LocalStep.EVICT);
            assertThat(h.slicesInStore).doesNotContain(ARTIFACT);
            assertThat(h.model.isLoaded(ARTIFACT)).isFalse();
            assertThat(stateOf(h)).isEqualTo(SliceObservedState.FAILED);
        }
    }

    @Nested
    class Activate {
        /// Red when: the chain timeout does not fail the activation, or the legs are not in the ACTIVE batch, or come after it.
        @Test
        @DisplayName("NT-54 B-125: legs ride in the ACTIVE batch; the chain bound fails the activation")
        void nt54_legsRideInTheActiveBatch_andTheChainBoundFails() {
            var h = new NodeHarness();
            var topic = leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT_B);
            var scheduled = leg(LegCommand.LegKind.SCHEDULED_TASK, ARTIFACT_B.base().withVersion(ARTIFACT.version()));

            h.script(LocalStep.BUILD_LEGS, LocalOutcome.done(List.of(topic, scheduled)));
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            var activeSubmits = h.submitsOf(SliceObservedState.ACTIVE);

            assertThat(activeSubmits).hasSize(1);
            assertThat(activeSubmits.getFirst().commands()).contains(topic.command(), scheduled.command());
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class).stream()
                        .filter(submit -> submit.commands().contains(topic.command()))).as("only the ACTIVE batch carries legs").hasSize(1);

            var slow = new NodeHarness();

            slow.hold(LocalStep.ACTIVATE).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            slow.advance(slow.timings.chainBoundMs());

            var row = slow.observed(ARTIFACT).or((SliceObservedValue) null);

            assertThat(row.state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row.fatal()).isFalse();
        }

        /// Red when: any write names a state outside the table (ROUTING is not even representable), or the ACTIVE write is
        /// submitted before the routes were published.
        @Test
        @DisplayName("NT-55 B-126: ACTIVE is written after route publication and no row names ROUTING")
        void nt55_activeFollowsRoutePublication() {
            var h = active();
            var publish = h.effects.indexOf(h.localOps().stream().filter(op -> op.step() == LocalStep.PUBLISH_ROUTES).findFirst().orElseThrow());
            var active = h.effects.indexOf(h.submitsOf(SliceObservedState.ACTIVE).getFirst());

            assertThat(active).isGreaterThan(publish);
            assertThat(h.writtenStates().stream().map(Enum::name)).doesNotContain("ROUTING");
            assertThat(h.writtenStates()).containsExactly(SliceObservedState.LOADING,
                                                          SliceObservedState.LOADED,
                                                          SliceObservedState.ACTIVATING,
                                                          SliceObservedState.ACTIVE);
        }

        /// Red when: a committed ACTIVATING row arms no check at the ACTIVATING timeout.
        @Test
        @DisplayName("NT-56 B-135: a committed ACTIVATING row arms the 90 s check")
        void nt56_committedActivating_armsTheCheck() {
            var h = new NodeHarness();

            h.hold(LocalStep.ACTIVATE).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            var checks = h.timersOf(TimerId.TimerKind.ACTIVATING_CHECK);

            assertThat(checks).hasSize(1);
            assertThat(h.timers.get(checks.getFirst())).isEqualTo(h.wall + SliceState.ACTIVATING.timeout().map(TimeSpan::millis).or(-1L));
            assertThat(h.timers.get(checks.getFirst())).isEqualTo(h.wall + 90_000L);
        }

        /// Red when: an activation failure leaves the bridge, routes or config registered (no unpublish before the FAILED row).
        @Test
        @DisplayName("NT-58 B-141: an activation failure unregisters what the chain registered")
        void nt58_activationFailure_unregisters() {
            var h = new NodeHarness();

            h.script(LocalStep.REGISTER_CONFIG, LocalOutcome.failed(Causes.cause("bad config")))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            var unpublish = h.localOps().stream().filter(op -> op.step() == LocalStep.UNPUBLISH).findFirst().orElseThrow();
            var failed = h.submitsOf(SliceObservedState.FAILED).getFirst();
            var row = h.observed(ARTIFACT).or((SliceObservedValue) null);

            assertThat(unpublish.token()).as("fire and forget").isZero();
            assertThat(h.effects.indexOf(unpublish)).isLessThan(h.effects.indexOf(failed));
            assertThat(row.state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row.fatal()).as("an activation failure is RETRY: an untyped cause is not fatal").isFalse();
            assertThat(row.failureReason().or("")).contains("bad config");
            assertThat(h.record(ARTIFACT).serving()).isFalse();
        }

        /// Red when: a step that ends after the chain bound failed the chain writes anyway (an ACTIVE row after FAILED).
        @Test
        @DisplayName("NT-59 B-142: a step that ends after the chain failed writes nothing")
        void nt59_stepAfterTheTimeout_writesNothing() {
            var h = new NodeHarness();

            h.hold(LocalStep.ACTIVATE).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.advance(h.timings.chainBoundMs());

            var failedAt = h.observed(ARTIFACT).map(SliceObservedValue::version).or(0L);
            var held = h.pendingLocal.values().iterator().next().op();

            h.clearEffects();
            h.feed(new NodeFsmInput.LocalOpSettled(held.incarnation(), ARTIFACT, held.token(), LocalOutcome.done()));

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
            assertThat(h.localOps()).isEmpty();
            assertThat(stateOf(h)).isEqualTo(SliceObservedState.FAILED);
            assertThat(h.observed(ARTIFACT).map(SliceObservedValue::version).or(0L)).isEqualTo(failedAt);
        }

        /// Red when: a duplicate settlement of a step that already ended is taken for the settlement of the step now running, so
        /// the chain moves on before that step ended (B-142: a step is identified by its token, not by its position).
        @Test
        @DisplayName("NT-59 B-142: a duplicate settlement of an earlier step does not advance the chain")
        void nt59_duplicateSettlementOfAnEarlierStep_isIgnored() {
            var h = new NodeHarness();

            h.hold(LocalStep.ACTIVATE).hold(LocalStep.REGISTER_BRIDGE).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            var activate = h.pendingLocal.values().iterator().next().op();

            h.settle(LocalStep.ACTIVATE, LocalOutcome.done());

            assertThat(h.localSteps()).endsWith(LocalStep.REGISTER_BRIDGE);

            h.clearEffects();
            h.feed(new NodeFsmInput.LocalOpSettled(activate.incarnation(), ARTIFACT, activate.token(), LocalOutcome.done()));

            assertThat(h.localOps()).as("the running step has not ended: nothing follows it yet").isEmpty();
            assertThat(h.record(ARTIFACT).serving()).isFalse();
        }

        /// Red when: a route publication failure does not fail the chain (an ACTIVE row is written).
        @Test
        @DisplayName("NT-75 B-169: a route publication failure fails the chain")
        void nt75_routeFailure_failsTheChain() {
            var h = new NodeHarness();

            h.script(LocalStep.PUBLISH_ROUTES, LocalOutcome.failed(Causes.cause("route clash")))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            assertThat(h.submitsOf(SliceObservedState.ACTIVE)).isEmpty();
            assertThat(stateOf(h)).isEqualTo(SliceObservedState.FAILED);
        }

        /// Red when: a slice that is gone from the store when its legs are built (the step contributes nothing) fails the chain.
        @Test
        @DisplayName("NT-74 B-167: a leg step that finds the slice evicted contributes nothing and does not fail the chain")
        void nt74_evictedSlice_doesNotFailThePublication() {
            var h = new NodeHarness();

            h.script(LocalStep.BUILD_LEGS, LocalOutcome.done(List.of()))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            assertThat(stateOf(h)).isEqualTo(SliceObservedState.ACTIVE);
            assertThat(h.submitsOf(SliceObservedState.FAILED)).isEmpty();
        }

        /// Red when: the ACTIVE batch lacks the stream-ref ACQUIRE.
        @Test
        @DisplayName("NT-72 B-165: the ACQUIRE rides in the ACTIVE batch")
        void nt72_acquireRidesInTheActiveBatch() {
            var h = new NodeHarness();
            var acquire = leg(LegCommand.LegKind.STREAM_REF, ARTIFACT_B);

            h.script(LocalStep.DESCRIBE_ACTIVE, LocalOutcome.described(List.of("handle"), List.of(acquire)))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            assertThat(h.submitsOf(SliceObservedState.ACTIVE).getFirst().commands()).contains(acquire.command());
        }

        /// Red when: a slice with no methods writes endpoints (or an instance number) or fails; control: with methods the
        /// ACTIVE row carries them and the instance number.
        @Test
        @DisplayName("NT-70 B-153: a methods-less slice writes a plain ACTIVE")
        void nt70_methodsLessSlice_writesPlainActive() {
            var plain = active();
            var row = plain.observed(ARTIFACT).or((SliceObservedValue) null);

            assertThat(row.state()).isEqualTo(SliceObservedState.ACTIVE);
            assertThat(row.methods()).isEmpty();
            assertThat(row.instanceNumber()).isZero();

            var withMethods = new NodeHarness();

            withMethods.script(LocalStep.DESCRIBE_ACTIVE, LocalOutcome.described(List.of("handle", "list"), List.of()))
                       .establish()
                       .permit(ARTIFACT)
                       .goal(ARTIFACT, SliceGoal.ACTIVE, G1);

            var served = withMethods.observed(ARTIFACT).or((SliceObservedValue) null);

            assertThat(served.methods()).containsExactly("handle", "list");
            assertThat(served.instanceNumber()).isEqualTo(withMethods.config.instanceNumber());
        }

        /// Red when: a row's transitionedAt differs from the injected wall clock at the moment it was written.
        @Test
        @DisplayName("NT-69 B-152: transitionedAt is the injected clock")
        void nt69_transitionedAt_isTheInjectedClock() {
            var h = new NodeHarness();

            h.wall = 1_700_000_000_123L;
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(h.observed(ARTIFACT).map(SliceObservedValue::transitionedAt).or(0L)).isEqualTo(1_700_000_000_123L);

            h.wall = 1_700_000_777_000L;
            h.goal(ARTIFACT, SliceGoal.ACTIVE, G2);

            assertThat(h.observed(ARTIFACT).map(SliceObservedValue::transitionedAt).or(0L)).isEqualTo(1_700_000_777_000L);
        }
    }

    @Nested
    class Teardown {
        /// Red when: the removes and the RELEASE are not in the DEACTIVATING batch, or a failure to prepare them stops the chain.
        @Test
        @DisplayName("NT-60 B-143: the removes ride in the DEACTIVATING batch; a failed preparation does not stop the chain")
        void nt60_removesRideInTheDeactivatingBatch() {
            var h = active();
            var topic = leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT_B);
            var scheduled = leg(LegCommand.LegKind.SCHEDULED_TASK, ARTIFACT_B.base().withVersion(ARTIFACT.version()));
            var release = leg(LegCommand.LegKind.STREAM_REF, ARTIFACT_C);

            h.script(LocalStep.PREPARE_TEARDOWN, LocalOutcome.done(List.of(topic, scheduled, release)));
            h.clearEffects().goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(h.submitsOf(SliceObservedState.DEACTIVATING).getFirst().commands()).contains(topic.command(),
                                                                                                     scheduled.command(),
                                                                                                     release.command());
            assertThat(stateOf(h)).isEqualTo(SliceObservedState.LOADED);

            var broken = active();

            broken.script(LocalStep.PREPARE_TEARDOWN, LocalOutcome.failed(Causes.cause("release failed")));
            broken.clearEffects().goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(broken.submitsOf(SliceObservedState.DEACTIVATING).getFirst().commands()).as("only the observed Put").hasSize(1);
            assertThat(stateOf(broken)).as("the chain went on").isEqualTo(SliceObservedState.LOADED);
        }

        /// Red when: a deactivation failure writes fatal=true.
        @Test
        @DisplayName("NT-61 B-144: a deactivation failure is a retryable FAILED")
        void nt61_deactivationFailure_isNotFatal() {
            var h = active();

            h.script(LocalStep.DEACTIVATE, LocalOutcome.failed(Causes.cause("stop() threw")));
            h.goal(ARTIFACT, SliceGoal.LOADED, G2);

            var row = h.observed(ARTIFACT).or((SliceObservedValue) null);

            assertThat(row.state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row.fatal()).isFalse();
        }

        /// Red when: an unload failure does not end in an UNLOADED tombstone, or ends in FAILED.
        @Test
        @DisplayName("NT-64 B-147: an unload failure still ends in the UNLOADED tombstone")
        void nt64_unloadFailure_stillWritesTheTombstone() {
            var h = active();

            h.script(LocalStep.UNLOAD, LocalOutcome.failed(Causes.cause("classloader busy")));
            h.goal(ARTIFACT, SliceGoal.UNLOADED, G2);

            assertThat(stateOf(h)).isEqualTo(SliceObservedState.UNLOADED);
            assertThat(h.submitsOf(SliceObservedState.FAILED)).isEmpty();
        }

        /// Red when: the unload chain runs twice (a second UNLOAD step or a second UNLOADING write) on a re-delivered goal or on
        /// the node's own committed UNLOADING row.
        @Test
        @DisplayName("NT-57 B-140: the unload chain runs once")
        void nt57_unloadChain_runsOnce() {
            var h = active();

            h.hold(LocalStep.UNLOAD);
            h.clearEffects().goal(ARTIFACT, SliceGoal.UNLOADED, G2);
            h.goal(ARTIFACT, SliceGoal.UNLOADED, G2);
            h.deliverCommitted(ARTIFACT);
            h.settle(LocalStep.UNLOAD, LocalOutcome.done());

            assertThat(h.localSteps().stream().filter(step -> step == LocalStep.UNLOAD).count()).isEqualTo(1L);
            assertThat(h.submitsOf(SliceObservedState.UNLOADING)).hasSize(1);
            assertThat(stateOf(h)).isEqualTo(SliceObservedState.UNLOADED);
        }

        /// Red when: unload or forced cleanup does not unregister the bridge, routes and config (no UNPUBLISH before the UNLOAD
        /// step; no FORCED_CLEANUP when the goal goes away from a LOADED slice).
        @Test
        @DisplayName("NT-73 B-166: unload and forced cleanup unregister the config registration")
        void nt73_unloadAndForcedCleanup_unpublish() {
            var h = active();

            h.clearEffects().goal(ARTIFACT, SliceGoal.UNLOADED, G2);

            assertThat(h.localSteps()).containsSubsequence(LocalStep.PREPARE_TEARDOWN, LocalStep.UNPUBLISH, LocalStep.UNLOAD);

            var forced = new NodeHarness();

            forced.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            forced.clearEffects().removeGoal(ARTIFACT);

            assertThat(forced.localSteps()).containsExactly(LocalStep.FORCED_CLEANUP);
        }

        /// Red when: a desired removal while the slice is only LOADED leaves it in the store (no forced cleanup), or the node
        /// writes anything (the leader owns the row, R-1.6.4).
        @Test
        @DisplayName("NT-65 B-148: a desired removal while LOADED cleans the slice out of the store")
        void nt65_desiredRemovalWhileLoaded_cleansUp() {
            var h = new NodeHarness();

            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.clearEffects().removeGoal(ARTIFACT);

            assertThat(h.localSteps()).containsExactly(LocalStep.FORCED_CLEANUP);
            assertThat(h.slicesInStore).doesNotContain(ARTIFACT);
            assertThat(h.model.isLoaded(ARTIFACT)).isFalse();
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
        }

        /// Red when: DeploymentFailed says "Unknown failure" although the row carries a reason. Control: a row with no reason.
        @Test
        @DisplayName("NT-66 B-149: DeploymentFailed carries the real reason")
        void nt66_deploymentFailed_carriesTheRealReason() {
            var h = new NodeHarness();

            h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("disk full")))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.LOADED, G1);

            var reasons = failedReasons(h);

            assertThat(reasons).containsExactly("disk full");

            var bare = new NodeHarness();
            var noReason = new SliceObservedValue(SliceObservedState.FAILED, 1L, G1, bare.process, Option.none(), false, 0, List.of(), bare.wall);

            bare.establish().feed(new NodeFsmInput.ObservedPut(NodeHarness.SELF, ARTIFACT, noReason, bare.tokens.self()));

            assertThat(failedReasons(bare)).containsExactly("Unknown failure");
        }

        private List<String> failedReasons(NodeHarness h) {
            return h.effectsOf(NodeFsmEffect.Route.class)
                    .stream()
                    .map(NodeFsmEffect.Route::event)
                    .filter(NodeFsmEffect.RoutedEvent.DeploymentFailed.class::isInstance)
                    .map(NodeFsmEffect.RoutedEvent.DeploymentFailed.class::cast)
                    .map(NodeFsmEffect.RoutedEvent.DeploymentFailed::reason)
                    .toList();
        }
    }
}
