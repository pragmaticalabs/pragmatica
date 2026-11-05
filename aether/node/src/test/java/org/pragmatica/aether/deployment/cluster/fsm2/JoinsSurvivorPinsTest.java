// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [Joins] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class JoinsSurvivorPinsTest {
    private static final long TOKEN_2 = 0x2222L;

    /// Red when: a learned process token that turns no row stale still counts a stale-row delta of 0 (`now > before` weakened to `>=`),
    /// or still restarts the divergence clocks and queues a reconcile (`turned > 0` weakened to `>= 0`).
    @Test
    void onProcessKnown_whenNoRowTurnsStale_countsNothing_restartsNothing_queuesNothing() {
        var w = MbWorld.world(CORE_1, CORE_2);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.target(ORDERS, 1, "CORE_ONLY")
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
         .activate();

        var m = w.h.model();
        var pair = SlicePair.slicePair(CORE_2, ORDERS);
        var ctx = new Ctx(w.h.settings, w.h.wallMs, w.h.monoNs + 1_000_000L);
        var seenBefore = m.seenAt.get(pair);

        m.reconcileQueued = false;
        Joins.onProcessKnown(m, ctx, CORE_2, TOKEN_2);

        assertThat(m.observed.get(pair).isPresent()).as("precondition: the row is there").isTrue();
        assertThat(Rows.isStale(m, pair, m.observed.get(pair).unwrap())).as("precondition: and still current").isFalse();
        assertThat(ctx.effects().stream().filter(ClusterEffect.Metric.class::isInstance).map(ClusterEffect.Metric.class::cast)
                      .filter(metric -> metric.name().equals("aether_deployment_fsm_stale_rows_total"))).as("no stale-row metric").isEmpty();
        assertThat(ctx.effects().stream().filter(ClusterEffect.Enqueue.class::isInstance)).as("no reconcile queued").isEmpty();
        assertThat(m.seenAt.get(pair)).as("the clocks are not restarted").isEqualTo(seenBefore);
    }
}
