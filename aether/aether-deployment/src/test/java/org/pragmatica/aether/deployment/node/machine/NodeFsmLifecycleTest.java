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
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;

/// Spec 3.4 outer states and the Rebuild (R-3.4.1, R-3.4.7): NT-44, NT-46, NT-47, NT-48 (the FSM half).
class NodeFsmLifecycleTest {
    private static NodeHarness activeWithServingSlice() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        return h;
    }

    @Nested
    class Entry {
        /// Red when: a goal committed while Dormant is not acted on at entry (no LOADING Put after the quorum), or the
        /// self-ready effect does not come before the first slice recovery effect (L-07).
        @Test
        @DisplayName("NT-44 B-103/B-134: goals committed while Dormant are acted on at entry, after self-ready")
        void nt44_goalsCommittedWhileDormant_areActedOnAtEntry() {
            var h = new NodeHarness();

            h.goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.targets.put(ARTIFACT.base(), ARTIFACT.version());

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("Dormant drops the put: nothing is submitted").isEmpty();
            assertThat(h.model.active()).isFalse();

            h.clearEffects();
            h.establish();

            var entry = h.effects.stream().filter(effect -> !(effect instanceof NodeFsmEffect.Metric)).toList();

            assertThat(entry.getFirst()).as("self-ready is the first effect of the entry").isEqualTo(NodeFsmEffect.selfReady());
            assertThat(entry.stream().filter(effect -> effect instanceof NodeFsmEffect.Submit).findFirst())
                .as("the entry reconcile writes, after self-ready")
                .isPresent();
            assertThat(h.writtenStates()).startsWith(SliceObservedState.LOADING);
            assertThat(h.observed(ARTIFACT).map(row -> row.state()).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.LOADED);
        }

        /// Red when: Dormant handles a key put (a Submit or a model change appears) instead of dropping it (B-101).
        @Test
        @DisplayName("NT-44 B-101: Dormant drops everything but the quorum")
        void nt44_dormant_dropsKeyPutsAndTimers() {
            var h = new NodeHarness();
            var before = h.model;

            h.feed(new NodeFsmInput.DesiredPut(ARTIFACT, new org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue(SliceGoal.LOADED, G1, 1L)));
            h.feed(new NodeFsmInput.ConfigChanged("a.b"));
            h.feed(new NodeFsmInput.QuorumDisappeared());

            assertThat(h.model).isEqualTo(before);
            assertThat(h.effects).allMatch(effect -> effect instanceof NodeFsmEffect.Metric);
        }
    }

    @Nested
    class QuorumCycle {
        /// Red when: the in-flight key is not reconciled at entry (the resumed LOAD is not issued again, under incarnation 2),
        /// or a local step that ends under incarnation 1 is acted on although its token is the live one.
        @Test
        @DisplayName("NT-46 B-108/B-170: the in-flight key is reconciled at entry and an incarnation-1 step result is dropped")
        void nt46_quorumCycle_reconcilesTheInFlightKey_andDropsOldIncarnationSteps() {
            var h = new NodeHarness();

            h.hold(LocalStep.LOAD);
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);

            var oldIncarnation = h.model.incarnation();

            assertThat(h.observed(ARTIFACT).map(row -> row.state()).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.LOADING);

            h.feed(new NodeFsmInput.QuorumDisappeared());
            h.clearEffects();
            h.pendingLocal.clear();
            h.establish();

            assertThat(h.model.incarnation()).isEqualTo(oldIncarnation + 1L);

            var reissued = h.localOps().stream().filter(op -> op.step() == LocalStep.LOAD).findFirst().orElseThrow();

            assertThat(reissued.incarnation()).as("the in-flight key is reconciled at entry: its LOAD is issued again").isEqualTo(oldIncarnation + 1L);

            h.clearEffects();
            h.feed(new NodeFsmInput.LocalOpSettled(oldIncarnation, ARTIFACT, reissued.token(), LocalOutcome.done()));

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("an incarnation-1 result carrying the live token writes nothing").isEmpty();
            assertThat(h.localOps()).isEmpty();

            h.feed(new NodeFsmInput.LocalOpSettled(reissued.incarnation(), ARTIFACT, reissued.token(), LocalOutcome.done()));

            assertThat(h.submitsOf(SliceObservedState.LOADED)).as("control: the same result under the live incarnation is acted on").hasSize(1);
        }

        /// Red when: a write-confirm timer or a write settlement of incarnation 1 acts after a quorum cycle, although it carries
        /// the correlation of the write now in flight (a retry or a re-drive would follow).
        @Test
        @DisplayName("NT-46 B-108/B-170: an incarnation-1 timer and settlement carrying the live correlation write nothing")
        void nt46_quorumCycle_dropsOldIncarnationTimersAndSettlements() {
            var h = new NodeHarness();

            h.writeMode = NodeHarness.WriteMode.LOST;
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);

            var oldIncarnation = h.model.incarnation();

            h.feed(new NodeFsmInput.QuorumDisappeared());
            h.establish();

            var live = h.record(ARTIFACT).write().orElse(Option.none()).or((InFlight) null);

            assertThat(live).as("the entry reconcile has a write in flight").isNotNull();

            h.clearEffects();
            h.feed(new NodeFsmInput.TimerFired(TimerId.timerId(TimerId.TimerKind.WRITE_CONFIRM, ARTIFACT, oldIncarnation, live.correlation())));
            h.feed(new NodeFsmInput.WriteSettled(oldIncarnation,
                                                 ARTIFACT,
                                                 live.correlation(),
                                                 NodeFsmInput.WriteOutcome.failed(org.pragmatica.lang.utils.Causes.cause("old"))));

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("nothing of incarnation 1 retries the write").isEmpty();

            h.feed(new NodeFsmInput.TimerFired(TimerId.timerId(TimerId.TimerKind.WRITE_CONFIRM, ARTIFACT, h.model.incarnation(), live.correlation())));

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("control: the live timer retries").hasSize(1);
        }
    }

    @Nested
    class Deferrals {
        /// Red when: a start parked before a quorum loss is lost (or restarted with a new since) by the cycle, or is not
        /// re-driven when its target arrives.
        @Test
        @DisplayName("B-157 R-3.4.1: a parked start survives a quorum cycle with its since and starts when the target arrives")
        void parkedStart_survivesAQuorumCycle() {
            var h = new NodeHarness();

            h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);

            var since = h.record(ARTIFACT).parked().map(Parked::since).or(-1L);

            h.advance(5_000L).feed(new NodeFsmInput.QuorumDisappeared());
            h.establish();

            assertThat(h.record(ARTIFACT).parked().map(Parked::since).or(-2L)).isEqualTo(since);
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();

            h.permit(ARTIFACT);

            assertThat(h.observed(ARTIFACT).map(row -> row.state()).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.LOADED);
        }

        /// Red when: a deferral whose goal was removed or changed during the outage is kept, so a later target would start a
        /// load the leader no longer asks for (step 2 of the Rebuild).
        @Test
        @DisplayName("B-157 R-3.4.1 step 2: a stale deferral is dropped on entry")
        void staleDeferral_isDroppedOnEntry() {
            var h = new NodeHarness();

            h.establish().goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.feed(new NodeFsmInput.QuorumDisappeared());
            h.desired.remove(ARTIFACT);
            h.establish();

            assertThat(h.record(ARTIFACT).parked().isEmpty()).isTrue();

            h.permit(ARTIFACT);

            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("the removed goal is not resurrected by a later target").isEmpty();
        }
    }

    @Nested
    class Config {
        /// Red when: Active does not pass a changed config key to the registered slices, or Dormant does.
        @Test
        @DisplayName("B-116 NI-5: a config change is pushed in Active and ignored in Dormant")
        void configChange_isPushedOnlyInActive() {
            var h = new NodeHarness();

            h.feed(new NodeFsmInput.ConfigChanged("slice.timeout"));

            assertThat(h.localOps()).isEmpty();

            h.establish().clearEffects().feed(new NodeFsmInput.ConfigChanged("slice.timeout"));

            assertThat(h.localOps()).singleElement().satisfies(op -> {
                assertThat(op.step()).isEqualTo(LocalStep.NOTIFY_CONFIG);
                assertThat(op.argument().or("")).isEqualTo("slice.timeout");
            });
        }
    }

    @Nested
    class Reactivation {
        /// Red when: after a quorum cycle the bridge, legs, config or routes are not registered again, an ACQUIRE is submitted
        /// with the ACTIVE batch, N0 treats the same process's row as stale (a LOADING or UNLOADED overwrite, a stale metric),
        /// or a KV write is made while the quorum is gone.
        @Test
        @DisplayName("NT-47 B-109/B-116: after a quorum cycle the instance re-registers and writes ACTIVE with no ACQUIRE")
        void nt47_quorumCycle_reactivatesWithoutAcquireAndKeepsTheRowCurrent() {
            var h = activeWithServingSlice();
            var acquire = LegCommand.legCommand(LegCommand.LegKind.STREAM_REF,
                                                new KVCommand.Remove<AetherKey>(AetherKey.NodeRoutesKey.nodeRoutesKey(NodeHarness.SELF, ARTIFACT.base().withVersion(ARTIFACT.version()))));

            assertThat(h.observed(ARTIFACT).map(row -> row.state()).or(SliceObservedState.UNKNOWN)).isEqualTo(SliceObservedState.ACTIVE);

            h.clearEffects();
            h.feed(new NodeFsmInput.QuorumDisappeared());

            assertThat(h.localSteps()).as("serving instances are unpublished").containsExactly(LocalStep.SUSPEND);
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("no KV write while the quorum is gone").isEmpty();

            h.clearEffects();
            h.script(LocalStep.DESCRIBE_ACTIVE, LocalOutcome.described(List.of("handle"), List.of(acquire)));
            h.establish();

            assertThat(h.localSteps()).containsExactly(LocalStep.REGISTER_BRIDGE,
                                                       LocalStep.BUILD_LEGS,
                                                       LocalStep.REGISTER_CONFIG,
                                                       LocalStep.PUBLISH_ROUTES,
                                                       LocalStep.DESCRIBE_ACTIVE);

            var submits = h.effectsOf(NodeFsmEffect.Submit.class);

            assertThat(h.writtenStates()).as("the row is current: only the final ACTIVE write").containsExactly(SliceObservedState.ACTIVE);
            assertThat(submits).hasSize(1);
            assertThat(submits.getFirst().commands()).as("no ACQUIRE on a reactivation").hasSize(1);
            assertThat(h.effects).noneMatch(effect -> effect instanceof NodeFsmEffect.Metric metric
                                                    && metric.name().contains("stale_rows"));
            assertThat(h.observed(ARTIFACT).map(row -> row.version()).or(0L)).isEqualTo(5L);
        }
    }

    @Nested
    class Reconcile {
        /// Red when: in Dormant the level-heal does not act as the quorum (no self-ready), or in Active it signals self-ready a
        /// second time or does anything else. The 5 s driver that delivers it is wiring (phase 3).
        @Test
        @DisplayName("NT-48 B-114 (FSM half): reconcileActivation activates a Dormant node once and is a no-op in Active")
        void nt48_reconcileActivation_activatesDormantOnly() {
            var h = new NodeHarness();

            h.feed(new NodeFsmInput.ReconcileActivation(h.snapshot()));

            assertThat(h.effectsOf(NodeFsmEffect.SelfReady.class)).hasSize(1);
            assertThat(h.model.active()).isTrue();

            h.clearEffects();
            h.feed(new NodeFsmInput.ReconcileActivation(h.snapshot()));

            assertThat(h.effectsOf(NodeFsmEffect.SelfReady.class)).as("self-ready is not re-fired").isEmpty();
            assertThat(h.effects).allMatch(effect -> effect instanceof NodeFsmEffect.Metric);
        }
    }
}
