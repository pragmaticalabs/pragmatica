// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.UpgradeRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.RestoreAuthorityKey;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #3303 RA-3a: [BackupRestoreKey] (K_A) is the one [RestoreAuthorityKey], so the store's key-typed restore-authority
/// fence (RA-1) applies to the real key. Until RA-3b makes [BackupRestoreValue] a `RestoreAuthority`, every write of it
/// at K_A is refused; these tests pin that the fence reaches the real key, not the value's eventual admission.
class BackupRestoreKeyAuthorityTest {
    private static final BackupRestoreKey KA = BackupRestoreKey.backupRestoreKey();
    private static final UpgradeRunKey CONTROL_KEY = UpgradeRunKey.INSTANCE;
    private static final LeaderValue LEADER = new LeaderValue(NodeId.nodeId("core").unwrap(), 1);
    private static final BackupRestoreValue IN_PROGRESS = BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS,
                                                                                                "lineage",
                                                                                                1L,
                                                                                                2L,
                                                                                                "commit");

    private Map<StructuredKey, Object> snapshotContent = Map.of();

    private final KVStore<StructuredKey, Object> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @SuppressWarnings("unchecked")
        @Override public <T> T read(ByteBuf buffer) { return (T) snapshotContent; }
    });

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private boolean transaction(String id, KVCommand.Mutation<StructuredKey, Object> mutation) {
        var result = (KVCommand.TransactionResult) store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction<>(mutation.key(),
                                                                                                                               id,
                                                                                                                               LEADER,
                                                                                                                               List.of(),
                                                                                                                               List.of(mutation)))))
                                                        .getFirst();
        return result.accepted();
    }

    private void electLeader() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
    }

    private static Stream<Class<?>> leaves(Class<?> type) {
        return type.isSealed()
               ? Arrays.stream(type.getPermittedSubclasses()).flatMap(BackupRestoreKeyAuthorityTest::leaves)
               : Stream.of(type);
    }

    // Test 1: the marker sits on K_A and on no other AetherKey.

    @Test
    void backupRestoreKey_isTheOnlyRestoreAuthorityKey() {
        assertThat(KA).isInstanceOf(RestoreAuthorityKey.class);

        var authorityKeys = leaves(AetherKey.class).filter(RestoreAuthorityKey.class::isAssignableFrom)
                                                   .toList();

        assertThat(leaves(AetherKey.class).count()).as("the walk reaches the key records").isGreaterThan(10);
        assertThat(authorityKeys).containsExactly(BackupRestoreKey.class);
    }

    // Test 2: through the store, the RA-1 fence holds at the real K_A.

    @Test
    void barePutOfBackupRestoreValueAtKa_isDropped() {
        apply(new KVCommand.Put<>(KA, IN_PROGRESS));
        apply(new KVCommand.Put<>(CONTROL_KEY, IN_PROGRESS));

        assertThat(store.get(KA).isEmpty()).isTrue();
        assertThat(store.get(CONTROL_KEY).unwrap()).as("control: the same bare Put elsewhere commits").isEqualTo(IN_PROGRESS);
    }

    @Test
    void transactionPutOfBackupRestoreValueAtKa_isRefused() {
        electLeader();

        assertThat(transaction("ka", new KVCommand.Mutation<>(KA, Option.none(), Option.some(IN_PROGRESS)))).isFalse();
        assertThat(store.get(KA).isEmpty()).isTrue();

        assertThat(transaction("control", new KVCommand.Mutation<>(CONTROL_KEY, Option.none(), Option.some(IN_PROGRESS))))
            .as("control: the same transaction elsewhere commits")
            .isTrue();
    }

    /// The committed value is installed by snapshot, the one path outside the fence, so the refusal is measured
    /// against a cell that holds a value both before and after this change.
    @Test
    void transactionDeleteAtKa_isRefused() {
        electLeader();
        snapshotContent = Map.of(LeaderKey.INSTANCE, LEADER, KA, IN_PROGRESS, CONTROL_KEY, IN_PROGRESS);
        assertThat(store.restoreSnapshot(new byte[0]).isSuccess()).isTrue();

        assertThat(transaction("ka", new KVCommand.Mutation<>(KA, Option.some(IN_PROGRESS), Option.none()))).isFalse();
        assertThat(store.get(KA).unwrap()).isEqualTo(IN_PROGRESS);

        assertThat(transaction("control", new KVCommand.Mutation<>(CONTROL_KEY, Option.some(IN_PROGRESS), Option.none())))
            .as("control: the same delete elsewhere commits")
            .isTrue();
        assertThat(store.get(CONTROL_KEY).isEmpty()).isTrue();
    }
}
