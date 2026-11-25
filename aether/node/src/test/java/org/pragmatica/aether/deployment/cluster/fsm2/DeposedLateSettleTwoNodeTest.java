// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #3203, the model's `late_fixed_view` trace on two nodes. Node A leads and writes the opener (index 1, revision 1); both nodes apply
/// it. A is deposed; the successor B closes the key (index 2, revision 2), settles and applies it. A's settlement of index 1 arrives
/// late, after B's close, while A has not applied index 2: A's own view holds revision 1, so no view-based gate on A can tell the
/// settlement is late. Under CTO ruling B the settlement publishes nothing, on any node; the operator stream comes from the committed
/// puts alone (`WarningLateSettleOrderingTest.committedPuts_publishInCommitOrder_endingAtTheCommittedRow`).
///
/// This file uses only API present at 34c4517a15, so it runs there too: red at `deposedSettle.effects()` (the late opener, revision 1,
/// published after the successor's recovery).
class DeposedLateSettleTwoNodeTest {
    private static final String OPENER = "retry-exhausted-unsettled";
    private static final FsmWarningKey KEY = FsmWarningKey.fsmWarningKey(OPENER, ORDERS.asString());
    private static final FsmWarningValue OPENED = row(true, "opened by A", 1L);
    private static final FsmWarningValue CLOSED = row(false, "closed by B", 2L);

    private static FsmWarningValue row(boolean open, String message, long revision) {
        return FsmWarningValue.fsmWarningValue(Map.of(), open, message, revision, CORE_2, process(1L), 1L);
    }

    private static TxDraft draft(Option<AetherValue> expected, FsmWarningValue replacement) {
        return TxDraft.draft(WARNINGS, KEY, List.of(new KVCommand.Mutation<>(KEY, expected, Option.<AetherValue>some(replacement))));
    }

    /// Red at 34c4517a15 at the `deposedSettle` assertion: the deposed node's late settle published the opener after the successor's close.
    @Test
    void deposedNode_lateSettleAfterTheSuccessorsClose_publishesNothing() {
        var nodeA = new ClusterModel();
        var nodeB = new ClusterModel();
        var applyA = SurvivorPinSupport.ctx(1_000L, 0L);
        var applyB = SurvivorPinSupport.ctx(1_000L, 0L);
        var successorSettle = SurvivorPinSupport.ctx(1_000L, 0L);
        var deposedSettle = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.onPut(nodeA, applyA, KEY, OPENED);
        Warnings.onPut(nodeB, applyB, KEY, OPENED);
        Warnings.accepted(nodeB, successorSettle, draft(Option.some(OPENED), CLOSED));
        Warnings.onPut(nodeB, applyB, KEY, CLOSED);
        Warnings.accepted(nodeA, deposedSettle, draft(Option.none(), OPENED));

        assertThat(nodeA.warningRows.get(KEY)).as("A has not applied index 2").isEqualTo(Option.some(OPENED));
        assertThat(nodeB.warningRows.get(KEY)).as("B holds the close").isEqualTo(Option.some(CLOSED));
        assertThat(deposedSettle.effects()).as("the deposed node's late settle publishes nothing").isEmpty();
    }
}
