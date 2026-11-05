// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// v-2648 F4 (design §3.2: "the dispatcher thread never ends any other way, except by KVStore.close()"). The delivery loop
/// blocks in `queue.take()`, which throws on a set interrupt flag; the loop used to map that to a silent exit, leaving the
/// store open with nothing delivering and every later write's apply promise parked forever.
///
/// - a handler that leaves its thread's interrupt flag set does not end delivery: the flag is cleared, later writes deliver;
/// - an interrupt of an idle loop that `close()` did not send takes the failure path: the store closes and the sink is called.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DispatcherInterruptTest {
    record Key(String value) implements StructuredKey {}

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final List<Throwable> stopCauses = new CopyOnWriteArrayList<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final KVStore<StructuredKey, Object> store = new KVStore<>(router, new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    }, NotificationDelivery.dispatched("intr" + System.nanoTime(), (_, cause) -> recordStop(cause)));

    @AfterEach
    void closeStore() {
        store.close();
    }

    /// Mutation: drop the clear after a delivered batch (the later write's promise times out, the store still open: red).
    @Test
    void handlerLeavingTheInterruptFlagSet_doesNotEndDelivery() throws Exception {
        var firstHandled = new CountDownLatch(1);
        router.addRoute(KVStoreNotification.ValuePut.class,
                        (KVStoreNotification.ValuePut<StructuredKey, Object> put) -> interruptOnA(put, firstHandled));

        put("a");
        assertThat(firstHandled.await(5, TimeUnit.SECONDS)).as("CONTROL: the interrupting handler ran").isTrue();
        var later = Promise.<Unit>promise();

        put("b");
        store.completeAfterDelivery(later, Unit.unit());

        assertThat(later.await(timeSpan(5).seconds()).isSuccess()).as("a later write is delivered").isTrue();
        assertThat(store.deliveryMetrics().closed()).isFalse();
        assertThat(stopCauses).isEmpty();
    }

    /// Mutation: map a failed `take()` back to a silent exit (the sink is never called, the store stays open: red).
    @Test
    void interruptOfTheIdleLoopOutsideClose_takesTheFailurePath() throws Exception {
        var delivered = new CountDownLatch(1);
        router.addRoute(KVStoreNotification.ValuePut.class, _ -> delivered.countDown());

        put("a");
        assertThat(delivered.await(5, TimeUnit.SECONDS)).as("CONTROL: the loop started and delivered").isTrue();
        var thread = dispatcherThread();

        assertThat(awaitIdleInTake(thread)).as("CONTROL: the loop is idle, blocked in take()").isTrue();
        thread.interrupt();

        assertThat(stopped.await(5, TimeUnit.SECONDS)).as("the sink is called").isTrue();
        assertThat(stopCauses.getFirst().getMessage()).contains("interrupted outside close()");
        assertThat(store.deliveryMetrics().closed()).isTrue();
    }

    /// Control for the arm above: `close()` interrupts the loop too, and that is never a failure.
    @Test
    void closeEndsTheLoop_withoutTheFailurePath() throws Exception {
        var delivered = new CountDownLatch(1);
        router.addRoute(KVStoreNotification.ValuePut.class, _ -> delivered.countDown());

        put("a");
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        var thread = dispatcherThread();

        store.close();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive()).isFalse();
        assertThat(stopCauses).isEmpty();
    }

    private void put(String key) {
        store.process(store.createBatch(List.of(new KVCommand.Put<>(new Key(key), key))));
    }

    private Thread dispatcherThread() {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .filter(thread -> thread.getName().equals(store.dispatcherThreadName()))
                     .findFirst()
                     .orElseThrow();
    }

    /// An interrupt that lands while a batch is still being delivered is a handler's flag (cleared, delivery continues);
    /// this arm needs the loop blocked in `take()`.
    private static boolean awaitIdleInTake(Thread thread) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING && blockedInTake(thread)) {
                return true;
            }
            Thread.sleep(5);
        }

        return false;
    }

    private static boolean blockedInTake(Thread thread) {
        for (var frame : thread.getStackTrace()) {
            if (frame.getClassName().equals("java.util.concurrent.LinkedBlockingQueue") && frame.getMethodName().equals("take")) {
                return true;
            }
        }

        return false;
    }

    private static void interruptOnA(KVStoreNotification.ValuePut<StructuredKey, Object> put, CountDownLatch handled) {
        if (((Key) put.cause().key()).value().equals("a")) {
            Thread.currentThread().interrupt();
        }

        handled.countDown();
    }

    private Unit recordStop(Throwable cause) {
        stopCauses.add(cause);
        stopped.countDown();

        return Unit.unit();
    }
}
