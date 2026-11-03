// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;

import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Guards of `Rows` that the mutation battery left unpinned (lane fsmf1). Each test names the line whose operator it holds.
/// The three dead helpers of the same battery (`isEarlierInChain`, `isTransitional`, `isDeparted(DepartureState)`) have no reference
/// under `aether/` and so no behaviour to pin.
class RowsSurvivorPinsTest {
    /// Red when: an observation counts only if the row AND the goal are absent (`&&` instead of `||`): a row on a counted core that has
    /// no goal key at all (a stray, R-3.3.11) stops being counted.
    @Test
    void countedRows_countsAStrayRow_thatHasNoGoal() {
        var m = new ClusterModel();
        var pair = SlicePair.slicePair(CORE_1, ORDERS);

        m.seams = Seams.UNWIRED.withCountedCores(Set.of(CORE_1));
        m.observed = m.observed.put(pair, row(SliceObservedState.ACTIVE, 1L, gen(1), process(1L)));
        m.observedByArtifact = Idx.add(m.observedByArtifact, ORDERS, CORE_1);

        assertThat(Rows.countsObservation(m, pair)).isTrue();
        assertThat(Rows.countedRows(m, ORDERS)).hasSize(1);
        assertThat(Rows.activeCount(m, ORDERS)).isEqualTo(1);
    }

    /// Red when: `isCommunityWorker` accepts a WORKER with no community, or a non-WORKER that names one (`||` instead of `&&`).
    @Test
    void isCommunityWorker_needsTheWorkerRole_andANamedCommunity() {
        assertThat(Rows.isCommunityWorker(ActivationDirectiveValue.worker("c", ""))).isTrue();
        assertThat(Rows.isCommunityWorker(ActivationDirectiveValue.worker())).as("WORKER without a community").isFalse();
        assertThat(Rows.isCommunityWorker(new ActivationDirectiveValue(ActivationDirectiveValue.CORE, "c", ""))).as("a community on a CORE")
                  .isFalse();
        assertThat(Rows.isCommunityWorker(ActivationDirectiveValue.core())).isFalse();
    }

    /// Red when: a FAILED row under a goal other than UNLOADED stops being settled (`return false`): the failure handling (C10c) owns it, and
    /// divergence would flag it as well.
    @Test
    void isSettled_isTrue_forAFailedRowUnderALoadingGoal() {
        var failed = Option.some(failedRow(1L, gen(1), process(1L), false));

        assertThat(Rows.isSettled(desired(SliceGoal.LOADED, gen(1)), failed)).isTrue();
        assertThat(Rows.isSettled(desired(SliceGoal.UNLOADED, gen(1)), failed)).as("control: under UNLOADED it is not").isFalse();
    }
}
