// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import org.pragmatica.aether.resource.projection.Projection.ClaimKey;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;


/// Where a [Projection]'s idempotency claims live (durable-pubsub-spec §8).
///
/// The protocol mirrors `IdempotencyMethodInterceptor` — claim, run, finalize on success, release on
/// failure — with two additions the in-process interceptor does not need, because a claim held in a
/// shared store outlives the process that took it: a LEASE, and a FENCING TOKEN. A claim is in one of
/// two states: PENDING (an attempt holds it, until its lease expires) or DONE (the fold was applied).
///
/// **Why a claim step and not get/put.** A separate read and write can never make the
/// check-then-record PAIR atomic, whatever the atomicity of each call; two attempts that both read
/// before either writes both apply (#1243). [#claimIfAbsent] is the single step that decides.
///
/// **Why a token.** Once a lease expires, another attempt may reclaim the key. The token is the
/// interceptor's value-conditional `remove(key, sentinel)` carried into a shared store: [#finalizeClaim]
/// and [#releaseClaim] act only while the stored claim still carries the caller's token, so an expired
/// holder can neither finalize nor release its successor's claim.
///
/// **What an implementation must provide.** Each operation is ONE indivisible step over a store every
/// instance of the projection reads — a consensus-KV compare-and-set, or a transactional row. A
/// per-process map meets it only for attempts inside that process. It is declared here rather than
/// reusing `CacheBackend` because `CacheBackend` offers only get/put, and because `resource-interceptors`
/// already depends on `resource-api`, so importing it the other way would close a dependency cycle.
///
/// **Claim retention is the deployment's choice and it bounds the guarantee.** A DONE claim that is
/// evicted re-admits the duplicate it was recording — the bound stated on [Projection].
public interface ProjectionClaims {
    /// What [#claimIfAbsent] found and did.
    sealed interface ClaimOutcome {}

    /// No claim, or a PENDING claim whose lease had expired: a PENDING claim with a fresh lease and
    /// `token` is now held by the caller, which must fold and then [#finalizeClaim] or [#releaseClaim]
    /// with that token. `token` must be unique for the key ACROSS releases and evictions — a counter
    /// that outlives the claim record, or a globally unique value. A counter kept in the claim record
    /// restarts when a release drops the record and reissues an old token, letting an expired holder
    /// settle a claim that is not its own.
    record Claimed(long token) implements ClaimOutcome {}

    /// The claim already held on the key.
    enum Held implements ClaimOutcome {
        /// The key was already applied; the caller must not fold.
        DONE,
        /// Another attempt holds a live PENDING claim; the caller must not fold now.
        IN_PROGRESS
    }

    /// What [#finalizeClaim] or [#releaseClaim] did.
    enum Settlement {
        /// The stored claim carried the caller's token and was finalized or released.
        APPLIED,
        /// The stored claim no longer carries the caller's token — its lease expired and the key was
        /// reclaimed, or it is already gone. Nothing was changed.
        STALE
    }

    /// In ONE indivisible step: absent or lease-expired PENDING → write PENDING with a fresh token,
    /// expiring after `lease`, answer [Claimed]; DONE → [Held#DONE]; live PENDING →
    /// [Held#IN_PROGRESS]. `lease` is always positive: [Projection] refuses a non-positive lease
    /// before claiming ([Projection.ProjectionError.NonPositiveLease]). Expiry is judged against ONE
    /// clock every instance agrees on — the STORE's, applied when the step executes — never a caller's
    /// own wall clock, otherwise clock skew decides who may reclaim.
    Promise<ClaimOutcome> claimIfAbsent(ClaimKey key, TimeSpan lease);
    /// Mark the key DONE after a successful fold, only while the stored claim is PENDING with `token`;
    /// otherwise change nothing and answer [Settlement#STALE]. Named `finalizeClaim`, not `finalize`,
    /// so it does not overload `Object#finalize`.
    Promise<Settlement> finalizeClaim(ClaimKey key, long token);
    /// Drop the PENDING claim after a failed fold, so a retry can claim at once instead of waiting out
    /// the lease — only while the stored claim is PENDING with `token`; otherwise change nothing and
    /// answer [Settlement#STALE]. Never removes a DONE claim.
    Promise<Settlement> releaseClaim(ClaimKey key, long token);
}
