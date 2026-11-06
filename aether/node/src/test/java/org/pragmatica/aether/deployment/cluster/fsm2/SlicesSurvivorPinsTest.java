// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.PendingDeparture;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The departed-or-departing guard of `Slices.onObservedPut` (lane fsmf1). A row from such a node drives nothing: it must not even be
/// judged stale. Staleness is the probe because its metric is emitted only on the path past the guard.
class SlicesSurvivorPinsTest {
    private static final SlicePair PAIR = SlicePair.slicePair(CORE_2, ORDERS);

    private static ClusterModel modelWithStaleRow() {
        var m = new ClusterModel();

        m.admissions = m.admissions.put(CORE_2, admission(6L));

        return m;
    }

    private static boolean judgedStale(ClusterModel m) {
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Slices.onObservedPut(m, ctx, PAIR, row(SliceObservedState.ACTIVE, 1L, gen(1), process(5L)));

        return ctx.effects()
                  .stream()
                  .anyMatch(effect -> effect instanceof ClusterEffect.Metric metric && metric.name().equals("aether_deployment_fsm_stale_rows_total"));
    }

    /// Control: a stale row of a live node is judged stale, so the probe can see the path past the guard.
    @Test
    void onObservedPut_judgesAStaleRow_ofALiveNode() {
        assertThat(judgedStale(modelWithStaleRow())).isTrue();
    }

    /// Red when: the guard needs the node departed AND departing (`&&` instead of `||`): a departed node's row is acted on.
    @Test
    void onObservedPut_ignoresARow_ofADepartedNode() {
        var m = modelWithStaleRow();

        m.departed = m.departed.add(CORE_2);

        assertThat(judgedStale(m)).isFalse();
    }

    /// Red when: the same operator lets a departing node's row through.
    @Test
    void onObservedPut_ignoresARow_ofADepartingNode() {
        var m = modelWithStaleRow();

        m.departing = m.departing.put(CORE_2, new PendingDeparture(admission(6L), Option.none(), Option.none(), false, 0L, 0L));

        assertThat(judgedStale(m)).isFalse();
    }
}
