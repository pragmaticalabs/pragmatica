// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.RetirementUnit;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
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

/// #3860 round 3: receipt inertness enforced in PRODUCTION, not by a test-side scan. The invariant: no production transaction other than
/// the receipt's own owner (`RetirementUnit`, the witness and the removal) applies a mutation to a `DirectiveReceiptKey`.
///
/// The enforcer is one runtime refusal at the leader-transaction choke point of the applier (`KVStore`, the [RetiredWithUnit] fence), which
/// every writer kind of `facts-f3860d` (section 2) ends in; each kind this module can drive has a test here that the write leaves the
/// receipt store as it was AND that the applier refused it (kind ARM, naming the receipt key). The failure inputs of the round: `gM1x`
/// (a receipt put at `HierarchyStateWriter.java:73`), `gD1c` (a put-form helper in an unlisted file; stood in by a hand-built put
/// transaction, since no such production helper exists), `d1` (a removal helper; same stand-in), and a non-owner transaction that carries
/// the owner's guards. The owner control stays green: the retirement of a unit that holds a receipt must keep working.
///
/// Receipts are PLANTED (`ReceiptInertness.plant`, the replica effect writer), never put through a transaction, because the applier
/// refuses every receipt put, the fixture's included.
class ReceiptWriterTripwireTest {
    private static final NodeId CORE = new NodeId("core-1");
    private static final NodeId GONE = new NodeId("worker-9");
    private static final NodeId OTHER = new NodeId("worker-8");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final DirectiveReceiptKey RECEIPT = new DirectiveReceiptKey(GONE);
    private static final DirectiveReceiptValue RECEIPT_VALUE = DirectiveReceiptValue.directiveReceiptValue("worker");
    private static final NodeDepartureValue DEPARTED = new NodeDepartureValue(DepartureState.DEPARTED, 1L, new ProcessIncarnation(1L, 0x99L), Option.some(DepartureCause.NODE_REMOVED), 10L);

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

    /// Real assertion (K1, g-m1x): a receipt put through the gateway leaves no receipt, and the gateway reports the failure.
    @Test
    void gM1x_hierarchyWriterPut_ofAReceipt_leavesNoReceipt() {
        var result = writer().put(RECEIPT, Option.none(), RECEIPT_VALUE).await();

        assertThat(result.isFailure()).as("the gateway reports the refusal").isTrue();
        assertThat(store.get(RECEIPT)).as("no code path but the owner puts a receipt").isEqualTo(Option.none());
    }

