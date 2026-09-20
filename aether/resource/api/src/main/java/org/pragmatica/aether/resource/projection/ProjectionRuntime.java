// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.lang.Result;


/// The runtime's side of a [Projection] (#1333): a slice-scoped resource, provisioned by type like a
/// durable entity, that ATTACHES a projection to the node hosting the slice.
///
/// Declared against the projection's TOPIC section of `resources.toml`:
/// ```java
/// @ResourceQualifier(type = ProjectionRuntime.class, config = "order-events")
/// public @interface OrderProjectionRuntime {}
///
/// static OrdersSlice ordersSlice(@OrderProjectionRuntime ProjectionRuntime runtime, ...) {
///     var projection = runtime.attach(Projection.of(ORDER_EVENTS).into(store, key).apply(fold)
///                                               .withClaims(claims, lease))
///                             .unwrap();
///     ...
/// }
/// ```
/// The section names the durable topic the projection folds — the same section its publisher and
/// subscriber use — so the runtime resolves the exact backing stream the way `PublisherFactory` does,
/// and an ephemeral topic (no stream, nothing to rewind) is refused at provisioning.
///
/// **What `attach` does.** It registers the projection with the node and returns the copy wired with the
/// node's `ReplayCursor` — the one that captures the group's real partition bounds and rewinds the
/// group's committed cursor under a fenced epoch. The slice must keep the RETURNED projection (record
/// semantics, as with `withClaims`): the argument still carries the refusing default cursor. Once
/// attached, the node reports the group's committed cursor to the projection after every commit
/// ([Projection#onCursorCommitted]), stamped with the epoch the committing consumer resumed under, and
/// the operator surface (`POST …/topics/{ns}/{topic}/{version}/groups/{group}/rebuild`,
/// `aether topics rebuild`) can drive [Projection#rebuild] on this node.
///
/// **Group identity.** The runtime's consumer group for a durable subscriber is
/// `artifactBase#method`, never the projection's name. [#attach(Projection)] infers the method: it
/// matches iff this slice has EXACTLY ONE durable subscriber on the topic; with two, every cursor report
/// is refused with one ERROR — attributing another group's cursor to the projection would skip replay
/// offsets it never applied. [#attach(Projection, String)] names the method and removes the ambiguity.
///
/// **Refusals** are synchronous: an attach is refused when the same slice already attached a projection
/// for the same subscriber, or when the projection's topic is not the one this runtime was provisioned
/// for. Both are declaration mistakes, so a slice fails to construct rather than running two projections
/// that report into each other.
public interface ProjectionRuntime {
    <S, T> Result<Projection<S, T>> attach(Projection<S, T> projection);
    <S, T> Result<Projection<S, T>> attach(Projection<S, T> projection, String subscriberMethod);
}
