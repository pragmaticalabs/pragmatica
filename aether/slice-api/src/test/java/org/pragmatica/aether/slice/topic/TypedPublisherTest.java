// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Unit tests for [TypedPublisher]: it is a faithful thin facade — every publish forwards to the
/// wrapped [Publisher] unchanged (success and failure), and it remains usable anywhere the erased
/// `Publisher` is expected, so the existing string-keyed publish path keeps working.
class TypedPublisherTest {
    private static final Cause PUBLISH_FAILED = Causes.cause("Publish failed");
    private static final Topic<SeatSold> SEAT_SOLD = Topic.of("seat-sold", SeatSold.class);

    record SeatSold(String seatId) {}

    @Test
    void publish_delegatesMessageToWrappedPublisher() {
        var captured = new AtomicReference<SeatSold>();
        var publisher = TypedPublisher.typedPublisher(SEAT_SOLD, capturingPublisher(captured));

        publisher.publish(new SeatSold("A1"))
                 .await()
                 .onFailure(cause -> fail(cause.message()));

        assertThat(captured.get()).isEqualTo(new SeatSold("A1"));
    }

    @Test
    void publish_returnsDelegateSuccess() {
        var publisher = TypedPublisher.typedPublisher(SEAT_SOLD, capturingPublisher(new AtomicReference<>()));

        var result = publisher.publish(new SeatSold("A1")).await();

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void publish_propagatesDelegateFailure() {
        Publisher<SeatSold> failing = _ -> PUBLISH_FAILED.promise();
        var publisher = TypedPublisher.typedPublisher(SEAT_SOLD, failing);

        publisher.publish(new SeatSold("A1"))
                 .await()
                 .onSuccess(_ -> fail("expected the wrapped publisher's failure to propagate"));
    }

    @Test
    void typedPublisher_isUsableAsPlainPublisher() {
        // Backward compat / interop: the facade IS-A Publisher, so it drops into any code path
        // that already accepts the erased Publisher provisioned by the existing @ResourceQualifier form.
        Publisher<SeatSold> asPublisher = TypedPublisher.typedPublisher(SEAT_SOLD, capturingPublisher(new AtomicReference<>()));

        assertThat(asPublisher).isNotNull();
    }

    @Test
    void topic_isRetained() {
        var publisher = TypedPublisher.typedPublisher(SEAT_SOLD, capturingPublisher(new AtomicReference<>()));

        assertThat(publisher.topic()).isEqualTo(SEAT_SOLD);
    }

    private static Publisher<SeatSold> capturingPublisher(AtomicReference<SeatSold> sink) {
        return message -> capture(sink, message);
    }

    private static Promise<Unit> capture(AtomicReference<SeatSold> sink, SeatSold message) {
        sink.set(message);

        return Promise.unitPromise();
    }
}
