// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

/// Carrier pairing a decoded durable-topic event with the [MessageContext] describing the delivery
/// it arrived on (durable-pubsub-spec §8).
///
/// It exists to keep the runtime dispatch path single-argument. That path routes a published fact by
/// `(artifact, methodName)` and carries the payload erased, so it has no place to put a second
/// argument. Rather than fork the path for context-taking subscribers, the runtime hands over one
/// value carrying both halves, and generated adapter code unpacks it and invokes the slice method's
/// real `(T event, MessageContext context)` shape. Subscribers declaring the one-argument shape keep
/// the untouched original path.
///
/// `event` is typed as `Object` for that same reason, and deliberately: the value is erased
/// everywhere it travels, and the generated adapter for a given subscriber is the single place that
/// knows the concrete payload type and casts to it. A type parameter here would have to be supplied
/// by a dispatch path that holds no type information to supply, which buys erased-cast safety at the
/// call site while making the carrier's own type a fiction. Hand-written code should reach for
/// [TypedSubscriber] and [Topic], where the payload type is genuinely known and checked.
///
/// Like [MessageContext], this is a carrier rather than a validated value object — it is assembled
/// from an envelope the runtime has already accepted.
///
/// @param event   the decoded payload, erased; the generated adapter casts it to the subscriber's
///                declared type
/// @param context delivery context for this event — see [MessageContext] for which of its fields are
///                stable identities and which are merely positional
public record ContextualEvent(Object event, MessageContext context) {
    /// Pairs a decoded payload with its delivery context. Called by generated adapter code, not
    /// normally by hand-written slices.
    public static ContextualEvent contextualEvent(Object event, MessageContext context) {
        return new ContextualEvent(event, context);
    }
}
