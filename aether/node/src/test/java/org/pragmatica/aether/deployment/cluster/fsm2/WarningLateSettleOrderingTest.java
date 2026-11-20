// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
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
/// key the successor re-raised. CTO ruling B: no settlement notifies at all; the operator stream is derived from the committed puts
/// ([Warnings#committedNotice]), one notice per KV index, in commit order.
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

    /// Positive control (#3203 ruling B): the same opener with no newer committed row reaches the operator from its committed put, and the
    /// settlement itself notifies nothing. Red when the settlement notifies, or the committed put does not.
    @Test
    void opener_withNoNewerCommittedRow_isPublishedFromTheCommittedPut() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.accepted(m, ctx, draft(Option.none(), row(true, "opener", 1L)));

        assertThat(ctx.effects()).isEmpty();
        assertThat(Warnings.committedNotice(KEY, Option.none(), row(true, "opener", 1L)))
                  .isEqualTo(Option.some(Warnings.Notice.notice(OPENER, ORDERS.asString(), "opener")));
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

    /// #3203 A3: red at 34c4517a15, where `landed` indexed the late replacement unfiltered and the mirror fell back to revision 1
    /// (open) while the store holds revision 2 (closed). The mirror takes a row only above the revision it holds.
    @Test
    void lateSettle_afterTheSuccessorClosedTheKey_leavesTheMirrorAtTheNewerRevision() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.onPut(m, ctx, KEY, row(false, "closed by the successor", 2L));
        Warnings.accepted(m, ctx, draft(Option.none(), row(true, "late opener", 1L)));

        assertThat(m.warningRows.get(KEY)).isEqualTo(Option.some(row(false, "closed by the successor", 2L)));
    }

    /// The mirror's revision never decreases, whichever path brings the older row: a put, a late accepted write, or a newer then
    /// older put. Red at 34c4517a15 at the first older row.
    @Test
    void indexRow_olderRevisionAfterNewer_neverLowersTheMirrorRevision() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);
        var seen = new ArrayList<Long>();

        Warnings.onPut(m, ctx, KEY, row(true, "opener", 1L));
        seen.add(revisionOf(m));
        Warnings.onPut(m, ctx, KEY, row(true, "re-raised", 3L));
        seen.add(revisionOf(m));
        Warnings.onPut(m, ctx, KEY, row(false, "older close", 2L));
        seen.add(revisionOf(m));
        Warnings.accepted(m, ctx, draft(Option.some(row(true, "opener", 1L)), row(false, "late recovery", 2L)));
        seen.add(revisionOf(m));
        Warnings.indexRow(m, KEY, row(true, "older opener", 1L));
        seen.add(revisionOf(m));

        assertThat(seen).containsExactly(1L, 3L, 3L, 3L, 3L);
        assertThat(m.warningRows.get(KEY).map(FsmWarningValue::open)).isEqualTo(Option.some(true));
    }

    /// Positive control for the gate's direction: a newer row is taken.
    @Test
    void indexRow_newerRevision_isTaken() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.onPut(m, ctx, KEY, row(true, "opener", 1L));
        Warnings.onPut(m, ctx, KEY, row(false, "closed", 2L));

        assertThat(m.warningRows.get(KEY)).isEqualTo(Option.some(row(false, "closed", 2L)));
    }

    /// #3203 ruling B, the `late_fixed_view` trace's operator stream: one notice per committed put of the key, in commit order (index 1
    /// A's opener, index 2 B's close), and the last one agrees with the committed row (closed). The settlements add nothing
    /// (`DeposedLateSettleTwoNodeTest`). Red when a committed put's notice is missing or the stream ends open.
    @Test
    void committedPuts_publishInCommitOrder_endingAtTheCommittedRow() {
        var opened = row(true, "opened by A", 1L);
        var closed = row(false, "closed by B", 2L);
        var stream = new ArrayList<Warnings.Notice>();

        Warnings.committedNotice(KEY, Option.none(), opened).onPresent(stream::add);
        Warnings.committedNotice(KEY, Option.some(opened), closed).onPresent(stream::add);

        assertThat(stream).containsExactly(Warnings.Notice.notice(OPENER, ORDERS.asString(), "opened by A"),
                                           Warnings.Notice.notice(Warnings.FAMILIES.get(OPENER), ORDERS.asString(), "closed by B"));
    }

    /// The gate is strict: a row at the revision the mirror holds is not newer, so it is not taken. Two distinct committed rows of one key
    /// cannot share a revision (`Warnings.put` writes the mirrored revision plus one with the mirrored row as the expected value, and
    /// `KVStore.validMutation` commits a mutation only when its expected value is the stored one), so this input is not reachable in
    /// production: the test pins the comparison itself. Red when `newer` compares with `>=` (the second row replaces the first).
    @Test
    void indexRow_distinctRowAtTheHeldRevision_isNotTaken() {
        var m = new ClusterModel();

        Warnings.indexRow(m, KEY, row(true, "held", 2L));
        Warnings.indexRow(m, KEY, row(false, "another row at the held revision", 2L));

        assertThat(m.warningRows.get(KEY)).isEqualTo(Option.some(row(true, "held", 2L)));
    }

    private static long revisionOf(ClusterModel m) {
        return m.warningRows.get(KEY)
                            .map(FsmWarningValue::revision)
                            .or(0L);
    }
}
