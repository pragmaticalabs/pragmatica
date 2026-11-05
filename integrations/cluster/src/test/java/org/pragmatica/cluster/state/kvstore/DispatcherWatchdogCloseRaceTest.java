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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// v-2648 A3-2: `close()` does not take the mutation guard, so it can run whole between the first append's "no thread
/// yet, not stopped" check and the watchdog's creation. It then sees neither the thread nor the watchdog, and before the
/// fix the watchdog's executor was never shut down: its thread outlived the closed store. The append now re-checks
/// `stopRequested` after `start()` and shuts the watchdog down. The interleaving is made deterministic with the
/// [DeliveryHook#beforeThreadStart] hook, which runs the close exactly in that window. (A spin-barrier race of 3000 fresh
/// stores per run hit the window in 1 of 20 runs with the fix removed, so the hook replaces it.) Mutation: drop the
/// re-check (red).
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DispatcherWatchdogCloseRaceTest {
    /// Closes the store the moment the first append decides to start the delivery thread.
    static final class ClosingInTheStartWindow implements DeliveryHook {
        final AtomicReference<KVStore<StructuredKey, Object>> store = new AtomicReference<>();
        final AtomicInteger closedInWindow = new AtomicInteger();

        @Override
        public Unit beforeDelivery(long deliverySeq) {
            return Unit.unit();
        }

        @Override
        public Unit beforeThreadStart() {
            closedInWindow.incrementAndGet();

            return store.get().close();
        }
    }

    @Test
    void closeBetweenTheStartCheckAndTheWatchdog_leavesNoWatchdogRunning() throws Exception {
        var hook = new ClosingInTheStartWindow();
        var store = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new InstallingDeserializer(), new NotificationDelivery.Dispatched("wdr" + System.nanoTime(),
                                                                              NotificationDelivery.ReplayRank.flat(),
                                                                              DispatcherFailureSink.loggingOnly(),
                                                                              hook,
                                                                              (code, subject, _) -> Unit.unit(),
                                                                              NotificationDelivery.DEFAULT_SLOW_HANDLER_NANOS));
        hook.store.set(store);

        store.process(store.createBatch(List.of(new KVCommand.Put<>(new Key("a"), "one"))));

        assertThat(hook.closedInWindow.get()).as("the close ran inside the start window").isEqualTo(1);
        assertThat(store.deliveryMetrics().closed()).isTrue();
        assertThat(endsWithin(store.dispatcherThreadName() + "-watchdog")).as("the watchdog of a store closed in the start window")
                                                                         .isTrue();
        assertThat(endsWithin(store.dispatcherThreadName())).as("the delivery thread of a store closed in the start window")
                                                            .isTrue();
    }

    /// A shut-down executor's thread ends shortly after `shutdownNow`; poll with a bound before calling it leaked.
    private static boolean endsWithin(String name) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (alive(name) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        return !alive(name);
    }

    private static boolean alive(String name) {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }
}
