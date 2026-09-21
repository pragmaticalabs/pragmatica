// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.aether.resource.projection.Projection.ClaimKey;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;


/// [ProjectionClaims] held in this process (#1333): the §8 claim protocol — lease, fencing token,
/// PENDING/DONE — with every step under one monitor, which is "one indivisible step" for attempts
/// inside this JVM and nothing beyond it. Expiry is judged on this process's monotonic clock. Same
/// scope statement as [InMemoryProjectionStore]: coherent for a single-assignee projection and for
/// tests; not a shared backing, and a DONE claim does not survive the process.
public final class InMemoryProjectionClaims implements ProjectionClaims {
    private record Claim(boolean done, long expiresAtNanos, long token) {}

    private final Map<ClaimKey, Claim> claims = new HashMap<>();
    /// Outlives every claim record, so a released key never reissues an earlier token.
    private long tokens;

    private InMemoryProjectionClaims() {}

    public static InMemoryProjectionClaims inMemoryProjectionClaims() {
        return new InMemoryProjectionClaims();
    }

    @Override
    public synchronized Promise<ClaimOutcome> claimIfAbsent(ClaimKey key, TimeSpan lease) {
        var now = System.nanoTime();
        var live = Option.option(claims.get(key)).filter(claim -> claim.done() || claim.expiresAtNanos() - now > 0);

        return Promise.success(live.map(InMemoryProjectionClaims::outcomeOf).or(() -> claim(key, now + lease.nanos())));
    }

    private ClaimOutcome claim(ClaimKey key, long expiresAtNanos) {
        var token = ++tokens;

        claims.put(key, new Claim(false, expiresAtNanos, token));

        return new Claimed(token);
    }

    private static ClaimOutcome outcomeOf(Claim claim) {
        return claim.done()
               ? Held.DONE
               : Held.IN_PROGRESS;
    }

    @Override
    public synchronized Promise<Settlement> finalizeClaim(ClaimKey key, long token) {
        return settle(key, token, Option.some(new Claim(true, Long.MAX_VALUE, token)));
    }

    @Override
    public synchronized Promise<Settlement> releaseClaim(ClaimKey key, long token) {
        return settle(key, token, Option.none());
    }

    /// Replace the claim with `next` (absent removes it) only while it is PENDING with `token`.
    private Promise<Settlement> settle(ClaimKey key, long token, Option<Claim> next) {
        var held = Option.option(claims.get(key)).filter(claim -> !claim.done() && claim.token() == token);

        if (held.isEmpty()) {
            return Promise.success(Settlement.STALE);
        }

        next.onPresent(claim -> claims.put(key, claim)).onEmpty(() -> claims.remove(key));

        return Promise.success(Settlement.APPLIED);
    }
}
