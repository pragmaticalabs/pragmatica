// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.PendingJoin;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Defects an independent review of the cluster FSM found, each reproduced on the pure step before it was fixed.
class ReviewDefectsTest {
    private static final long TOKEN_2 = 0x2222L;

    private MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 1L, 0x1111L);
        w.admit(CORE_2, 1L, TOKEN_2);
        w.admit(CORE_3, 1L, 0x3333L);

        return w.target(ORDERS, 1, "CORE_ONLY")
                .directive(CORE_2,
                           ActivationDirectiveValue.core())
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          TOKEN_2)
                .routes(CORE_2, ORDERS);
    }

    /// Red when: a departure whose draft was dropped by a suspended flush is never read again, so n stays in `departing`
    /// forever (the backstop skips it and its record is never collected). Step 10 re-reads it after one consensus timeout.
    @Test
    void departureDroppedBySuspendedFlush_isResubmittedByTheReread() {
        var w = world().activate();

        w.h.deliver(new ClusterInput.WitnessStale());
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("suspended: nothing is submitted").isEmpty();
        assertThat(w.h.model().isDeparting(CORE_2)).isTrue();
        w.h.commitLeader(FsmHarness.SELF, 2L);
        w.h.drain();
        w.h.advance(65_000L);
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("resubmitted once the leader value names this node again")
                  .isNotEmpty();
        assertThat(w.h.model().isDeparting(CORE_2)).as("and the committed DEPARTED resolves it").isFalse();
    }

    /// Red when: a `Rebuild` delivered to an Active FSM leaves the old model's timers armed (two tick chains) or restarts the
    /// correlation sequence (a late settlement of an old submission matches a new one).
    @Test
    void rebuildWhileActive_cancelsTheOldTimers_andContinuesTheCorrelationSequence() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        h.activate();
        var before = h.model().txSeq;

        h.clearLog();
        h.deliver(new ClusterInput.Rebuild(h.snapshot()));
        assertThat(before).as("the first model submitted something").isGreaterThan(0L);
        assertThat(h.effects(ClusterEffect.Cancel.class)).extracting(cancel -> cancel.timer()
                                                                                     .kind())
                  .contains(TimerId.Kind.TICK, TimerId.Kind.POST_ACTIVATION);
        assertThat(h.model().txSeq).as("correlation ids are not reused").isGreaterThanOrEqualTo(before);
    }

    /// Red when: a join deferred by J9 outlives the node's departure, so `join-unmatched` is warned on every tick for a node
    /// that is no longer a member ("until a row matches or n departs").
    @Test
    void departure_endsTheNodesDeferredJoin() {
        var w = world().activate();
        var model = w.h.model();

        model.pendingJoins = model.pendingJoins.put(CORE_2, new PendingJoin(false, w.admissionOf(CORE_2, 1L)));
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.h.model().pendingJoins.containsKey(CORE_2)).isFalse();
    }

    private static long routedFailures(FsmHarness h) {
        return h.effects(ClusterEffect.Route.class)
                .stream()
                .filter(route -> route.event() instanceof RoutedEvent.DeploymentFailed)
                .count();
    }

    /// Red when: a failure whose transaction was lost and is handled again by the sweep routes `DeploymentFailed` a second time.
    @Test
    void failureHandledAgainAfterALostWrite_isAnnouncedOnce() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        new NodeSim(h, CORE_1, process(11L)).fails(ORDERS, true);
        h.drain();
        var lost = List.copyOf(h.held.values());

        lost.forEach(submit -> h.held.remove(submit.correlationId()));
        lost.forEach(submit -> h.settleHeld(submit, new ClusterInput.Settlement.Failed("lost")));
        assertThat(routedFailures(h)).as("nothing is announced while no transaction was accepted").isZero();
        h.advance(Timing.DEFAULT.retryBase());
        h.advance(30_000L);
        List.copyOf(h.held.values()).forEach(submit -> h.settleHeld(submit, h.applyTransaction(submit)));
        assertThat(h.effects(ClusterEffect.Route.class)
                    .stream()
                    .filter(route -> route.event() instanceof RoutedEvent.DeploymentFailed)
                    .count()).as("announced once, although the failure was handled twice")
                  .isEqualTo(1L);
    }

    /// Red when: a goal that rides an artifact's failure transaction is still written for a node that departs in the same drain,
    /// so one pair sits in both the failure transaction and the departure (R-5.7.4).
    @Test
    void departure_dropsTheNodesGoalsFromTheArtifactTransaction() {
        var w = world().activate();
        var failed = new SliceObservedValue(SliceObservedState.FAILED,
                                            9L,
                                            gen(1),
                                            process(TOKEN_2),
                                            Option.some("boom"),
                                            false,
                                            1,
                                            List.of(),
                                            w.h.wallMs);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.clearLog();
        w.h.deliverTogether(new ClusterInput.KvPut(new SliceObservedKey(CORE_2, ORDERS), failed),
                            new ClusterInput.NodeRemoved(CORE_2));
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).hasSize(1);
        assertThat(w.h.submits()
                      .stream()
                      .flatMap(submit -> submit.transaction()
                                               .mutations()
                                               .stream())
                      .filter(mutation -> mutation.key() instanceof SliceDesiredKey desired
                                          && desired.nodeId()
                                                    .equals(CORE_2)
                                          && mutation.replacement()
                                                     .isPresent())).as("no goal is written for the departing node (its desired key is removed by the cleanup, not rewritten)")
                  .isEmpty();
    }
}
