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

/// #3203: a deposed leader can settle its own WARNINGS write after the successor committed a newer row of the same key. The late
/// settlement must not reach the operator: its opener would stay open after the successor closed the key, its recovery would close a
/// key the successor re-raised. The committed row's per-key `revision` orders the two writes, so the effect carries it.
class WarningLateSettleOrderingTest {
    private static final String OPENER = "retry-exhausted-unsettled";
    private static final FsmWarningKey KEY = FsmWarningKey.fsmWarningKey(OPENER, ORDERS.asString());

    private static FsmWarningValue row(boolean open, String message, long revision) {
        return FsmWarningValue.fsmWarningValue(Map.of(), open, message, revision, CORE_2, process(1L), 1L);
    }

    private static TxDraft draft(Option<AetherValue> expected, FsmWarningValue replacement) {
        return TxDraft.draft(WARNINGS, KEY, List.of(new KVCommand.Mutation<>(KEY, expected, Option.<AetherValue>some(replacement))));
    }

    /// Red when the deposed leader's opener (revision 1) is delivered although the mirror already holds the successor's close (revision 2).
    @Test
    void lateOpener_afterTheSuccessorClosedTheKey_isNotDelivered() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.onPut(m, ctx, KEY, row(false, "closed by the successor", 2L));
        Warnings.accepted(m, ctx, draft(Option.none(), row(true, "late opener", 1L)));

        assertThat(ctx.effects()).isEmpty();
    }

    /// Positive control: the same opener with no newer committed row is delivered, stamped with its revision.
    @Test
    void opener_withNoNewerCommittedRow_isDeliveredWithItsRevision() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.accepted(m, ctx, draft(Option.none(), row(true, "opener", 1L)));

        assertThat(ctx.effects()).containsExactly(new ClusterEffect.Warn(OPENER, ORDERS.asString(), "opener", OPENER, 1L));
    }

    /// Red when the deposed leader's late recovery (revision 2) is delivered after the successor re-raised the key (revision 3).
    @Test
    void lateRecovery_afterTheSuccessorReRaisedTheKey_isNotDelivered() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.onPut(m, ctx, KEY, row(true, "re-raised by the successor", 3L));
        Warnings.accepted(m, ctx, draft(Option.some(row(true, "opener", 1L)), row(false, "late recovery", 2L)));

        assertThat(ctx.effects()).isEmpty();
    }
}
