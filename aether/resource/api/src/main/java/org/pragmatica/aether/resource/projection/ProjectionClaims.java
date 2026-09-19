// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.aether.resource.projection.Projection.ClaimKey;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// Where a [Projection]'s idempotency claims live (durable-pubsub-spec §8).
///
/// The protocol mirrors `IdempotencyMethodInterceptor` — claim, run, finalize on success, release on
/// failure — with one addition the in-process interceptor does not need: a LEASE, because a claim held
/// in a shared store outlives the process that took it. A claim is in one of two states: PENDING
/// (an attempt holds it, until its lease expires) or DONE (the fold was applied).
///
/// **Why a claim step and not get/put.** A separate read and write can never make the
/// check-then-record PAIR atomic, whatever the atomicity of each call; two attempts that both read
/// before either writes both apply (#1243). [#claimIfAbsent] is the single step that decides.
///
/// **What an implementation must provide.** [#claimIfAbsent] is ONE indivisible step over a store
/// every instance of the projection reads — a consensus-KV compare-and-set, or a transactional row.
/// A per-process map meets it only for attempts inside that process. It is declared here rather than
/// reusing `CacheBackend` because `CacheBackend` offers only get/put, and because `resource-interceptors`
/// already depends on `resource-api`, so importing it the other way would close a dependency cycle.
///
/// **Claim retention is the deployment's choice and it bounds the guarantee.** A DONE claim that is
/// evicted re-admits the duplicate it was recording — the bound stated on [Projection].
public interface ProjectionClaims {
    /// What [#claimIfAbsent] found and did.
    enum ClaimOutcome {
        /// No claim, or a PENDING claim whose lease had expired: a PENDING claim with a fresh lease is
        /// now held by the caller, which must fold and then [#finalizeClaim] or [#releaseClaim].
        CLAIMED,
        /// The key was already applied; the caller must not fold.
        DONE,
        /// Another attempt holds a live PENDING claim; the caller must not fold now.
        IN_PROGRESS
    }

    /// In ONE indivisible step: absent or lease-expired PENDING → write PENDING expiring after `lease`,
    /// answer [ClaimOutcome#CLAIMED]; DONE → [ClaimOutcome#DONE]; live PENDING →
    /// [ClaimOutcome#IN_PROGRESS].
    Promise<ClaimOutcome> claimIfAbsent(ClaimKey key, TimeSpan lease);
    /// Mark the key DONE after a successful fold. Named `finalizeClaim`, not `finalize`, so it does not
    /// overload `Object#finalize`.
    Promise<Unit> finalizeClaim(ClaimKey key);
    /// Drop a PENDING claim after a failed fold, so a retry can claim at once instead of waiting out the
    /// lease. Must never remove a DONE claim.
    Promise<Unit> releaseClaim(ClaimKey key);
}
