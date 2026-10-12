// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT_B;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G3;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.PEER;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.PEER_TOKEN;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.leg;

/// The committed-target gate and the last-replica guards (spec N2, R-3.4.5): NT-71, NT-62, NT-63.
class NodeFsmGateTest {
    @Nested
    class Parking {
        /// Red when: a re-delivered identical goal resets the park's `since` (B-159 as it was); control: a newer goal restarts
        /// the clock. Nothing is written while parked.
        @Test
        @DisplayName("NT-71 B-159: a re-delivered identical goal keeps the park's since")
        void nt71_identicalRedelivery_keepsSince() {
            var h = new NodeHarness();

            h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);

            var first = h.wall;

            assertThat(h.record(ARTIFACT).parked().map(Parked::since).or(-1L)).isEqualTo(first);
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("a refused start writes nothing").isEmpty();

            h.advance(10_000L).goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(h.record(ARTIFACT).parked().map(Parked::since).or(-1L)).isEqualTo(first);
            assertThat(h.timersOf(TimerId.TimerKind.PARKED_ESCALATION)).as("armed once per park").hasSize(1);

            h.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(h.record(ARTIFACT).parked().map(Parked::since).or(-1L)).isEqualTo(first + 10_000L);
        }

        /// Red when: the escalation warning is raised before the bound, more than once, or its recovery is raised when the
        /// warning never was; or a parked start is not re-driven when the target arrives.
        @Test
        @DisplayName("NT-71 B-158: one escalation warning at the bound, its recovery when the target arrives")
        void nt71_escalation_isOnce_andTheRecoveryFollowsIt() {
            var h = new NodeHarness();

            h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(h.timings.parkedBoundMs() - 1L);

            assertThat(h.effectsOf(NodeFsmEffect.Warn.class)).isEmpty();

            var timer = h.timersOf(TimerId.TimerKind.PARKED_ESCALATION).stream().findFirst();

            h.advance(1L);
            h.advance(h.timings.parkedBoundMs());
            timer.ifPresent(h::fire);

            assertThat(h.effectsOf(NodeFsmEffect.Warn.class)).as("once, even if the timer is delivered again").hasSize(1);
            assertThat(h.effectsOf(NodeFsmEffect.Warn.class).getFirst().code()).isEqualTo(OperatorWarningCode.SLICE_START_AWAITING_TARGET.code());

            h.permit(ARTIFACT);

            assertThat(h.effectsOf(NodeFsmEffect.WarnResolved.class)).hasSize(1);
            assertThat(h.effectsOf(NodeFsmEffect.WarnResolved.class).getFirst().code()).isEqualTo(OperatorWarningCode.SLICE_START_DEFERRAL_ENDED.code());
            assertThat(h.state(ARTIFACT).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.LOADED);
            assertThat(h.record(ARTIFACT).parked().isEmpty()).isTrue();

            var quiet = new NodeHarness();

            quiet.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);
            quiet.permit(ARTIFACT);

