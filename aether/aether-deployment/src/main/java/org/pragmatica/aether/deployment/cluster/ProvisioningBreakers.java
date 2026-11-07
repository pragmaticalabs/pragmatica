// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// The provisioning circuit breakers, one per source binding (spec §8.3, P-SRC2). A source whose provisions keep failing opens only its
/// own breaker: another source's replacements are not deferred by it. A breaker is a consecutive-failure count and the instant before
/// which the source is not asked again; it opens when the count reaches the cap and the instant is still ahead.
///
/// Each update of one source is a single atomic step on the map entry, so concurrent failures of one source count exactly. Sources are
/// keyed by their name. A refusal that is not a provider failure (admission refusal, unresolved source) is never counted here: it has its
/// own window ([#refuseUntil]), keyed by source or by [#UNRESOLVED], which can never be a source name (it is outside the name grammar).
final class ProvisioningBreakers {
    /// The key of the refusal window of a core replacement whose source could not be resolved.
    static final String UNRESOLVED = "(unresolved)";

    /// One source's breaker.
    record Breaker(int failures, long nextAllowedMs) {
        private static final Breaker CLOSED = new Breaker(0, 0L);

        Breaker failed(int cap, long nowMs, long backoffMs) {
            var count = failures + 1;

            return new Breaker(count,
                               count >= cap
                               ? nowMs + backoffMs
                               : nextAllowedMs);
        }

        boolean isOpen(int cap, long nowMs) {
            return failures >= cap && nowMs < nextAllowedMs;
        }

        boolean isTripped(int cap) {
            return failures >= cap;
        }
    }

    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> refusedUntil = new ConcurrentHashMap<>();

    /// A refusal that is not a provider failure (a capacity admission refusal of a source, an unresolved source) is paced by its own
    /// window, keyed like the breakers but never counted by them: the key is not asked again before `untilMs`.
    @Contract
    void refuseUntil(String key, long untilMs) {
        refusedUntil.put(key, untilMs);
    }

    /// Whether `key` is inside the window its last refusal armed.
    boolean isRefused(String key, long nowMs) {
        return nowMs < refusedUntil.getOrDefault(key, 0L);
    }

    /// Count a failure of `source`; once `cap` is reached the backoff window is armed. Returns the new consecutive count.
    @Contract
    int recordFailure(String source, int cap, long nowMs, long backoffMs) {
        return breakers.compute(source,
                                (_, current) -> failedFrom(current, cap, nowMs, backoffMs))
                       .failures();
    }

    private static Breaker failedFrom(Breaker current, int cap, long nowMs, long backoffMs) {
        return Option.option(current)
                     .or(Breaker.CLOSED)
                     .failed(cap, nowMs, backoffMs);
    }

    /// Whether `source` may not be asked now. A source with no recorded failure is never open.
    boolean isOpen(String source, int cap, long nowMs) {
        return of(source).isOpen(cap, nowMs);
    }

    Breaker of(String source) {
        return breakers.getOrDefault(source, Breaker.CLOSED);
    }

    /// Close `source`'s breaker; returns the consecutive count it held.
    @Contract
    int reset(String source) {
        refusedUntil.remove(source);

        return Option.option(breakers.remove(source))
                     .map(Breaker::failures)
                     .or(0);
    }

    /// Close every breaker; returns the sum of the counts they held.
    @Contract
    int resetAll() {
        refusedUntil.clear();

        return breakers.keySet()
                       .stream()
                       .mapToInt(this::reset)
                       .sum();
    }

    /// Every breaker that holds a failure, by source name, in name order.
    Map<String, Breaker> snapshot() {
        return new TreeMap<>(breakers);
    }
}
