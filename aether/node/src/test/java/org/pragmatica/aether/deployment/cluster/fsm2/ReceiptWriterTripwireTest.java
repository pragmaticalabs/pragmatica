// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #3860 round 3, writer kind K3 of `facts-f3860d` (the FSM2 outbox): a `TxDraft` holds any `Mutation<AetherKey, AetherValue>`, and
/// `Outbox.flush` turns it into a `Submit` without looking at the key. The enabled tripwire asserts what is true today (a draft that
/// puts a receipt reaches a `Submit`); the `@Disabled` twin carries the real assertion (no `Submit` carries a receipt put; a throw is
/// tolerated because "fails to compile or throws" both satisfy the invariant). The harness-level check in `FsmHarness` is NOT used
/// here: it is the test-side enforcer this lane replaces, and it would fail the tripwire before the outbox's behaviour is seen.
class ReceiptWriterTripwireTest {
    private static final LeaderValue LEADER = new LeaderValue(FsmHarness.SELF, 1L);
    private static final DirectiveReceiptKey RECEIPT = new DirectiveReceiptKey(CORE_2);
    private static final DirectiveReceiptValue RECEIPT_VALUE = DirectiveReceiptValue.directiveReceiptValue("worker");
    private static final String ENABLE = "lane B: enable when the production enforcer lands (#3860 round 3); the enabled tripwire beside it then goes red and is retired";

    /// Tripwire (K3): a draft that puts a receipt is flushed into a `Submit` today.
    @Test
    void anOutboxDraftThatPutsAReceipt_isSubmittedToday() {
        assertThat(submittedReceiptPuts()).as("the receipt put reaches a Submit (the invariant is not enforced yet)").hasSize(1);
    }

    /// Real assertion (K3): no `Submit` carries a receipt put.
    @Test
    @Disabled(ENABLE)
    void anOutboxDraftThatPutsAReceipt_isNeverSubmitted() {
        assertThat(attempted()).as("no code path but the owner puts a receipt").isEmpty();
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> attempted() {
        try {
            return submittedReceiptPuts();
        } catch (RuntimeException | AssertionError expected) {
            return List.of();
        }
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> submittedReceiptPuts() {
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
                  .flatMap(submit -> submit.transaction().mutations().stream())
                  .filter(mutation -> mutation.key() instanceof DirectiveReceiptKey && mutation.replacement().isPresent())
                  .toList();
    }
}
