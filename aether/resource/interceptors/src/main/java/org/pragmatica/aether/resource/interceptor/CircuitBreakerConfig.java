// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record CircuitBreakerConfig(int failureThreshold, TimeSpan resetTimeout, int testAttempts) {
    private static final CircuitBreakerConfig DEFAULTS = new CircuitBreakerConfig(5,
                                                                                                                  timeSpan(30).seconds(),
                                                                                                                  3);

    public static CircuitBreakerConfig circuitBreakerConfig() {
        return DEFAULTS;
    }

    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold) {
        return ensure(failureThreshold, Verify.Is::positive).map(threshold -> new CircuitBreakerConfig(threshold,
                                                                                                       timeSpan(30).seconds(),
                                                                                                       3));
    }

    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold,
                                                                    TimeSpan resetTimeout,
                                                                    int testAttempts) {
        var validThreshold = ensure(failureThreshold, Verify.Is::positive);
        var validTimeout = ensure(resetTimeout, Verify.Is::notNull);
        var validAttempts = ensure(testAttempts, Verify.Is::positive);
        return all(validThreshold, validTimeout, validAttempts).map(CircuitBreakerConfig::new);
    }
}
