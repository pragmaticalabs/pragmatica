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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// v-2648 A3-2: `close()` does not take the mutation guard, so it can run whole between the first append's
/// "no thread yet, not stopped" check and the watchdog's creation. It then sees neither the thread nor the watchdog, and
/// before the fix the watchdog's executor was never shut down (its thread outlived the store). The append now re-checks
/// `stopRequested` after `start()` and shuts the watchdog down. Every round races a fresh store's first append against its
/// close, released together from a spin barrier; after every round, no watchdog thread of a closed store may stay alive.
/// The window is narrow, so the race runs many rounds; mutation, drop the re-check (red, rate in the PR evidence).
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DispatcherWatchdogCloseRaceTest {
    private static final int ROUNDS = 3_000;
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(150);

    @Test
    void closeRacingTheFirstAppend_leavesNoWatchdogRunning() throws Exception {
        var watchdogs = new ArrayList<String>();
        var started = System.nanoTime();

        for (int round = 0; round < ROUNDS && System.nanoTime() - started < DEADLINE_NANOS; round++) {
            watchdogs.add(race(round));
        }

        assertThat(watchdogs.size()).as("rounds run before the deadline").isGreaterThanOrEqualTo(500);
        assertThat(leftAlive(watchdogs)).as("watchdog threads alive after their store closed (of %d rounds)", watchdogs.size())
                                        .isEmpty();
    }

    private static String race(int round) throws InterruptedException {
        var store = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new InstallingDeserializer(), new NotificationDelivery.Dispatched("wdr" + round + "-" + System.nanoTime(),
                                                                              NotificationDelivery.ReplayRank.flat(),
                                                                              DispatcherFailureSink.loggingOnly(),
                                                                              DeliveryHook.none(),
                                                                              (code, subject, _) -> Unit.unit(),
                                                                              NotificationDelivery.DEFAULT_SLOW_HANDLER_NANOS));
        var ready = new AtomicInteger();
        var go = new AtomicBoolean();
        var appender = Thread.ofPlatform().start(() -> appendAfter(ready, go, store));
        var closer = Thread.ofPlatform().start(() -> closeAfter(ready, go, store));

        while (ready.get() < 2) {
            Thread.onSpinWait();
        }
        go.set(true);
        appender.join(TimeUnit.SECONDS.toMillis(10));
        closer.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(appender.isAlive() || closer.isAlive()).as("round %d ended", round).isFalse();

        return store.dispatcherThreadName() + "-watchdog";
    }

    private static void appendAfter(AtomicInteger ready, AtomicBoolean go, KVStore<StructuredKey, Object> store) {
        spinUntil(ready, go);
        store.process(store.createBatch(List.of(new KVCommand.Put<>(new Key("a"), "one"))));
    }

    private static void closeAfter(AtomicInteger ready, AtomicBoolean go, KVStore<StructuredKey, Object> store) {
        spinUntil(ready, go);
        store.close();
    }

    private static void spinUntil(AtomicInteger ready, AtomicBoolean go) {
        ready.incrementAndGet();
        while (!go.get()) {
            Thread.onSpinWait();
        }
    }

    /// A shut-down executor's thread ends shortly after `shutdownNow`; poll with a bound before calling one leaked.
    private static Set<String> leftAlive(List<String> watchdogs) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var alive = aliveAmong(watchdogs);

        while (!alive.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            alive = aliveAmong(watchdogs);
        }

        return alive;
    }

    private static Set<String> aliveAmong(List<String> names) {
        var wanted = Set.copyOf(names);

        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(Thread::isAlive)
                     .map(Thread::getName)
                     .filter(wanted::contains)
                     .collect(Collectors.toSet());
    }
}
