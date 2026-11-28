// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import io.netty.buffer.ByteBuf;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #3860 round 3, writer kind K3 of `facts-f3860d` (the FSM2 outbox): a `TxDraft` holds any `Mutation<AetherKey, AetherValue>`, and
/// `Outbox.flush` turns it into a `Submit` without looking at the key. The outbox is not the enforcer: the applier is (the `RetiredWithUnit`
/// fence of `KVStore`), so the real assertion feeds every `Submit` the flush produced to a real store that holds the leader, and asserts that
/// no receipt is stored afterwards and that the applier refused the transaction. The harness-level check in `FsmHarness` is NOT used here: it
/// is the test-side enforcer this lane replaces, and it would fail the test before the applier's behaviour is seen.
class ReceiptWriterTripwireTest {
    private static final LeaderValue LEADER = new LeaderValue(FsmHarness.SELF, 1L);
    private static final DirectiveReceiptKey RECEIPT = new DirectiveReceiptKey(CORE_2);
    private static final DirectiveReceiptValue RECEIPT_VALUE = DirectiveReceiptValue.directiveReceiptValue("worker");

    /// Real assertion (K3): a draft that puts a receipt is flushed into a `Submit` (the outbox does not look at keys), and the applier that
    /// receives it refuses it, so no receipt is stored.
    @Test
    void anOutboxDraftThatPutsAReceipt_isRefusedByTheApplier() {
        var store = leaderStore();
        var submitted = submittedTransactions();

        assertThat(submitted).as("the outbox hands the draft to the applier; refusing it is the applier's job").hasSize(1);

        var results = apply(store, submitted);

        assertThat(results).as("the applier refused the transaction at the unit fence (ARM)")
                           .allSatisfy(result -> assertThat(result.refusal().map(KVCommand.Refusal::kind)).isEqualTo(Option.some(KVCommand.RefusalKind.ARM)));
        assertThat(store.get(RECEIPT)).as("no code path but the owner puts a receipt").isEqualTo(Option.none());
    }

    private static List<KVCommand.LeaderTransaction<AetherKey, AetherValue>> submittedTransactions() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);
        var put = new KVCommand.Mutation<AetherKey, AetherValue>(RECEIPT, Option.none(), Option.some(RECEIPT_VALUE));

        m.leader = Option.some(LEADER);
        m.outbox = List.of(TxDraft.draft(SubmitKind.GC, RECEIPT, List.of(put)));
        Outbox.flush(m, ctx, false);

        return ctx.effects()
                  .stream()
                  .filter(ClusterEffect.Submit.class::isInstance)
                  .map(ClusterEffect.Submit.class::cast)
                  .map(ClusterEffect.Submit::transaction)
                  .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KVStore<AetherKey, AetherValue> leaderStore() {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) { return null; }
        });

        ((KVStore) store).process(((KVStore) store).createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER))));

        return store;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<KVCommand.TransactionResult> apply(KVStore<AetherKey, AetherValue> store, List<KVCommand.LeaderTransaction<AetherKey, AetherValue>> transactions) {
        return ((List<Object>) ((KVStore) store).process(((KVStore) store).createBatch((List) transactions))).stream()
                   .map(KVCommand.TransactionResult.class::cast)
                   .toList();
    }
}
