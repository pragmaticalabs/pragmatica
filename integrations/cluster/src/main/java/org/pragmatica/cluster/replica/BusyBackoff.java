// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.SplittableRandom;


/// The retry rule for a `BUSY` or `NOT_SERVING` answer: the other uplink first; if both refuse, wait `uniform(0, min(15 s, 1 s x 2^n))`
/// (full jitter), `n` the consecutive refusals, reset on success, and never before 250 ms. The jitter is seeded by node id and attempt, so a
/// synchronized restart does not re-synchronize its retries. `BUSY` is advisory, never an error.
public interface BusyBackoff {
    long MIN_DELAY_MILLIS = 250L;
    long BASE_MILLIS = 1_000L;
    long CAP_MILLIS = 15_000L;
    int MAX_SHIFT = 20;

    /// The wait before the next attempt after `consecutiveRefusals` refusals in a row (the first refusal is `1`).
    static long delayMillis(String nodeId, int consecutiveRefusals) {
        var ceiling = ceilingMillis(consecutiveRefusals);
        var jitter = new SplittableRandom(seed(nodeId, consecutiveRefusals)).nextLong(ceiling + 1);

        return Math.max(MIN_DELAY_MILLIS, jitter);
    }

    /// `min(15 s, 1 s x 2^n)` for `n` = refusals - 1, so the first refusal draws from `[0, 1 s]`.
    static long ceilingMillis(int consecutiveRefusals) {
        var shift = Math.min(MAX_SHIFT, Math.max(0, consecutiveRefusals - 1));

        return Math.min(CAP_MILLIS, BASE_MILLIS << shift);
    }

    private static long seed(String nodeId, int attempt) {
        return nodeId.hashCode() * 31L + attempt;
    }

    record unused() implements BusyBackoff {}
}
