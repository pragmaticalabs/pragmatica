// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.FatalEscape;
import org.pragmatica.lang.FatalEscapeHandler;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// D1 exit (q'), arm (a) of the OQ-1 ruling (design §5, §8.1): an `Error` thrown from an `onSuccess` handler of a
/// parked apply promise is contained by `Promise` (logged, the dependent fails), so neither the store's task wrapper
/// nor the dispatcher ever sees it. The core fatal-escape hook is the only place that does, and [FatalEscapeRelay]
/// carries it from there to the node's failure sink. Each arm installs the relay on a recording sink, parks an apply
/// promise and throws from its `onSuccess`; the sink must be asked to stop the node, with the thrown `Error`.
///
/// Mutations, each RED through this class: the relay's handler is a no-op (hook "not installed", both arms red);
/// the relay forwards only a `VirtualMachineError` (the `AssertionError` arm red); the delivery hold is not armed (the
/// promise resolves inline on the caller, and every arm fails at "the delivery loop reached the hold"). The control arm
/// pins that a `RuntimeException` never reaches the sink.
class KVStoreFatalEscapeTest {
    record Key(String value) implements StructuredKey {}

    static final Key A = new Key("a");

    /// Records every stop request with its reason and cause.
    static final class RecordingSink implements DispatcherFailureSink {
        final List<String> reasons = new CopyOnWriteArrayList<>();
        final List<Throwable> causes = new CopyOnWriteArrayList<>();
        final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public Unit stopNode(String reason, Throwable cause) {
            reasons.add(reason);
            causes.add(cause);
            stopped.countDown();
            return Unit.unit();
        }
    }

    final MessageRouter.MutableRouter router = MessageRouter.mutable();
    final RecordingSink sink = new RecordingSink();
    final HeldDelivery hold = HeldDelivery.heldDelivery();
    final List<KVStore<StructuredKey, Object>> opened = new ArrayList<>();
    FatalEscapeHandler.Registration registration;

    @BeforeEach
    void installRelay() {
        registration = Promise.installFatalEscapeHandler(FatalEscapeRelay.fatalEscapeRelay(DispatcherFailureSink.once(sink)))
                              .onFailure(cause -> fail("install must succeed: " + cause.message()))
                              .unwrap();
    }

    @AfterEach
    void tearDown() {
        registration.uninstall();
        opened.forEach(KVStore::close);
    }

    KVStore<StructuredKey, Object> store() {
        var delivery = NotificationDelivery.dispatched("fatal-escape-" + System.nanoTime(),
                                                       NotificationDelivery.ReplayRank.flat(),
                                                       DispatcherFailureSink.loggingOnly(),
                                                       hold);
        var store = new KVStore<StructuredKey, Object>(router, new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) { return null; }
        }, delivery);

        opened.add(store);
        return store;
    }

    /// Parks an apply promise for real: the delivery loop is held before the batch, so the promise cannot resolve inline
    /// on the caller. The handler is attached while the promise is provably unresolved, then the hold is released and
    /// the handler runs on the delivery thread.
    private void parkThenRun(Consumer<List<Object>> onSuccess) {
        var store = store();
        var promise = Promise.<List<Object>>promise();

        hold.holdFrom(1);

        var results = store.process(store.createBatch(List.of(new KVCommand.Put<>(A, "one"))));

        store.completeAfterDelivery(promise, results);
        assertThat(hold.awaitHeld(10)).as("the delivery loop reached the hold").isTrue();
        assertThat(promise.isResolved()).as("the apply promise is parked, not resolved inline").isFalse();
        promise.onSuccess(onSuccess);
        hold.release();
    }

    @Test
    @Timeout(value = 30, unit = SECONDS)
    void assertionErrorFromAnOnSuccessHandler_stopsTheNodeThroughTheSink() throws Exception {
        var error = new AssertionError("onSuccess handler");

        parkThenRun(_ -> { throw error; });

        assertThat(sink.stopped.await(10, TimeUnit.SECONDS)).as("the sink was asked to stop the node").isTrue();
        assertThat(sink.reasons).containsExactly(FatalEscapeRelay.REASON);
        assertThat(sink.causes).containsExactly(error);
    }

    @Test
    @Timeout(value = 30, unit = SECONDS)
    void stackOverflowErrorFromAnOnSuccessHandler_stopsTheNodeThroughTheSink() throws Exception {
        var error = new StackOverflowError("onSuccess handler");

        parkThenRun(_ -> { throw error; });

        assertThat(sink.stopped.await(10, TimeUnit.SECONDS)).as("the sink was asked to stop the node").isTrue();
        assertThat(sink.causes).containsExactly(error);
    }

    /// Control: a `RuntimeException` is a contained failure, not a fatal escape, so the sink stays untouched and the
    /// parked promise still resolved (the probe reached the handler).
    @Test
    @Timeout(value = 30, unit = SECONDS)
    void runtimeExceptionFromAnOnSuccessHandler_neverReachesTheSink() throws Exception {
        var handlerRan = new CountDownLatch(1);

        parkThenRun(_ -> raise(handlerRan));

        assertThat(handlerRan.await(10, TimeUnit.SECONDS)).as("control: the handler ran").isTrue();
        assertThat(sink.stopped.await(500, TimeUnit.MILLISECONDS)).as("no stop for a RuntimeException").isFalse();
    }

    private static void raise(CountDownLatch handlerRan) {
        handlerRan.countDown();
        throw new IllegalStateException("contained");
    }

    /// The escaping thread parks until the sink returns: while the sink is held `onFatalEscape` must not return (kills
    /// fe03, a zero park bound, and fe04, no park), and once the sink returns it must return soon, far inside
    /// [FatalEscapeRelay#PARK_BOUND_MILLIS] (kills fe02, a latch never counted down, which waits out the bound).
    @Test
    @Timeout(value = 30, unit = SECONDS)
    void escapingThreadParksUntilTheSinkReturnsAndIsThenReleasedPromptly() throws Exception {
        var sinkEntered = new CountDownLatch(1);
        var releaseSink = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var relay = FatalEscapeRelay.fatalEscapeRelay(blockingSink(sinkEntered, releaseSink));
        var escaping = Thread.ofPlatform()
                             .daemon()
                             .start(() -> escape(relay, returned));

        assertThat(sinkEntered.await(10, TimeUnit.SECONDS)).as("the relay thread is inside the sink").isTrue();
        assertThat(returned.await(500, TimeUnit.MILLISECONDS)).as("parked while the sink is held").isFalse();
        releaseSink.countDown();
        assertThat(returned.await(FatalEscapeRelay.PARK_BOUND_MILLIS / 3, TimeUnit.MILLISECONDS))
            .as("released soon after the sink returned, well under the park bound").isTrue();
        escaping.join(10_000);
    }

    private static DispatcherFailureSink blockingSink(CountDownLatch entered, CountDownLatch release) {
        return (reason, cause) -> block(entered, release);
    }

    private static Unit block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        await(release);

        return Unit.unit();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void escape(FatalEscapeHandler relay, CountDownLatch returned) {
        relay.onFatalEscape(new FatalEscape("continuation", new AssertionError("parked"), "origin", "thread"));
        returned.countDown();
    }
}
