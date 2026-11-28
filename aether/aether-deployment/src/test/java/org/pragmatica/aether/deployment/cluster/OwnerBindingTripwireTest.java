// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #3828 part B, the owner-binding ruling (`s56/ruling-3828-owner-binding.md`): a mutation of a `DirectiveReceiptKey` exists only inside a
/// transaction that the receipt's owner built (the issue builder or `RetirementUnit`). The applier admits a receipt removal today by its SHAPE,
/// whoever authored it, so each failure input of the ruling is ADMITTED at this head. Each input is a pair:
/// - an ENABLED tripwire that asserts the current admission, with a failure message that names what to enable;
/// - the real assertion (REFUSED, the receipt stays) beside it, `@Disabled` until the owner-bound variant lands.
///
/// The forge probes are ported from `ProbeV3860hTest` (`forge_liveReceiptAlone_...` and `forge_liveAdmittedUnit_...`). The generic writers are
/// the transaction shapes of `BackupRestoreCoordinator.transaction` (anchored at `BackupRestoreKey`) and `RollbackManager` (anchored at its
/// target key, guarded by `ClusterConfigKey`), each naming a receipt removal. The fourth failure input, a code path outside the owner package
/// that constructs the variant, is a COMPILE failure and is shown by a javac probe over a test-source copy (lane B), not by a test here.
/// The must-stay-admitted controls (the owner's whole unit, and record plus receipt) are `ReceiptWriterTripwireTest`'s
/// `theOwnersRetirementOf*` rows.
class OwnerBindingTripwireTest {
    private static final String ENABLE = "ENABLE the @Disabled twin %s and DELETE this tripwire when the owner-bound receipt variant lands (#3828 part B, ruling 2).";
    private static final NodeId CORE = new NodeId("core-1");
    private static final NodeId N = new NodeId("worker-7");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final DirectiveReceiptKey RECEIPT = new DirectiveReceiptKey(N);
    private static final NodeDepartureKey RECORD = new NodeDepartureKey(N);
    private static final ActivationDirectiveKey DIRECTIVE = new ActivationDirectiveKey(N);
    private static final DirectiveReceiptValue RECEIPT_VALUE = DirectiveReceiptValue.directiveReceiptValue("worker");
    private static final NodeDepartureValue ADMITTED = new NodeDepartureValue(DepartureState.ADMITTED,
                                                                              1L,
                                                                              new ProcessIncarnation(1L, 0x99L),
                                                                              Option.none(),
                                                                              10L);

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

    // -- failure input 1: forge probe, a live node's receipt alone, mates witnessed absent -------------------------------------------------

    @Test
    void tripwire_forgeLiveReceiptAlone_isAdmittedToday() {
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);

        var results = process(List.of(liveReceiptAlone("cleaner:")));

