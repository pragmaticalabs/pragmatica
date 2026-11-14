// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #3073: `NotificationDispatcher.close()` joins the delivery thread, so a caller that closes and then releases resources
/// cannot race a final delivery. Without the join the old "no dispatcher thread remains" assertions still pass, because the
/// thread ends on its own path once the handler returns; the join is only observable while a delivery is in flight.
///
/// The handler holds the delivery thread on a latch and ignores `close()`'s interrupt, so the delivery is provably still
/// running when `close()` is called; `close()` must stay blocked until the handler is released.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DispatcherCloseJoinTest {
    record Key(String value) implements StructuredKey {}

    /// How long `close()` is given to return while the delivery is held. With the join it cannot return before the release
    /// (its own bound is `JOIN_TIMEOUT_MILLIS`, 5 s); without it, it returns in microseconds. A slow host only makes the
    /// mutated arm read red later, never the intact one. Blind band, stated: a join bound at or above this window (about 2 s)
    /// is not detected, because the delivery is released when the window ends; a bound below it (for example 700 or 1500 ms)
    /// lets `close()` return while the handler is still held and goes red.
    private static final long CLOSE_RETURN_WINDOW_MILLIS = 2_000;

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean deliveryFinished = new AtomicBoolean();
    private final KVStore<StructuredKey, Object> store = new KVStore<>(router, new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    }, NotificationDelivery.dispatched("cjoin" + System.nanoTime(), (_, _) -> Unit.unit()));

    @AfterEach
    void releaseAndClose() {
        release.countDown();
        store.close();
    }

    /// Mutation: delete `thread.onPresent(this::join)` in `close()`, or only its `delivery.join(...)` call (`close()` returns
    /// while the handler is held, the window assertion is red).
    @Test
    void closeDoesNotReturnWhileADeliveryIsInFlight() throws Exception {
        router.addRoute(KVStoreNotification.ValuePut.class, _ -> holdDelivery());
        store.process(store.createBatch(List.of(new KVCommand.Put<>(new Key("a"), "a"))));
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("CONTROL: the delivery is in flight").isTrue();
        var closer = Thread.ofPlatform().name("closer").unstarted(store::close);

        closer.start();
        closer.join(CLOSE_RETURN_WINDOW_MILLIS);

        assertThat(deliveryFinished).as("CONTROL: the delivery is still held").isFalse();
        assertThat(closer.isAlive()).as("close() waits for the in-flight delivery").isTrue();

        release.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(closer.isAlive()).as("close() returns once the delivery ends").isFalse();
        assertThat(deliveryFinished).as("close() returned after the delivery completed").isTrue();
    }

    /// Holds the delivery thread until released; `close()`'s interrupt is swallowed, so only the join can make `close()` wait.
    private void holdDelivery() {
        entered.countDown();
        awaitRelease();
        deliveryFinished.set(true);
    }

    private void awaitRelease() {
        var interrupted = false;

        while (release.getCount() > 0) {
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
