// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.RetirementUnit;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #3860 round 3: receipt inertness enforced in PRODUCTION, not by a test-side scan. The invariant: no production code path other than the
/// receipt's own owner (`RetirementUnit`) can build a mutation of a `DirectiveReceiptKey`, a put or a removal.
///
/// Each writer kind of `facts-f3860d` (section 2) that this module can drive has a pair here:
///
/// - an ENABLED tripwire that asserts what is true TODAY: the write is accepted by the real applier (`KVStore`). It is green now and goes red
///   the day an enforcer lands, which names the tripwire to retire.
/// - a `@Disabled` twin carrying the REAL assertion: whatever the enforcer is (a compile error cannot be expressed here, a throw or a refusal
///   can), after the attempt the receipt store is unchanged. A throw is tolerated, because "fails to compile or throws" are both allowed
///   by the invariant; only an accepted write is not.
///
/// The failure inputs of the round: `gM1x` (a receipt put at `HierarchyStateWriter.java:73`), `gD1c` (a put-form helper in an unlisted
/// file; stood in by a hand-built put transaction, since no such production helper exists), `d1` (a removal helper; same stand-in).
/// The owner control stays green in both states: the retirement of a unit that holds a receipt must keep working.
class ReceiptWriterTripwireTest {
    private static final NodeId CORE = new NodeId("core-1");
    private static final NodeId GONE = new NodeId("worker-9");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final DirectiveReceiptKey RECEIPT = new DirectiveReceiptKey(GONE);
    private static final DirectiveReceiptValue RECEIPT_VALUE = DirectiveReceiptValue.directiveReceiptValue("worker");
    private static final String ENABLE = "lane B: enable when the production enforcer lands (#3860 round 3); the enabled tripwire beside it then goes red and is retired";

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void commitLeader() {
        ((KVStore) store).process(((KVStore) store).createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER))));
    }

    // -- K1: the hierarchy gateway (HierarchyStateWriter.put / commit), g-m1x -----------------------------------------------------------

    /// Tripwire (K1, g-m1x): the gateway takes any key, so a receipt put through it commits today.
    @Test
    void gM1x_hierarchyWriterPut_ofAReceipt_isAcceptedToday() {
        var result = writer().put(RECEIPT, Option.none(), RECEIPT_VALUE).await();

        assertThat(result.isSuccess()).as("the receipt put is accepted (the invariant is not enforced yet)").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    /// Real assertion (K1, g-m1x): a receipt put through the gateway leaves no receipt.
    @Test
    @Disabled(ENABLE)
    void gM1x_hierarchyWriterPut_ofAReceipt_leavesNoReceipt() {
        attempt(() -> writer().put(RECEIPT, Option.none(), RECEIPT_VALUE).await());

        assertThat(store.get(RECEIPT)).as("no code path but the owner puts a receipt").isEqualTo(Option.none());
    }

    /// Tripwire (K1, removal form): the gateway commits a receipt removal today.
    @Test
    void hierarchyWriterCommit_ofAReceiptRemoval_isAcceptedToday() {
        seedReceipt();

        var result = writer().commit(List.of(removal()), List.of()).await();

        assertThat(result.isSuccess()).as("the receipt removal is accepted (the invariant is not enforced yet)").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Real assertion (K1, removal form): a receipt removal through the gateway leaves the receipt where it was.
    @Test
    @Disabled(ENABLE)
    void hierarchyWriterCommit_ofAReceiptRemoval_leavesTheReceipt() {
        seedReceipt();

        attempt(() -> writer().commit(List.of(removal()), List.of()).await());

        assertThat(store.get(RECEIPT)).as("no code path but the owner removes a receipt").isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- K2: the applier funnel every kind ends in (KVStore.process), g-d1c and d1 -----------------------------------------------------

    /// Tripwire (K2, g-d1c): a put-form transaction built by any code (a helper in an unlisted file) is accepted by the applier today.
    @Test
    void gD1c_aHandBuiltReceiptPutTransaction_isAcceptedToday() {
        var results = process(List.of(transaction(put())));

        assertThat(accepted(results)).as("the applier accepts a receipt put (the invariant is not enforced yet)").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    /// Real assertion (K2, g-d1c): a hand-built receipt put leaves no receipt.
    @Test
    @Disabled(ENABLE)
    void gD1c_aHandBuiltReceiptPutTransaction_leavesNoReceipt() {
        attempt(() -> process(List.of(transaction(put()))));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Tripwire (K2, d1): a removal-form transaction built by any code (a removal helper) is accepted by the applier today.
    @Test
    void d1_aHandBuiltReceiptRemovalTransaction_isAcceptedToday() {
        seedReceipt();

        var results = process(List.of(transaction(removal())));

        assertThat(accepted(results)).as("the applier accepts a receipt removal (the invariant is not enforced yet)").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Real assertion (K2, d1): a hand-built receipt removal leaves the receipt where it was.
    @Test
    @Disabled(ENABLE)
    void d1_aHandBuiltReceiptRemovalTransaction_leavesTheReceipt() {
        seedReceipt();

        attempt(() -> process(List.of(transaction(removal()))));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    /// Tripwire (K2, E6 batch): a receipt put in the SECOND transaction of a batch whose first transaction is legal is accepted today. A check on
    /// the first transaction only, or on the batch head, would miss it.
    @Test
    void aReceiptPutBehindALegalTransactionInOneBatch_isAcceptedToday() {
        var results = process(List.of(transaction(legalPut()), transaction(put())));

        assertThat(store.get(RECEIPT)).as("the second transaction of the batch put the receipt").isEqualTo(Option.some(RECEIPT_VALUE));
        assertThat(results).hasSize(2);
    }

    /// Real assertion (K2, E6 batch): the same batch leaves no receipt.
    @Test
    @Disabled(ENABLE)
    void aReceiptPutBehindALegalTransactionInOneBatch_leavesNoReceipt() {
        attempt(() -> process(List.of(transaction(legalPut()), transaction(put()))));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    // -- K2, bare commands (E4, E5): refused by the existing LeaderAuthorized arm; these are controls that must stay refused -------------

    /// Control (E4): a bare `Put` of the receipt value is dropped by the applier's LeaderAuthorized arm today, and must stay dropped.
    @Test
    void aBareReceiptPut_isRefusedAndStaysRefused() {
        process(List.of(new KVCommand.Put<AetherKey, AetherValue>(RECEIPT, RECEIPT_VALUE)));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Control (E5): a witnessless bare `Remove` of a committed receipt is refused by the applier's LeaderAuthorized arm today, and must stay refused.
    @Test
    void aWitnesslessBareReceiptRemove_isRefusedAndStaysRefused() {
        seedReceipt();

        process(List.of(new KVCommand.Remove<AetherKey>(RECEIPT)));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- the owner: stays green with and without the enforcer ----------------------------------------------------------------------------

    /// Control (owner): the retirement unit of a node that holds a receipt removes it, in the transaction production builds
    /// (`RetirementUnit.removals` and `witnesses`). An enforcer must admit exactly this.
    @Test
    void theOwnersRetirementOfAReceipt_isAcceptedBeforeAndAfter() {
        seedReceipt();
        var unit = RetirementUnit.retirementUnit(store::get, GONE);
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(RECEIPT, "owner-retire", LEADER, unit.witnesses(), unit.removals());

        var results = process(List.of(command));

        assertThat(accepted(results)).as("the owner's retirement is accepted").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    // -- fixtures ------------------------------------------------------------------------------------------------------------------------

    private HierarchyStateWriter writer() {
        return HierarchyStateWriter.hierarchyStateWriter(() -> Option.some(LEADER), store::get, commands -> Promise.success(process(commands)));
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> put() {
        return new KVCommand.Mutation<>(RECEIPT, Option.none(), Option.some(RECEIPT_VALUE));
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> removal() {
        return new KVCommand.Mutation<>(RECEIPT, Option.some(RECEIPT_VALUE), Option.none());
    }

    /// A mutation that is legal under any enforcer: a put of a key that is not a receipt.
    private static KVCommand.Mutation<AetherKey, AetherValue> legalPut() {
        return new KVCommand.Mutation<>(AetherKey.AutoHealStateKey.SINGLETON, Option.none(), Option.some(AetherValue.AutoHealStateValue.autoHealStateValue(false, "tripwire")));
    }

    private static KVCommand<AetherKey> transaction(KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(mutation.key(), "tx-" + System.nanoTime(), LEADER, List.of(), List.of(mutation));
    }

    private void seedReceipt() {
        var results = process(List.of(transaction(put())));

        assertThat(accepted(results)).as("fixture: seeding goes through the store; today's applier accepts it").isTrue();
    }

    private static boolean accepted(List<Object> results) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(KVCommand.TransactionResult::accepted);
    }

    /// An enforcer may throw instead of refusing ("fails to compile or throws" are both allowed by the invariant), so the real assertion
    /// ignores the throw and judges only the store.
    private static void attempt(Runnable write) {
        try {
            write.run();
        } catch (RuntimeException | AssertionError expected) {
            // allowed outcome: the write was refused by throwing
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> process(List<? extends KVCommand<AetherKey>> commands) {
        return ((KVStore) store).process(((KVStore) store).createBatch((List) commands));
    }
}
