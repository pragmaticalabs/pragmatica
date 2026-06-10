// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.CircuitBreaker;


public final class CircuitBreakerInterceptorFactory implements ResourceFactory<CircuitBreakerMethodInterceptor, CircuitBreakerConfig> {
    @Override
    public Class<CircuitBreakerMethodInterceptor> resourceType() {
        return CircuitBreakerMethodInterceptor.class;
    }

    @Override
    public Class<CircuitBreakerConfig> configType() {
        return CircuitBreakerConfig.class;
    }

    @Override
    public Promise<CircuitBreakerMethodInterceptor> provision(CircuitBreakerConfig config) {
        return Promise.success(interceptor(config));
    }

    private static CircuitBreakerMethodInterceptor interceptor(CircuitBreakerConfig config) {
        var breaker = CircuitBreaker.builder().failureThreshold(config.failureThreshold()).resetTimeout(config.resetTimeout()).testAttempts(config.testAttempts()).withDefaultShouldTrip().withDefaultTimeSource();

        return new CircuitBreakerMethodInterceptor(breaker);
    }
}
