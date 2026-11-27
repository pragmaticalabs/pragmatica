// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #3828 lane 3a (design 3.3, 4.4): the cluster FSM mirrors the directive receipt like every other key family. The mirror is a cache of the
/// KV that the committed notifications and a refusal fill; its handlers decide nothing, they request a reconcile. Nothing writes a receipt
/// in this lane, so every receipt here is a fixture the harness commits as the leader would.
class ReceiptMirrorTest {
    private static final DirectiveReceiptKey KEY = DirectiveReceiptKey.directiveReceiptKey(CORE_2);
    private static final DirectiveReceiptValue VALUE = DirectiveReceiptValue.directiveReceiptValue("CORE");

    private static MbWorld world() {
        return MbWorld.world(CORE_1, CORE_2, CORE_3);
    }

    private static long reconcilesQueued(FsmHarness h) {
        return h.effects(ClusterEffect.Enqueue.class)
                .stream()
                .filter(enqueue -> enqueue.input() instanceof ClusterInput.Reconcile)
                .count();
    }

    /// Red when: `Rebuild` leaves the receipts of the snapshot out of `m.receipts` (`Indexer.indexPut` has no receipt case).
    @Test
    void aReceiptInTheSnapshot_isInTheMirrorAfterRebuild() {
        var w = world();

        w.h.seedQuietly(KEY, VALUE);
        w.activate();

        assertThat(w.h.model().receipts.get(CORE_2)).isEqualTo(Option.some(VALUE));
        assertThat(w.h.model().receipts.get(CORE_3)).as("only the receipt of the snapshot").isEqualTo(Option.none());
    }

    /// Red when: a committed Put or Remove of a receipt does not move the mirror, or does not request a reconcile (the handlers fall to
    /// `Judgments`, which indexes and requests only for a judgment), or a handler writes a receipt (it decides nothing, design 4.4).
    @Test
    void aReceiptPutThenRemove_movesTheMirror_andRequestsAReconcileEachTime() {
        var w = world().activate();

        w.h.clearLog();
        w.h.seed(KEY, VALUE);
        w.h.drain();
        assertThat(w.h.model().receipts.get(CORE_2)).as("after the put").isEqualTo(Option.some(VALUE));
        assertThat(reconcilesQueued(w.h)).as("the put requests one reconcile").isEqualTo(1L);

        w.h.clearLog();
        w.h.seedRemove(KEY);
        w.h.drain();
        assertThat(w.h.model().receipts.get(CORE_2)).as("after the remove").isEqualTo(Option.none());
        assertThat(reconcilesQueued(w.h)).as("the remove requests one reconcile").isEqualTo(1L);
        assertThat(w.h.mutations(DirectiveReceiptKey.class)).as("the mirror's handlers write nothing").isEmpty();
    }

    /// Red when: a refusal that shows a receipt the mirror lags behind does not index it (`Outbox.reindex` goes through `Indexer.indexPut`).
    /// The receipt is committed behind the FSM's back, so the mirror is stale; the refusal carries the store's value.
    @Test
    void aRefusedDraft_reindexesTheReceiptFromTheStore() {
        var worker = node("worker-1");
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withMemberSource(Map.of(worker, "east")))
                       .activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 21L)));
        var first = w.h.submits(SubmitKind.DIRECTIVE).getFirst();

        w.h.held.clear();
        w.h.seedQuietly(KEY, VALUE);
        assertThat(w.h.model().receipts.get(CORE_2)).as("precondition: the mirror lags").isEqualTo(Option.none());
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.settleHeld(first, new Settlement.Refused(w.h.committedLeader(), Map.of(KEY, w.h.store.get(KEY))));

        assertThat(w.h.model().receipts.get(CORE_2)).as("the refusal corrected the mirror").isEqualTo(Option.some(VALUE));
    }

    /// Red when: a refusal that shows a receipt gone from the store does not drop it from the mirror (`Outbox.reindex` goes through
    /// `Indexer.indexRemove`, which has no receipt case). The receipt is removed behind the FSM's back, so the mirror is stale.
    @Test
    void aRefusedDraft_dropsAReceiptTheStoreNoLongerHolds() {
        var worker = node("worker-1");
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withMemberSource(Map.of(worker, "east")))
                       .activate();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 21L)));
        var first = w.h.submits(SubmitKind.DIRECTIVE).getFirst();

        w.h.held.clear();
        w.h.seed(KEY, VALUE);
        w.h.drain();
        w.h.notifying = false;
        w.h.seedRemove(KEY);
        w.h.notifying = true;
        assertThat(w.h.model().receipts.get(CORE_2)).as("precondition: the mirror lags").isEqualTo(Option.some(VALUE));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.settleHeld(first, new Settlement.Refused(w.h.committedLeader(), Map.of(KEY, w.h.store.get(KEY))));

        assertThat(w.h.model().receipts.get(CORE_2)).as("the refusal dropped the receipt from the mirror").isEqualTo(Option.none());
    }

    /// Red when: `Outbox.mirrored` has no receipt case, so `reindex` thinks the mirror never matches and the unit function sees no receipt.
    @Test
    void mirrored_returnsTheReceipt() {
        var w = world().activate();

        assertThat(Outbox.mirrored(w.h.model(), KEY)).as("before any receipt").isEqualTo(Option.none());
        w.h.seed(KEY, VALUE);
        w.h.drain();

        assertThat(Outbox.mirrored(w.h.model(), KEY)).isEqualTo(Option.some((AetherValue) VALUE));
        assertThat(Outbox.mirrored(w.h.model(), DirectiveReceiptKey.directiveReceiptKey(CORE_3))).as("another node id").isEqualTo(Option.none());
    }
}
