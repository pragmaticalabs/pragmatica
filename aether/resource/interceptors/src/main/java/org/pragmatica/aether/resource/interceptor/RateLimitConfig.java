// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// One `[rate-limit.<name>]` section. SCOPE: the limiter is per node and per provision. Each node hosting the slice
/// keeps its own token bucket, so the limit is per node: a cluster of N nodes hosting the slice admits up to
/// `max_requests x N` calls per window cluster-wide (burst likewise, `burst x N`).
///
/// METERS (#280 R27), registered when the node has a metrics registry and the slice is attributed, tagged `slice` and
/// `section` (a second section of the same slice has its own series; nothing is tagged per method, since one bucket
/// serves every method naming the section):
/// - `aether.interceptor.rate_limit.admitted` counter: calls the limiter let through.
/// - `aether.interceptor.rate_limit.rejected` counter: calls the limiter refused; the method did not run.
///
/// The meters are removed when the slice unloads.
public record RateLimitConfig(int maxRequests, TimeSpan window, int burst) {
    public static Result<RateLimitConfig> rateLimitConfig(int maxRequests, TimeSpan window) {
        var validRequests = ensure(maxRequests, Verify.Is::positive);
        var validWindow = ensure(window, Verify.Is::notNull);

        return all(validRequests, validWindow).map((r, w) -> new RateLimitConfig(r, w, 0));
    }

    public static Result<RateLimitConfig> rateLimitConfig(int maxRequests, TimeSpan window, int burst) {
        var validRequests = ensure(maxRequests, Verify.Is::positive);
        var validWindow = ensure(window, Verify.Is::notNull);
        var validBurst = ensure(burst, Verify.Is::nonNegative);

        return all(validRequests, validWindow, validBurst).map(RateLimitConfig::new);
    }

    public static Result<RateLimitConfig> rateLimitConfig() {
        return rateLimitConfig(100, timeSpan(1).minutes());
    }
}
