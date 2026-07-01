// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.pragmatica.lang.type.TypeToken;


/// Pure, reflection-free descriptor of a pub/sub topic: its declared `name` plus the `payloadType`
/// of the facts published on it. It is the single place the topic string is declared, so a typo in
/// the name lives in exactly one location instead of being repeated across every publisher and
/// subscriber declaration.
///
/// The payload type is carried as a [TypeToken] rather than a raw `Class` so a topic can describe a
/// generic payload (`Topic<Event<SeatSold>>`) — matching how the runtime pub/sub path already
/// carries payload types through `SliceInvoker` and `TopicPublisher`.
///
/// `Topic<T>` is the type-safety anchor for the typed pub/sub facade: [TypedPublisher] and
/// [TypedSubscriber] both bind to the same `Topic<T>`, so publishing or handling a payload of the
/// wrong type is a compile error rather than a silent non-delivery. The descriptor carries no
/// Aether runtime dependency — only [TypeToken] from Pragmatica Core — so any module can declare a
/// topic without pulling in the invoke/deployment machinery.
///
/// Declared once, reused everywhere:
/// ```
/// static final Topic<SeatSold> SEAT_SOLD = Topic.of("seat-sold", SeatSold.class);
/// ```
///
/// Like [org.pragmatica.aether.slice.repr.PgRepr], this is a descriptor, not a validated value
/// object: [#of] returns the topic directly. A blank name is a declaration-time programming error,
/// not a runtime failure to be recovered from.
///
/// @param <T> the fact/event payload type published on this topic
public record Topic<T>(String name, TypeToken<T> payloadType) {
    /// Declares a topic from its name and a captured generic payload type. Use the [TypeToken]
    /// overload when the payload is itself generic (`new TypeToken<Event<SeatSold>>() {}`).
    public static <T> Topic<T> of(String name, TypeToken<T> payloadType) {
        return new Topic<>(name, payloadType);
    }

    /// Declares a topic from its name and a non-generic payload class — the common case
    /// (`Topic.of("seat-sold", SeatSold.class)`).
    public static <T> Topic<T> of(String name, Class<T> payloadType) {
        return new Topic<>(name, TypeToken.typeToken(payloadType));
    }
}
