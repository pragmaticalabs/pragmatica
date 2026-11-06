// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.active;
import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [Divergence] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class DivergenceSurvivorPinsTest {
    private static final long T_LOAD_MS = 120_000L;

    private static long reissues(FsmHarness h) {
        return h.metricTotal("aether_deployment_fsm_divergence_total", t -> "reissue".equals(t.get("stage")));
    }

    /// Red when: the second re-issue fires at exactly 2 x T (`age > 2 * timeout` weakened to `>=`): it is due strictly after.
    @Test
    void divergence_secondReissue_isNotDueAtExactly2T_butOneMsLater() {
        var h = active();

        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        h.advance(T_LOAD_MS + 1);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).as("the first, just past T").isEqualTo(1);
        h.advance(T_LOAD_MS - 1);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).as("exactly 2T since the issue: not yet").isEqualTo(1);
        h.advance(1);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(reissues(h)).as("one ms later: the second").isEqualTo(2);
    }

    /// Red when: an unresponsive exclusion does not expire at exactly 10 x T of the wall term (`>=` weakened to `>`).
    @Test
    void unresponsiveExclusion_expiresAtExactlyTenT() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5, CORE_1);

        h.activate();
        h.seed(new SliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
        h.drain();
        h.advance(3 * T_LOAD_MS + 31_000L);
        var retractedAt = h.store.get(new ArtifactJudgmentKey(ORDERS))
                                 .map(value -> ((ArtifactJudgmentValue) value).unresponsive().get(CORE_1).at())
                                 .or(-1L);
        var due = retractedAt + 10 * T_LOAD_MS;

        assertThat(retractedAt).as("precondition: CORE_1 is excluded").isGreaterThan(0L);
        h.advance(due - 1L - h.wallMs);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.warns("slice-unresponsive-expired")).as("1 ms short of 10 x T").isEmpty();
        h.advance(1L);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.wallMs).as("precondition: exactly at 10 x T").isEqualTo(due);
        assertThat(h.warns("slice-unresponsive-expired")).as("exactly 10 x T: expired").hasSize(1);
    }
}
