// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Where a [Projection]'s idempotency claims live (durable-pubsub-spec §8).
///
/// Deliberately the same two operations `CacheBackend` exposes, so a deployment backs this with the
/// same store §8 names and the aspect's semantics carry over unchanged. It is declared HERE rather
/// than reusing `CacheBackend` directly for a structural reason, not a design one:
/// `resource-interceptors` (where `CacheBackend` lives) already depends on `resource-api` (where
/// [Projection] lives), so importing it the other way would close a dependency cycle. The adapter is
/// one lambda at the wiring site, which is the only place that legitimately sees both.
///
/// **Claim retention is the deployment's choice and it bounds the guarantee.** A claim that is
/// evicted re-admits the duplicate it was recording — exception (ii) of the bound stated on
/// [Projection]. Nothing here can compensate for that; it is why the bound is stated rather than
/// hidden behind the word "idempotent".
public interface ProjectionClaims {
    /// Present iff this exact `(projectionName, generation, messageId)` has already been applied.
    Promise<Option<Object>> get(Object key);
    /// Record that the key has been applied. Called AFTER a successful fold — see [Projection]'s
    /// ordering note for why recording follows the write rather than preceding it.
    Promise<Unit> put(Object key, Object value);
}
