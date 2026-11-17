// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cluster.state.kvstore.KVStoreDispatcherTest.InstallingDeserializer;
import org.pragmatica.cluster.state.kvstore.KVStoreDispatcherTest.Key;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Serializer;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/// #3057: a dispatched store starts its delivery thread on the FIRST append, not at construction (see
/// `NotificationDispatcher#ensureStarted`). A test that expects the thread right after a node's `start()` therefore
/// depends on whether a batch was appended by then, which under load it may not have been. This pins the premise: no
/// thread before the first append, one after. Mutation: start the thread at construction (the first assertion goes red).
class DispatcherLazyStartTest {
    @Test
    @Timeout(60)
    void deliveryThread_startsOnFirstAppend_notBefore() {
        var store = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new InstallingDeserializer(), new NotificationDelivery.Dispatched("lazy" + System.nanoTime(),
                                                                              NotificationDelivery.ReplayRank.flat(),
                                                                              DispatcherFailureSink.loggingOnly(),
                                                                              DeliveryHook.none(),
                                                                              (code, subject, _) -> Unit.unit(),
                                                                              NotificationDelivery.DEFAULT_SLOW_HANDLER_NANOS));

        try {
            assertThat(alive(store.dispatcherThreadName())).as("no delivery thread before the first append").isFalse();
            store.process(store.createBatch(List.of(new KVCommand.Put<>(new Key("a"), "one"))));
            assertThat(alive(store.dispatcherThreadName())).as("the first append starts the delivery thread").isTrue();
        } finally {
            store.close();
        }
    }

    private static boolean alive(String name) {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }
}
