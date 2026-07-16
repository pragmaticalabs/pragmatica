// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.function.Supplier;

import org.pragmatica.aether.slice.RateGuard;
import org.pragmatica.aether.slice.RateGuardError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.RateLimiter;


record DefaultRateGuard(RateLimiter limiter, int limit) implements RateGuard {
    static DefaultRateGuard defaultRateGuard(RateGuardConfig config) {
        var limiter = RateLimiter.builder()
                                 .rate(config.requestsPerSecond())
                                 .period(config.window())
                                 .burst(config.burst())
                                 .withDefaultTimeSource();

        return new DefaultRateGuard(limiter, config.requestsPerSecond());
    }

    @Override
    public <T> Promise<T> guard(Supplier<Promise<T>> operation) {
        if (limiter.tryAcquire()) {
            return operation.get();
        }

        var retryAfterMs = limiter.retryAfter().millis();
        var resetAtMs = System.currentTimeMillis() + retryAfterMs;

        return Promise.failure(RateGuardError.LimitExceeded.limitExceeded(retryAfterMs, limit, 0, resetAtMs));
    }
}
