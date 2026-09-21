// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class KVStoreCommittedNotificationTest {
    private record Key(String id) implements StructuredKey {}
    private static final Key FIRST = new Key("first");
    private static final Key SECOND = new Key("second");
    private static final Key REENTRANT = new Key("reentrant");
    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final KVStore<Key, String> store = new KVStore<>(router, new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    @Test
    void committedListenerSeesNewRevisionAndAllowsConcurrentAtomicReader() {
        var snapshots = new ArrayList<Long>();
        var readerCompleted = new AtomicBoolean();
        var monitorHeld = new AtomicBoolean();
        var notificationPending = new AtomicBoolean();
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<Key, String> ignored) -> {
            monitorHeld.set(Thread.holdsLock(store));
            notificationPending.set(store.hasPendingNotifications());
            snapshots.add(store.committedRevision());
            var reader = CompletableFuture.supplyAsync(() -> {
                synchronized (store) {
                    return store.committedRevision() == 17 && store.get(FIRST).unwrap().equals("one")
                        && store.get(SECOND).unwrap().equals("two");
                }
            });
            readerCompleted.set(reader.completeOnTimeout(false, 2, TimeUnit.SECONDS).join());
        });

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"),
                                                        new KVCommand.Put<>(SECOND, "two"))), 17);

        assertThat(monitorHeld).isFalse();
        assertThat(notificationPending).isTrue();
        assertThat(store.hasPendingNotifications()).isFalse();
        assertThat(readerCompleted).isTrue();
        assertThat(snapshots).containsExactly(17L, 17L);
    }

    @Test
    void reentrantCommitQueuesBehindWholeEarlierBatch() {
        var delivered = new ArrayList<Key>();
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<Key, String> put) -> {
            delivered.add(put.cause().key());
            if (put.cause().key().equals(FIRST)) {
                store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(REENTRANT, "nested"))), 18);
            }
        });

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"),
                                                        new KVCommand.Put<>(SECOND, "two"))), 17);

        assertThat(delivered).containsExactly(FIRST, SECOND, REENTRANT);
        assertThat(store.committedRevision()).isEqualTo(18);
    }

    @Test
    void committedRecoveryRemainsSilentUntilReplayAndKeepsRevision() {
        var replayFlags = new ArrayList<Boolean>();
        router.addRoute(KVStoreNotification.ValuePut.class,
            (KVStoreNotification.ValuePut<Key, String> put) -> replayFlags.add(store.isReplaying()));

        assertThat(store.recoverCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 21).isSuccess()).isTrue();
        assertThat(store.committedRevision()).isEqualTo(21);
        assertThat(replayFlags).isEmpty();
        store.replayNotifications();
        assertThat(replayFlags).containsExactly(true);
        assertThat(store.isReplaying()).isFalse();
        assertThat(store.committedRevision()).isEqualTo(21);
    }
    @Test
    void liveReentrantCommitDuringReplayDoesNotInheritReplayFlag() {
        var delivered = new ArrayList<String>();
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<Key, String> put) -> {
            delivered.add(put.cause().key().id() + ":" + store.isReplaying());
            if (put.cause().key().equals(FIRST)) {
                store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(REENTRANT, "nested"))), 22);
            }
        });
        store.recoverCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 21).unwrap();
        store.replayNotifications();
        assertThat(delivered).containsExactly("first:true", "reentrant:false");
        assertThat(store.committedRevision()).isEqualTo(22);
        assertThat(store.hasPendingNotifications()).isFalse();
    }

}
