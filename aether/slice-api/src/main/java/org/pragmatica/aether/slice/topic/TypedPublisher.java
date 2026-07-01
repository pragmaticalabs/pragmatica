// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Thin typed facade over the existing string-keyed publish machinery: it binds a [Topic] to a
/// runtime [Publisher] (in production the `TopicPublisher` provisioned for the topic's name) and
/// forwards every publish to it unchanged.
///
/// The facade adds no delivery behaviour of its own — it is a `Publisher<T>` itself, so it drops
/// into any code that already accepts the erased `Publisher`, keeping the existing pub/sub path
/// working. What it adds is compile-time safety: because the delegate is obtained through
/// `Topic<T>`, publishing a payload whose type does not match the topic's `payloadType` is a
/// compile error rather than a silent non-delivery.
///
/// ```
/// var pub = TypedPublisher.typedPublisher(SEAT_SOLD, injectedPublisher);
/// pub.publish(new SeatSold(seatId));   // OK
/// pub.publish(new OrderPlaced(...));   // does not compile
/// ```
///
/// @param <T> the topic's payload type
public record TypedPublisher<T>(Topic<T> topic, Publisher<T> delegate) implements Publisher<T> {
    /// Binds a [Topic] to the runtime [Publisher] provisioned for it, yielding a publisher that
    /// only accepts payloads of the topic's declared type.
    public static <T> TypedPublisher<T> typedPublisher(Topic<T> topic, Publisher<T> delegate) {
        return new TypedPublisher<>(topic, delegate);
    }

    @Override
    public Promise<Unit> publish(T message) {
        return delegate.publish(message);
    }
}
