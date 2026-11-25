// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.CircuitBreaker;
import org.pragmatica.lang.utils.CircuitBreaker.StateListener;
import org.pragmatica.lang.utils.TimeSource;

import static org.pragmatica.aether.resource.interceptor.CircuitBreakerMethodInterceptor.circuitBreakerMethodInterceptor;


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
        return Promise.success(circuitBreakerMethodInterceptor(breaker(config, StateListener.none())));
    }

    /// Meters are bound when the context carries a `MeterRegistry`, the slice id and the section (#280 R27). Any of
    /// them missing provisions the breaker without meters: a node or config that never had a registry keeps working,
    /// unlike [MetricsInterceptorFactory], whose whole purpose is the registry.
    @Override
    public Promise<CircuitBreakerMethodInterceptor> provision(CircuitBreakerConfig config,
                                                              ProvisioningContext context) {
        return Promise.success(circuitBreakerMethodInterceptor(listener -> breaker(config, listener), context));
    }

    /// Unload removes the breaker's meters, so a redeploy leaves one gauge bound to the live breaker.
    @Override
    public Promise<Unit> close(CircuitBreakerMethodInterceptor resource) {
        return Promise.success(resource.release());
    }

    private static CircuitBreaker breaker(CircuitBreakerConfig config, StateListener listener) {
        return CircuitBreaker.builder()
                             .failureThreshold(config.failureThreshold())
                             .resetTimeout(config.resetTimeout())
                             .testAttempts(config.testAttempts())
                             .withDefaultShouldTrip()
                             .timeSource(TimeSource.system(),
                                         listener);
    }
}
