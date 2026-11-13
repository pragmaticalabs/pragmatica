// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #3085: a leader-local family warning reaches the operator-warning sink only once the WARNINGS row that carries it has committed (publish
/// after commit). The window it closes: a warning raised in the drain whose leader is deposed before its WARNINGS write commits was published
/// but never persisted, so the successor did not inherit it, and an event-only family (retry exhaustion) never raised or closed it again.
class WarnAfterCommitTest {
    private static final String OPENER = "retry-exhausted-unsettled";
    private static final String RECOVERY = "retry-exhausted-settled";
    private static final FsmWarningKey KEY = FsmWarningKey.fsmWarningKey(OPENER, ORDERS.asString());

    /// ORDERS on core-1 one failure short of exhaustion, the leader active, every submission held from here on.
    private static FsmHarness oneFailureShort() {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        SlFixtures.seedJudgment(h, ORDERS, new ArtifactJudgmentValue(false, Map.of(CORE_1, 5), Map.of(), Set.of()));
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;

        return h;
    }

    private static FsmHarness exhausted() {
        var h = oneFailureShort();

        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the leader decided the exhaustion").isTrue();
        assertThat(h.warningPuts()).as("precondition: the last WARNINGS write carries it").endsWith(KEY);

        return h;
    }

    private static boolean committed(FsmHarness h) {
        return h.snapshot()
                .entries()
                .stream()
                .anyMatch(entry -> entry.key()
                                        .equals(KEY));
    }

    private static ClusterEffect.Submit warningsWrite(FsmHarness h) {
        return h.submits(WARNINGS).getLast();
    }

    /// The deposed leader's WARNINGS write is fenced (positive control: it is refused), the successor rebuilds from the store and runs its
    /// passes. Red when: an opener published by the deposed leader is neither in the committed open set nor closed by a recovery, so it stays
    /// open in the operator's view for ever (the event-only family is never raised or closed again).
    @Test
    void warningRaisedInTheDeposedDrain_isNotLeftOpen() {
        var h = exhausted();

        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(CORE_2, 2L);
        var fenced = (KVCommand.TransactionResult) h.applyCommand(warningsWrite(h).transaction());

        assertThat(fenced.accepted()).as("precondition: the deposed leader's WARNINGS write is refused by the fence").isFalse();

        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.drain();
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());

        var published = h.warns(OPENER).size();
        var accountedFor = (committed(h) ? 1 : 0) + h.warns(RECOVERY).size();

        assertThat(published).as("a published %s is neither committed nor closed: open for ever", OPENER)
                             .isLessThanOrEqualTo(accountedFor);
    }
}
