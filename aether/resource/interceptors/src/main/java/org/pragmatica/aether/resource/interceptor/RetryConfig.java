// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record RetryConfig(int maxAttempts, BackoffStrategy backoffStrategy) {
    public static Result<RetryConfig> retryConfig(int maxAttempts) {
        return ensure(maxAttempts, Verify.Is::positive).map(RetryConfig::withExponentialBackoff);
    }

    public static Result<RetryConfig> retryConfig(int maxAttempts, TimeSpan interval) {
        return ensure(maxAttempts, Verify.Is::positive).map(attempts -> withFixedBackoff(attempts, interval));
    }

    public static Result<RetryConfig> retryConfig(int maxAttempts, BackoffStrategy backoffStrategy) {
        var validAttempts = ensure(maxAttempts, Verify.Is::positive);
        var validStrategy = ensure(backoffStrategy, Verify.Is::notNull);

        return all(validAttempts, validStrategy).map(RetryConfig::new);
    }

    @SuppressWarnings("JBCT-NAM-01")
    private static RetryConfig withExponentialBackoff(int attempts) {
        var strategy = BackoffStrategy.exponential().initialDelay(timeSpan(100).millis()).maxDelay(timeSpan(10).seconds()).factor(2.0).withoutJitter();

        return new RetryConfig(attempts, strategy);
    }

    @SuppressWarnings("JBCT-NAM-01")
    private static RetryConfig withFixedBackoff(int attempts, TimeSpan interval) {
        return new RetryConfig(attempts,
                               BackoffStrategy.fixed().interval(interval));
    }
}