            assertThat(quiet.effectsOf(NodeFsmEffect.WarnResolved.class)).as("no warning, no recovery").isEmpty();
        }

        /// Red when: an escalation timer that fires before the bound has elapsed on the node's clock raises the warning (the
        /// bound is checked against the wall clock, B-158), or one for a park that ended is acted on.
        @Test
        @DisplayName("NT-71 B-158: an escalation timer that fires early is ignored")
        void nt71_earlyEscalationTimer_isIgnored() {
            var h = new NodeHarness();

            h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);

            var parkedAt = h.wall;
            var timer = h.timersOf(TimerId.TimerKind.PARKED_ESCALATION).getFirst();

            h.wall = parkedAt + h.timings.parkedBoundMs() - 1L;
            h.fire(timer);

            assertThat(h.effectsOf(NodeFsmEffect.Warn.class)).isEmpty();
            assertThat(h.record(ARTIFACT).parked().map(Parked::warned).or(true)).isFalse();

            h.wall = parkedAt + h.timings.parkedBoundMs();
            h.fire(timer);

            assertThat(h.effectsOf(NodeFsmEffect.Warn.class)).hasSize(1);
        }

        /// Red when: a version a rolling update names is refused (the routing entry is ignored), or an absent target permits.
        @Test
        @DisplayName("NT-71 B-154: a routing entry that names the version permits it while a target exists")
        void gate_routingNamesTheVersion_permits() {
            var old = org.pragmatica.aether.artifact.Artifact.artifact("org.example:orders:0.9.0").unwrap();
            var h = new NodeHarness();

            h.establish().feed(new NodeFsmInput.RoutingPut(old.base(), old.version(), ARTIFACT.version()));
            h.goal(old, SliceGoal.LOADED, G1);

            assertThat(h.record(old).parked().isPresent()).as("a routing entry without a target is not a deploy").isTrue();

            h.permit(ARTIFACT);

            assertThat(h.state(old).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.LOADED);
            assertThat(h.model.permits(old)).isTrue();
            assertThat(h.model.permits(ARTIFACT)).isTrue();
        }
    }

    @Nested
    class LastReplica {
        private NodeHarness activeWith(LegCommand.LegKind kind) {
            var h = new NodeHarness();

            h.script(LocalStep.PREPARE_TEARDOWN,
                     LocalOutcome.done(List.of(leg(kind, ARTIFACT_B), leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT_B.base().withVersion(ARTIFACT.version())))));
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.clearEffects();

            return h;
        }

        private boolean carries(NodeHarness h, SliceObservedState state, LegCommand.LegKind kind) {
            return h.submitsOf(state)
                    .getFirst()
                    .commands()
                    .contains(leg(kind, ARTIFACT_B).command());
        }

        /// Red when: a scheduled-task remove is made with another live current replica hosting the artifact; controls: it is
        /// made with no replica, with a FAILED replica, and with a replica whose row is stale on positive evidence; an unknown
        /// token reads as current, so it is withheld.
        @Test
        @DisplayName("NT-62 B-145: the scheduled-task remove waits for the last live current replica")
        void nt62_scheduledTaskRemove_onlyByTheLastReplica() {
            var withPeer = activeWith(LegCommand.LegKind.SCHEDULED_TASK);

            withPeer.tokens.admit(PEER, PEER_TOKEN);
            withPeer.peerRow(PEER, ARTIFACT, SliceObservedState.ACTIVE, PEER_TOKEN, withPeer.tokens.tokenOf(PEER));
            withPeer.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(carries(withPeer, SliceObservedState.DEACTIVATING, LegCommand.LegKind.SCHEDULED_TASK)).isFalse();
            assertThat(withPeer.submitsOf(SliceObservedState.DEACTIVATING).getFirst().commands()).as("the node-scoped topic key still goes").hasSize(2);

            var alone = activeWith(LegCommand.LegKind.SCHEDULED_TASK);

            alone.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(carries(alone, SliceObservedState.DEACTIVATING, LegCommand.LegKind.SCHEDULED_TASK)).isTrue();

            var failedPeer = activeWith(LegCommand.LegKind.SCHEDULED_TASK);

            failedPeer.peerRow(PEER, ARTIFACT, SliceObservedState.FAILED, PEER_TOKEN, 0L);
            failedPeer.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(carries(failedPeer, SliceObservedState.DEACTIVATING, LegCommand.LegKind.SCHEDULED_TASK)).isTrue();

            var stalePeer = activeWith(LegCommand.LegKind.SCHEDULED_TASK);

            stalePeer.tokens.admit(PEER, 0xAAAL);
            stalePeer.peerRow(PEER, ARTIFACT, SliceObservedState.ACTIVE, PEER_TOKEN, stalePeer.tokens.tokenOf(PEER));
            stalePeer.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(carries(stalePeer, SliceObservedState.DEACTIVATING, LegCommand.LegKind.SCHEDULED_TASK)).isTrue();

            var unknownToken = activeWith(LegCommand.LegKind.SCHEDULED_TASK);

            unknownToken.peerRow(PEER, ARTIFACT, SliceObservedState.ACTIVE, PEER_TOKEN, unknownToken.tokens.tokenOf(PEER));
            unknownToken.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(unknownToken.tokens.tokenOf(PEER)).isZero();
            assertThat(carries(unknownToken, SliceObservedState.DEACTIVATING, LegCommand.LegKind.SCHEDULED_TASK)).isFalse();
        }

        /// Red when: a stream-registration remove is made on a deactivation or an unload while another live current replica
        /// hosts the artifact; control: the last replica removes it.
        @Test
        @DisplayName("NT-63 B-146: the stream-registration remove waits for the last live current replica")
        void nt63_streamRegistrationRemove_onlyByTheLastReplica() {
            var withPeer = activeWith(LegCommand.LegKind.STREAM_REGISTRATION);

            withPeer.peerRow(PEER, ARTIFACT, SliceObservedState.LOADED, PEER_TOKEN, 0L);
            withPeer.goal(ARTIFACT, SliceGoal.LOADED, G2);

            assertThat(carries(withPeer, SliceObservedState.DEACTIVATING, LegCommand.LegKind.STREAM_REGISTRATION)).isFalse();

            var unloading = activeWith(LegCommand.LegKind.STREAM_REGISTRATION);

            unloading.peerRow(PEER, ARTIFACT, SliceObservedState.ACTIVE, PEER_TOKEN, 0L);
            unloading.goal(ARTIFACT, SliceGoal.UNLOADED, G2);

            assertThat(carries(unloading, SliceObservedState.UNLOADING, LegCommand.LegKind.STREAM_REGISTRATION)).isFalse();

            var last = activeWith(LegCommand.LegKind.STREAM_REGISTRATION);

            last.goal(ARTIFACT, SliceGoal.UNLOADED, G2);

            assertThat(carries(last, SliceObservedState.UNLOADING, LegCommand.LegKind.STREAM_REGISTRATION)).isTrue();

            var peerGone = activeWith(LegCommand.LegKind.STREAM_REGISTRATION);

            peerGone.peerRow(PEER, ARTIFACT, SliceObservedState.ACTIVE, PEER_TOKEN, 0L);
            peerGone.feed(new NodeFsmInput.ObservedRemove(PEER, ARTIFACT));
            peerGone.goal(ARTIFACT, SliceGoal.UNLOADED, G2);

            assertThat(carries(peerGone, SliceObservedState.UNLOADING, LegCommand.LegKind.STREAM_REGISTRATION)).as("a removed replica row no longer guards").isTrue();
        }
    }
}
