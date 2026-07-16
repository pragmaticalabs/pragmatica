// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Thin typed facade over the subscribe side of pub/sub: it binds a [Topic] to the handler that
/// consumes the topic's facts, so a handler whose parameter type does not match the topic's
/// `payloadType` is a compile error rather than a silent non-delivery.
///
/// Because the topic and the handler are joined through the same `Topic<T>`, a publisher and a
/// subscriber that both reference the declared `Topic<SeatSold>` are type-checked against each
/// other by construction — the acceptance the topic string being declared in exactly one place
/// buys for free.
///
/// This is a value-level binding, not a new runtime dispatch path. The existing declarative
/// subscription path (a `@ResourceQualifier(type = Subscriber.class, ...)`-annotated slice method,
/// discovered by the slice processor and dispatched by the runtime through `SliceInvoker` by
/// `(artifact, methodName)`) remains the mechanism that actually routes a published fact to a
/// subscriber. A slice method delegates to [#deliver] to consume the fact with full type safety.
///
/// ```
/// var sub = TypedSubscriber.typedSubscriber(SEAT_SOLD, this::onSeatSold);
/// sub.deliver(seatSold);   // invokes onSeatSold(SeatSold)
/// ```
///
/// @param <T> the topic's payload type
public record TypedSubscriber<T>(Topic<T> topic, Fn1<Promise<Unit>, T> handler) {
    /// Binds a [Topic] to the handler that consumes its facts, yielding a subscriber that only
    /// accepts payloads of the topic's declared type.
    public static <T> TypedSubscriber<T> typedSubscriber(Topic<T> topic, Fn1<Promise<Unit>, T> handler) {
        return new TypedSubscriber<>(topic, handler);
    }

    /// Delivers a fact to the bound handler. The payload type is checked against the topic at
    /// compile time, so only a `T` the topic declares can be delivered.
    public Promise<Unit> deliver(T message) {
        return handler.apply(message);
    }
}
