// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.leader;
import static org.assertj.core.api.Assertions.assertThat;


/// Events that describe a decision leave only when the decision is durable (v-2174 F4, extended to every routed event): a suspended,
/// deposed or refused leader announces nothing. `ReconciliationAdjustment` rides the goals it describes; `ActivationBlocked` reports a
/// committed record and is announced by an acting leader only.
class AnnouncementTest {
    private static long adjustments(FsmHarness h) {
        return h.effects(ClusterEffect.Route.class)
                .stream()
                .filter(route -> route.event() instanceof RoutedEvent.ReconciliationAdjustment)
                .count();
    }

    private static long blocked(FsmHarness h) {
        return h.effects(ClusterEffect.Route.class)
                .stream()
                .filter(route -> route.event() instanceof RoutedEvent.ActivationBlocked)
                .count();
    }

    private static FsmHarness scaleUp() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1, CORE_2, CORE_3);

        SlFixtures.seedTarget(h, ORDERS, 2, 1);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();

        return h;
    }

    /// Red when: the adjustment is announced when it is decided (zero goals accepted yet), or once per goal, or not at all.
    @Test
    void reconciliationAdjustment_isAnnouncedOnce_whenItsGoalsAreAccepted() {
        var h = scaleUp();

        assertThat(h.submits(SubmitKind.GOALS)).as("goals are in flight").isNotEmpty();
        assertThat(adjustments(h)).as("nothing is announced while the goals are in flight").isZero();
        List.copyOf(h.held.values()).forEach(submit -> h.settleHeld(submit, h.applyTransaction(submit)));
        assertThat(adjustments(h)).as("once the goals are accepted: one announcement for the operation").isEqualTo(1L);
    }

    /// Red when: a refused goal transaction announces the adjustment it did not make.
    @Test
    void reconciliationAdjustment_isNotAnnounced_byARefusedTransaction() {
        var h = scaleUp();
        var first = h.submits(SubmitKind.GOALS).getFirst();

        h.held.clear();
        h.settleHeld(first, h.refused(first));
        assertThat(adjustments(h)).isZero();
    }

    /// Red when: a deposed leader announces the adjustment of a transaction another leader refused it.
    @Test
    void reconciliationAdjustment_isNotAnnounced_byADeposedLeader() {
        var h = scaleUp();
        var first = h.submits(SubmitKind.GOALS).getFirst();
        var other = new LeaderValue(NodeId.nodeId("someone-else").unwrap(),
                                    9L);

        h.held.clear();
        h.settleHeld(first,
                     new ClusterInput.Settlement.Refused(Option.some(other), Map.of()));
        assertThat(adjustments(h)).isZero();
    }

    /// Red when: a suspended leader announces an adjustment (it submits nothing, so it decided nothing); after the resume the operation
    /// is announced once, when its goals land.
    @Test
    void reconciliationAdjustment_isNotAnnounced_whileSuspended() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1, CORE_2, CORE_3);

        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 2, 1));
        h.drain();
        assertThat(adjustments(h)).isZero();
        assertThat(h.submits(SubmitKind.GOALS)).isEmpty();
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.advance(30_000L);
        assertThat(adjustments(h)).as("announced once after the resume, with the accepted goals").isEqualTo(1L);
    }

    private static SchemaVersionValue failedSchema() {
        return new SchemaVersionValue("ds",
                                      1,
                                      "m1",
                                      SchemaStatus.FAILED,
                                      "g:a:1",
                                      blueprintId("org.example:app:1.0.0"),
                                      2,
                                      FsmHarness.START_WALL);
    }

    /// Red when: an acting leader does not announce a committed FAILED schema record (slices held), or announces it twice.
    @Test
    void activationBlocked_isAnnouncedOnce_byAnActingLeader() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        h.activate();
        h.seed(new SchemaVersionKey("ds"), failedSchema());
        h.drain();
        assertThat(blocked(h)).isEqualTo(1L);
    }

    /// Red when: a suspended leader announces a committed FAILED schema record: it indexes the record and announces nothing.
    @Test
    void activationBlocked_isNotAnnounced_whileSuspended() {
        var h = leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        h.activate();
        h.deliver(new ClusterInput.WitnessStale());
        h.seed(new SchemaVersionKey("ds"), failedSchema());
        h.drain();
        assertThat(h.model().schemas.containsKey("ds")).as("the record is indexed").isTrue();
        assertThat(blocked(h)).isZero();
    }
}
