// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G3;

/// Timers that carry a token of an object that no longer exists, injected directly (the harness removes a cancelled timer
/// at once, so a real mailbox race never reaches these guards), the goal removed mid-chain (N17), and the once-per-entry
/// DeploymentCompleted (N10). Closes v-2172 checklist item 6.
class NodeFsmStaleInputTest {
    private static long completed(NodeHarness h) {
        return h.effectsOf(NodeFsmEffect.Route.class)
                .stream()
                .filter(route -> route.event() instanceof NodeFsmEffect.RoutedEvent.DeploymentCompleted)
                .count();
    }

    /// Red when: a WRITE_CONFIRM timer of an earlier attempt, delivered late, counts as a failed attempt of the write now in flight.
    @Test
    @DisplayName("an old write-confirm timer does not fail the current attempt")
    void oldWriteConfirmTimer_isIgnored() {
        var h = new NodeHarness();

        h.writeMode = NodeHarness.WriteMode.LOST;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);

        var first = h.record(ARTIFACT).write().orElse(org.pragmatica.lang.Option.none()).or((InFlight) null).correlation();

        h.advance(h.timings.writeAttemptMs());
        h.clearEffects();
        h.feed(new NodeFsmInput.TimerFired(TimerId.timerId(TimerId.TimerKind.WRITE_CONFIRM, ARTIFACT, h.model.incarnation(), first)));

        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("attempt 2 is in flight; the timer of attempt 1 adds nothing").isEmpty();
        assertThat(h.record(ARTIFACT).write().map(InFlight::attempt).or(0)).isEqualTo(2);
    }

    /// Red when: the chain bound of a chain that already failed fails the next chain of the key.
    @Test
    @DisplayName("an old chain-bound timer does not fail the next chain")
    void oldChainBoundTimer_isIgnored() {
        var h = new NodeHarness();

        h.hold(LocalStep.ACTIVATE).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        var oldBound = h.timersOf(TimerId.TimerKind.CHAIN_BOUND).getFirst();

        h.advance(h.timings.chainBoundMs());
        h.goal(ARTIFACT, SliceGoal.ACTIVE, G2);

        assertThat(h.record(ARTIFACT).chain().isPresent()).as("precondition: a new chain is running").isTrue();

        h.clearEffects();
        h.feed(new NodeFsmInput.TimerFired(oldBound));

        assertThat(h.submitsOf(SliceObservedState.FAILED)).isEmpty();
        assertThat(h.record(ARTIFACT).chain().isPresent()).isTrue();
    }

    /// Red when: the grace timer of a tombstone that was overwritten removes the newer tombstone before its own grace.
    @Test
    @DisplayName("an old tombstone-grace timer does not remove a newer tombstone")
    void oldTombstoneTimer_isIgnored() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1).goal(ARTIFACT, SliceGoal.UNLOADED, G2);

        var oldTimer = h.timersOf(TimerId.TimerKind.TOMBSTONE_GRACE).getFirst();
        var oldVersion = h.observed(ARTIFACT).map(SliceObservedValue::version).or(0L);

        h.goal(ARTIFACT, SliceGoal.LOADED, G3).goal(ARTIFACT, SliceGoal.UNLOADED, new org.pragmatica.aether.slice.kvstore.AetherValue.Generation(5L, 4L));

        assertThat(h.observed(ARTIFACT).map(SliceObservedValue::version).or(0L)).as("precondition: a newer tombstone").isGreaterThan(oldVersion);

        h.clearEffects();
        h.feed(new NodeFsmInput.TimerFired(oldTimer));

        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
        assertThat(h.observed(ARTIFACT).isPresent()).isTrue();
    }

    /// Red when: the ACTIVATING check armed for an earlier ACTIVATING row acts on a newer committed row.
    @Test
    @DisplayName("an old activating-check timer does not act on a newer row")
    void oldActivatingCheck_isIgnored() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        var activating = h.observedPuts().stream().filter(put -> put.state() == SliceObservedState.ACTIVATING).findFirst().orElseThrow();

        h.model = h.model.withInstance(ARTIFACT, h.model.instance(ARTIFACT).withObserved(org.pragmatica.lang.Option.some(activating)).withLastVersion(activating.version()));
        h.clearEffects();
        h.fireCheck(activating.version() - 1L);

        assertThat(h.effectsOf(NodeFsmEffect.Log.class)).as("an older check says nothing about this row").isEmpty();
        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
    }

    /// Red when: a goal removed during a load keeps its chain: the LOAD result is acted on and the chain goes on writing.
    @Test
    @DisplayName("N17 mid-chain: the chain does not outlive the goal")
    void goalRemovedMidChain_stopsTheChain() {
        var h = new NodeHarness();

        h.hold(LocalStep.LOAD).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        var load = h.pendingLocal.values().iterator().next().op();

        h.removeGoal(ARTIFACT);
        h.clearEffects();
        h.feed(new NodeFsmInput.LocalOpSettled(load.incarnation(), ARTIFACT, load.token(), LocalOutcome.done()));

        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
        assertThat(h.localOps()).isEmpty();
        assertThat(h.record(ARTIFACT).chain().isEmpty()).isTrue();
    }

    /// Red when: DeploymentCompleted is routed on every ACTIVE put (a reactivation rewrites ACTIVE over ACTIVE) or not at all.
    @Test
    @DisplayName("N10: DeploymentCompleted is routed once per entry into ACTIVE")
    void deploymentCompleted_onceOnEntry() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        assertThat(completed(h)).isEqualTo(1L);

        h.clearEffects().feed(new NodeFsmInput.QuorumDisappeared());
        h.establish();

        assertThat(h.submitsOf(SliceObservedState.ACTIVE)).as("precondition: the reactivation rewrote ACTIVE over ACTIVE").hasSize(1);
        assertThat(completed(h)).as("no new completion for ACTIVE over ACTIVE").isZero();

        h.goal(ARTIFACT, SliceGoal.LOADED, G2).goal(ARTIFACT, SliceGoal.ACTIVE, G3);

        assertThat(completed(h)).as("a new entry into ACTIVE completes again").isEqualTo(1L);
    }

    /// Red when: the entry cleanup for a goal removed during the outage leaves the key suspended (its reactivation then writes a
    /// FAILED row for a slice that was just cleaned up) or writes anything at all.
    @Test
    @DisplayName("N17 at entry: a suspended key whose goal went away is cleaned up and writes nothing")
    void goalRemovedWhileDormant_writesNothingAtEntry() {
        var h = new NodeHarness();

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
        h.feed(new NodeFsmInput.QuorumDisappeared());
        h.removeGoal(ARTIFACT);
        h.clearEffects().establish();

        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
        assertThat(h.record(ARTIFACT).suspended()).isFalse();
        assertThat(h.record(ARTIFACT).serving()).isFalse();
    }

    /// Red when: a satisfied plan marks the goal's generation applied. Only N0's same-process branch and a completed chain do
    /// (spec 3.4), so a row of generation 1 that meets a goal of generation 2 leaves the applied generation at 1.
    @Test
    @DisplayName("spec 3.4: a goal that is already met does not set appliedGeneration")
    void satisfiedPlan_doesNotSetAppliedGeneration() {
        var h = new NodeHarness();

        h.seedRow(ARTIFACT, SliceObservedState.LOADED, NodeHarness.SELF_TOKEN, 2L, G1);
        h.slicesInStore.add(ARTIFACT);
        h.permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G2).establish();

        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).isEmpty();
        assertThat(h.record(ARTIFACT).appliedGeneration().or(G3)).isEqualTo(G1);
    }

    /// Red when: R-6.0 step 5 rebuilds a write for a goal that is gone, or leaves the owed write behind.
    @Test
    @DisplayName("R-6.0 step 5: a write owed to a goal that went away is dropped, not rebuilt")
    void redrive_dropsAnOwedFailedWhenTheGoalIsGone() {
        var h = new NodeHarness();
        var row = new SliceObservedValue(SliceObservedState.LOADING, 2L, G1, h.process, org.pragmatica.lang.Option.none(), false, 0, java.util.List.of(), h.wall);
        var rec = InstanceRecord.instanceRecord()
                                .withObserved(org.pragmatica.lang.Option.some(row))
                                .withLastVersion(2L)
                                .withUnconfirmed(org.pragmatica.lang.Option.some(Unconfirmed.unconfirmed(SliceObservedState.FAILED,
                                                                                                         3L,
                                                                                                         org.pragmatica.lang.Option.some(FailureNote.failureNote("x", false, G1)))));
        var model = NodeFsmModel.nodeFsmModel().withActive(true, 1L).withInstance(ARTIFACT, rec);
        var step = Redrive.redrive(h.fsm, Transition.transition(model), ARTIFACT, 0);

        assertThat(step.effects().stream().filter(effect -> effect instanceof NodeFsmEffect.Submit)).isEmpty();
        assertThat(step.instance(ARTIFACT).unconfirmed().isEmpty()).isTrue();
    }
}
