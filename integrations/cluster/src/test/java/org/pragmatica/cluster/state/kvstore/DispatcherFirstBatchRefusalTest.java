// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// v-2648 F1 (PR #2648 CI red in `KVStoreDispatcherTest$Replay.replayFromTheDispatcherThreadIsRefused`): a replay a
/// handler calls during the very FIRST batch a store delivers must be refused ("caller is the dispatcher thread"), never
/// wait for itself. The refusal reads the dispatcher's thread field; `ensureStarted` used to assign it only after
/// `Thread.start()`, so the first batch could run before the field was set. Every round is a fresh store, so every round
/// is a first batch. Measured before the fix (vd1A, bigboy): 6 of 300 rounds missed the refusal, so 200 rounds go red with
/// probability about 0.98 on the unfixed code; the fix (assign, then start) makes a miss impossible, not rare.
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DispatcherFirstBatchRefusalTest {
    record Key(String value) implements StructuredKey {}

    static final int ROUNDS = 200;

    @Test
    void replayFromTheFirstBatch_isRefusedAsTheDispatcherThread_everyRound() throws Exception {
        var missed = 0;
        var wrongReason = new CopyOnWriteArrayList<String>();

        for (var round = 0; round < ROUNDS; round++) {
            var reasons = new CopyOnWriteArrayList<String>();
            var stopped = new CountDownLatch(1);
            DispatcherFailureSink sink = (_, cause) -> recordStop(reasons, stopped, cause);
            var router = MessageRouter.mutable();
            var store = new KVStore<StructuredKey, Object>(router, new Serializer() {
                @Override public <T> void write(ByteBuf buffer, T value) {}
            }, new Deserializer() {
                @Override public <T> T read(ByteBuf buffer) { return null; }
            }, NotificationDelivery.dispatched("first" + round, sink));
            router.addRoute(KVStoreNotification.ValuePut.class, _ -> store.replayNotifications());
            try {
                store.process(store.createBatch(List.of(new KVCommand.Put<>(new Key("a"), "one"))));
                if (!stopped.await(2, TimeUnit.SECONDS)) {
                    missed++;
                } else if (!reasons.getFirst().contains("dispatcher thread")) {
                    wrongReason.add(reasons.getFirst());
                }
            } finally {
                store.close();
            }
        }

        assertThat(missed).as("rounds whose first-batch replay was not refused (the dispatcher waited for itself)").isZero();
        assertThat(wrongReason).as("rounds refused for another reason").isEmpty();
    }

    private static Unit recordStop(List<String> reasons, CountDownLatch stopped, Throwable cause) {
        reasons.add(cause.getMessage());
        stopped.countDown();

        return Unit.unit();
    }
}