    /// Real assertion (K1, removal form): a receipt removal through the gateway leaves the receipt where it was.
    @Test
    void hierarchyWriterCommit_ofAReceiptRemoval_leavesTheReceipt() {
        seedReceipt();

        var result = writer().commit(List.of(removal()), List.of()).await();

        assertThat(result.isFailure()).as("the gateway reports the refusal").isTrue();
        assertThat(store.get(RECEIPT)).as("no code path but the owner removes a receipt").isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- K2: the applier funnel every kind ends in (KVStore.process), g-d1c and d1 -----------------------------------------------------

    /// Real assertion (K2, g-d1c): a hand-built receipt put leaves no receipt, refused at the receipt key.
    @Test
    void gD1c_aHandBuiltReceiptPutTransaction_leavesNoReceipt() {
        var results = process(List.of(transaction(put())));

        assertThat(refusedAtTheReceipt(results)).as("the applier refuses a receipt put").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Real assertion (K2, d1): a hand-built receipt removal leaves the receipt where it was, refused at the receipt key.
    @Test
    void d1_aHandBuiltReceiptRemovalTransaction_leavesTheReceipt() {
        seedReceipt();

        var results = process(List.of(transaction(removal())));

        assertThat(refusedAtTheReceipt(results)).as("the applier refuses a receipt removal that is not the unit's").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    /// Real assertion (K2, E6 batch): a receipt put in the SECOND transaction of a batch whose first transaction is legal leaves no receipt. A
    /// check on the first transaction only, or on the batch head, would miss it.
    @Test
    void aReceiptPutBehindALegalTransactionInOneBatch_leavesNoReceipt() {
        var results = process(List.of(transaction(legalPut()), transaction(put())));

        assertThat(results).hasSize(2);
        assertThat(acceptedCount(results)).as("the legal first transaction commits, the receipt put does not").isEqualTo(1);
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// A put that replaces a committed receipt with another one is a put as well.
    @Test
    void aReceiptReplacement_isRefused() {
        seedReceipt();

        var results = process(List.of(transaction(new KVCommand.Mutation<>(RECEIPT, Option.some(RECEIPT_VALUE), Option.some(DirectiveReceiptValue.directiveReceiptValue("core"))))));

        assertThat(refusedAtTheReceipt(results)).isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- the owner's guards, reused or rebuilt by a non-owner ----------------------------------------------------------------------------

    /// Forged input: the owner's own witnesses (read from the owner's unit) around a receipt PUT. The guards decide nothing for a put.
    @Test
    void theOwnersWitnesses_aroundAReceiptPut_areRefused() {
        var unit = RetirementUnit.retirementUnit(store::get, GONE);

        var results = process(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(RECEIPT, "forged-put", LEADER, unit.witnesses(), List.of(put()))));

        assertThat(refusedAtTheReceipt(results)).as("the owner's witnesses do not admit a put").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Forged input: the owner's witnesses of ONE node's unit reused to remove ANOTHER node's receipt.
    @Test
    void theOwnersWitnessesOfOneUnit_reusedForAnotherNodesReceipt_areRefused() {
        var other = new DirectiveReceiptKey(OTHER);
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);
        ReceiptInertness.plant(store, other, RECEIPT_VALUE);
        var owners = RetirementUnit.retirementUnit(store::get, GONE);
        var removalOfOther = new KVCommand.Mutation<AetherKey, AetherValue>(other, Option.some(RECEIPT_VALUE), Option.none());

        var results = process(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(other, "forged-reuse", LEADER, owners.witnesses(), List.of(removalOfOther))));

        assertThat(refusedAtTheReceipt(results, other)).as("another node's receipt is not covered by this unit's guards").isTrue();
        assertThat(store.get(other)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    /// Forged input: the receipt removed beside a departure record that is present and untouched (a witness that holds, no removal).
    @Test
    void aReceiptRemoval_besideAPresentUntouchedRecord_isRefused() {
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);
        ReceiptInertness.plant(store, new NodeDepartureKey(GONE), DEPARTED);
        var guards = List.of(new KVCommand.ReadWitness<AetherKey>(new NodeDepartureKey(GONE), Option.some(DEPARTED)),
                             new KVCommand.ReadWitness<AetherKey>(new ActivationDirectiveKey(GONE), Option.none()));

        var results = process(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(RECEIPT, "forged-present", LEADER, guards, List.of(removal()))));

        assertThat(refusedAtTheReceipt(results)).as("a present mate that is not retired with it refuses the removal").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- K2, bare commands (E4, E5): refused by the existing LeaderAuthorized arm; these are controls that must stay refused -------------

    /// Control (E4): a bare `Put` of the receipt value is dropped by the applier's LeaderAuthorized arm, and must stay dropped.
    @Test
    void aBareReceiptPut_isRefusedAndStaysRefused() {
        process(List.of(new KVCommand.Put<AetherKey, AetherValue>(RECEIPT, RECEIPT_VALUE)));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// A bare `Put` of a value that is NOT the receipt value (so not `LeaderAuthorized`) at the receipt key is dropped by the key's marker.
    @Test
    void aBarePutOfAnotherValueAtTheReceiptKey_isDropped() {
        process(List.of(new KVCommand.Put<AetherKey, AetherValue>(RECEIPT, ActivationDirectiveValue.worker())));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Control (E5): a witnessless bare `Remove` of a committed receipt is refused by the applier's LeaderAuthorized arm, and must stay refused.
    @Test
    void aWitnesslessBareReceiptRemove_isRefusedAndStaysRefused() {
        seedReceipt();

        process(List.of(new KVCommand.Remove<AetherKey>(RECEIPT)));

        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- the owner: admitted ----------------------------------------------------------------------------------------------------------

    /// Control (owner, E3): the retirement unit of a node that holds only a receipt removes it, in the transaction production builds
    /// (`RetirementUnit.removals` and `witnesses`: the absence of the record and of the directive). The enforcer admits exactly this.
    @Test
    void theOwnersRetirementOfAReceipt_isAcceptedBeforeAndAfter() {
        seedReceipt();
        var unit = RetirementUnit.retirementUnit(store::get, GONE);
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(RECEIPT, "owner-retire", LEADER, unit.witnesses(), unit.removals());

        var results = process(List.of(command));

        assertThat(accepted(results)).as("the owner's retirement is accepted").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Control (owner, E3): the whole unit (record, directive, receipt) retires in one transaction; the mates leave in the same transaction.
    @Test
    void theOwnersRetirementOfAWholeUnit_isAccepted() {
        ReceiptInertness.plant(store, new NodeDepartureKey(GONE), DEPARTED);
        ReceiptInertness.plant(store, new ActivationDirectiveKey(GONE), ActivationDirectiveValue.worker());
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);
        var unit = RetirementUnit.retirementUnit(store::get, GONE);

        var results = process(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(RECEIPT, "owner-unit", LEADER, unit.witnesses(), unit.removals())));

        assertThat(accepted(results)).as("the owner's retirement of the whole unit is accepted").isTrue();
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
        assertThat(store.get(new NodeDepartureKey(GONE))).isEqualTo(Option.none());
        assertThat(store.get(new ActivationDirectiveKey(GONE))).isEqualTo(Option.none());
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
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);
    }

    private static boolean accepted(List<Object> results) {
        return acceptedCount(results) > 0;
    }

    private static long acceptedCount(List<Object> results) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .filter(KVCommand.TransactionResult::accepted)
                      .count();
    }

    private static boolean refusedAtTheReceipt(List<Object> results) {
        return refusedAtTheReceipt(results, RECEIPT);
    }

    /// Some transaction of the batch was refused by the unit fence (kind ARM) at exactly this receipt key.
    private static boolean refusedAtTheReceipt(List<Object> results, DirectiveReceiptKey key) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.refusal().equals(Option.some(KVCommand.Refusal.refusal(KVCommand.RefusalKind.ARM, Option.some(key)))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> process(List<? extends KVCommand<AetherKey>> commands) {
        return ((KVStore) store).process(((KVStore) store).createBatch((List) commands));
    }
}
