// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KVStoreLeaderTransactionTest {
    private record Key(String id) implements StructuredKey {}
    private record Version(long fenceVersion) implements VersionFenced {}
    private static final Key FIRST = new Key("first");
    private static final Key SECOND = new Key("second");
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);
    private final java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
    private MessageRouter router() {
        var router = MessageRouter.mutable();
        router.addRoute(KVStoreNotification.ValuePut.class, _ -> notifications.incrementAndGet());
        router.addRoute(KVStoreNotification.ValueRemove.class, _ -> notifications.incrementAndGet());
        return router;
    }
    private final KVStore<StructuredKey, Object> store = new KVStore<>(router(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    private Object apply(KVCommand<StructuredKey> command) {
        return store.process(store.createBatch(List.of(command))).getFirst();
    }

    private KVCommand.TransactionResult transaction(String id, List<KVCommand.Mutation<StructuredKey, Object>> mutations) {
        return (KVCommand.TransactionResult) apply(new KVCommand.LeaderTransaction<>(FIRST, id, LEADER, List.of(), mutations));
    }

    private KVCommand.Mutation<StructuredKey, Object> insert(StructuredKey key, Object value) {
        return new KVCommand.Mutation<>(key, Option.none(), Option.some(value));
    }

    @Test
    void conflictingSecondMutation_leavesFirstUntouchedAndReportsOwnRefusal() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        apply(new KVCommand.Put<>(SECOND, "occupied"));
        var result = transaction("reservation-1", List.of(insert(FIRST, "new"), insert(SECOND, "new")));
        assertThat(result).isEqualTo(new KVCommand.TransactionResult("reservation-1", false));
        assertThat(store.get(FIRST).isEmpty()).isTrue();
        assertThat(store.get(SECOND).unwrap()).isEqualTo("occupied");
    }

    @Test
    void validTransaction_changesAllKeysAndReportsSuccess() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        var result = transaction("reservation-2", List.of(insert(FIRST, "a"), insert(SECOND, "b")));
        assertThat(result.accepted()).isTrue();
        assertThat(store.get(FIRST).unwrap()).isEqualTo("a");
        assertThat(store.get(SECOND).unwrap()).isEqualTo("b");
    }

    @Test
    void staleFenceInOneMutation_rejectsWholeTransaction() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        var version = new Version(5);
        apply(new KVCommand.Put<>(SECOND, version));
        var result = transaction("reservation-3", List.of(insert(FIRST, "a"),
            new KVCommand.Mutation<>(SECOND, Option.some(version), Option.some(new Version(7)))));
        assertThat(result.accepted()).isFalse();
        assertThat(store.get(FIRST).isEmpty()).isTrue();
        assertThat(store.get(SECOND).unwrap()).isEqualTo(version);
    }

    @Test
    void duplicateKeyOrStaleLeader_rejectsWithoutPartialMutation() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        assertThat(transaction("duplicate", List.of(insert(FIRST, "a"), insert(FIRST, "b"))).accepted()).isFalse();
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(new NodeId("next"), 2)));
        assertThat(transaction("old-leader", List.of(insert(FIRST, "a"), insert(SECOND, "b"))).accepted()).isFalse();
        assertThat(store.get(FIRST).isEmpty()).isTrue();
        assertThat(store.get(SECOND).isEmpty()).isTrue();
    }
    @Test
    void secondReadGuardConflictChangesNoWrittenKeyAndEmitsNoNotification() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        var firstRead = new Key("first-read");
        var secondRead = new Key("second-read");
        apply(new KVCommand.Put<>(firstRead, "unchanged"));
        apply(new KVCommand.Put<>(secondRead, "changed"));
        var result = (KVCommand.TransactionResult) apply(new KVCommand.LeaderTransaction<>(FIRST, "read-conflict", LEADER,
            List.of(new KVCommand.ReadWitness<StructuredKey>(firstRead, Option.some("unchanged")),
                    new KVCommand.ReadWitness<StructuredKey>(secondRead, Option.some("old"))),
            List.of(insert(FIRST, "a"), insert(SECOND, "b"))));
        assertThat(result).isEqualTo(new KVCommand.TransactionResult("read-conflict", false));
        assertThat(store.get(FIRST).isEmpty()).isTrue();
        assertThat(store.get(SECOND).isEmpty()).isTrue();
        assertThat(notifications.get()).isEqualTo(3);
    }

    @Test
    void staleLeaderSingleMutationReportsCallerChosenCorrelation() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(new NodeId("successor"), 2)));
        assertThat(transaction("my-single-write", List.of(insert(FIRST, "a"))))
            .isEqualTo(new KVCommand.TransactionResult("my-single-write", false));
    }

}
