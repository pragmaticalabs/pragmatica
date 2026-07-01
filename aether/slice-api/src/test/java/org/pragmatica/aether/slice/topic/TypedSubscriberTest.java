// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Unit tests for [TypedSubscriber]: [TypedSubscriber#deliver] invokes the bound handler with the
/// typed payload and returns its result unchanged (success and failure).
class TypedSubscriberTest {
    private static final Cause HANDLER_FAILED = Causes.cause("Handler failed");
    private static final Topic<SeatSold> SEAT_SOLD = Topic.of("seat-sold", SeatSold.class);

    record SeatSold(String seatId) {}

    @Test
    void deliver_invokesHandlerWithMessage() {
        var captured = new AtomicReference<SeatSold>();
        var subscriber = TypedSubscriber.typedSubscriber(SEAT_SOLD, capturingHandler(captured));

        subscriber.deliver(new SeatSold("A1"))
                  .await()
                  .onFailure(cause -> fail(cause.message()));

        assertThat(captured.get()).isEqualTo(new SeatSold("A1"));
    }

    @Test
    void deliver_returnsHandlerSuccess() {
        var subscriber = TypedSubscriber.typedSubscriber(SEAT_SOLD, capturingHandler(new AtomicReference<>()));

        var result = subscriber.deliver(new SeatSold("A1")).await();

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void deliver_propagatesHandlerFailure() {
        Fn1<Promise<Unit>, SeatSold> failing = _ -> HANDLER_FAILED.promise();
        var subscriber = TypedSubscriber.typedSubscriber(SEAT_SOLD, failing);

        subscriber.deliver(new SeatSold("A1"))
                  .await()
                  .onSuccess(_ -> fail("expected the handler's failure to propagate"));
    }

    @Test
    void topic_isRetained() {
        var subscriber = TypedSubscriber.typedSubscriber(SEAT_SOLD, capturingHandler(new AtomicReference<>()));

        assertThat(subscriber.topic()).isEqualTo(SEAT_SOLD);
    }

    private static Fn1<Promise<Unit>, SeatSold> capturingHandler(AtomicReference<SeatSold> sink) {
        return message -> capture(sink, message);
    }

    private static Promise<Unit> capture(AtomicReference<SeatSold> sink, SeatSold message) {
        sink.set(message);

        return Promise.unitPromise();
    }
}