        assertThat(accepted(results)).as(ENABLE.formatted("forgeLiveReceiptAlone_isRefused")).isTrue();
        assertThat(store.get(RECEIPT)).as(ENABLE.formatted("forgeLiveReceiptAlone_isRefused")).isEqualTo(Option.none());
    }

    @Disabled("#3828 part B: enable with the owner-bound receipt variant; admitted today by shape (ProbeV3860hTest :66)")
    @Test
    void forgeLiveReceiptAlone_isRefused() {
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);

        var results = process(List.of(liveReceiptAlone("cleaner:")));

        assertThat(accepted(results)).as("an ordinary LeaderTransaction naming the receipt key is refused").isFalse();
        assertThat(store.get(RECEIPT)).as("the receipt stays").isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- failure input 2: forge probe, a live admitted unit removed whole by a non-owner ------------------------------------------------------

    @Test
    void tripwire_forgeLiveAdmittedUnit_isAdmittedToday() {
        plantLiveUnit();

        var results = process(List.of(liveUnitRemoval()));

        assertThat(accepted(results)).as(ENABLE.formatted("forgeLiveAdmittedUnit_isRefused")).isTrue();
        assertThat(store.get(RECEIPT)).as(ENABLE.formatted("forgeLiveAdmittedUnit_isRefused")).isEqualTo(Option.none());
    }

    @Disabled("#3828 part B: enable with the owner-bound receipt variant; admitted today by shape (ProbeV3860hTest :80)")
    @Test
    void forgeLiveAdmittedUnit_isRefused() {
        plantLiveUnit();

        var results = process(List.of(liveUnitRemoval()));

        assertThat(accepted(results)).as("a non-owner removal of a live unit naming the receipt is refused").isFalse();
        assertThat(store.get(RECEIPT)).as("the receipt stays").isEqualTo(Option.some(RECEIPT_VALUE));
        assertThat(store.get(DIRECTIVE)).as("the transaction is refused whole, the directive stays").isEqualTo(Option.some(ActivationDirectiveValue.worker()));
    }

    // -- failure input 3a: a generic writer, the BackupRestoreCoordinator transaction shape ---------------------------------------------------

    @Test
    void tripwire_backupRestoreShapedTransactionNamingAReceipt_isAdmittedToday() {
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);

        var results = process(List.of(backupRestoreShaped()));

        assertThat(accepted(results)).as(ENABLE.formatted("backupRestoreShapedTransactionNamingAReceipt_isRefused")).isTrue();
        assertThat(store.get(RECEIPT)).as(ENABLE.formatted("backupRestoreShapedTransactionNamingAReceipt_isRefused")).isEqualTo(Option.none());
    }

    @Disabled("#3828 part B: enable with the owner-bound receipt variant; admitted today by shape (BackupRestoreCoordinator.transaction)")
    @Test
    void backupRestoreShapedTransactionNamingAReceipt_isRefused() {
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);

        var results = process(List.of(backupRestoreShaped()));

        assertThat(accepted(results)).as("a BackupRestoreKey-anchored transaction naming the receipt key is refused").isFalse();
        assertThat(store.get(RECEIPT)).as("the receipt stays").isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- failure input 3b: a generic writer, the RollbackManager transaction shape -------------------------------------------------------------

    @Test
    void tripwire_rollbackShapedTransactionNamingAReceipt_isAdmittedToday() {
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);

        var results = process(List.of(rollbackShaped()));

        assertThat(accepted(results)).as(ENABLE.formatted("rollbackShapedTransactionNamingAReceipt_isRefused")).isTrue();
        assertThat(store.get(RECEIPT)).as(ENABLE.formatted("rollbackShapedTransactionNamingAReceipt_isRefused")).isEqualTo(Option.none());
    }

    @Disabled("#3828 part B: enable with the owner-bound receipt variant; admitted today by shape (RollbackManager.rollbackTransaction)")
    @Test
    void rollbackShapedTransactionNamingAReceipt_isRefused() {
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);

        var results = process(List.of(rollbackShaped()));

        assertThat(accepted(results)).as("a rollback-shaped transaction naming the receipt key is refused").isFalse();
        assertThat(store.get(RECEIPT)).as("the receipt stays").isEqualTo(Option.some(RECEIPT_VALUE));
    }

    // -- fixtures -------------------------------------------------------------------------------------------------------------------------------

    private static KVCommand<AetherKey> liveReceiptAlone(String prefix) {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(RECEIPT,
                                                                       prefix + UUID.randomUUID(),
                                                                       LEADER,
                                                                       List.of(new KVCommand.ReadWitness<AetherKey>(RECORD, Option.none()),
                                                                               new KVCommand.ReadWitness<AetherKey>(DIRECTIVE, Option.none())),
                                                                       List.of(receiptRemoval()));
    }

    private void plantLiveUnit() {
        ReceiptInertness.plant(store, RECORD, ADMITTED);
        ReceiptInertness.plant(store, DIRECTIVE, ActivationDirectiveValue.worker());
        ReceiptInertness.plant(store, RECEIPT, RECEIPT_VALUE);
    }

    private static KVCommand<AetherKey> liveUnitRemoval() {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(RECEIPT,
                                                                       "forged-unit:" + UUID.randomUUID(),
                                                                       LEADER,
                                                                       List.of(),
                                                                       List.of(new KVCommand.Mutation<>(RECORD, Option.some(ADMITTED), Option.none()),
                                                                               new KVCommand.Mutation<>(DIRECTIVE,
                                                                                                        Option.some(ActivationDirectiveValue.worker()),
                                                                                                        Option.none()),
                                                                               receiptRemoval()));
    }

    /// The coordinator's anchor and id format (`BackupRestoreCoordinator.transaction`), carrying a receipt removal beside its mates' absence.
    private static KVCommand<AetherKey> backupRestoreShaped() {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(BackupRestoreKey.backupRestoreKey(),
                                                                       "restore-" + UUID.randomUUID(),
                                                                       LEADER,
                                                                       List.of(new KVCommand.ReadWitness<AetherKey>(RECORD, Option.none()),
                                                                               new KVCommand.ReadWitness<AetherKey>(DIRECTIVE, Option.none())),
                                                                       List.of(receiptRemoval()));
    }

    /// The rollback transaction's shape (`RollbackManager.rollbackTransaction`): anchored at its target key, a configuration guard, a random id.
    private static KVCommand<AetherKey> rollbackShaped() {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(ClusterConfigKey.CURRENT,
                                                                       UUID.randomUUID().toString(),
                                                                       LEADER,
                                                                       List.of(new KVCommand.ReadWitness<AetherKey>(ClusterConfigKey.CURRENT, Option.none()),
                                                                               new KVCommand.ReadWitness<AetherKey>(RECORD, Option.none()),
                                                                               new KVCommand.ReadWitness<AetherKey>(DIRECTIVE, Option.none())),
                                                                       List.of(receiptRemoval()));
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> receiptRemoval() {
        return new KVCommand.Mutation<>(RECEIPT, Option.some(RECEIPT_VALUE), Option.none());
    }

    private static boolean accepted(List<Object> results) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(KVCommand.TransactionResult::accepted);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> process(List<? extends KVCommand<AetherKey>> commands) {
        return ((KVStore) store).process(((KVStore) store).createBatch((List) commands));
    }
}
