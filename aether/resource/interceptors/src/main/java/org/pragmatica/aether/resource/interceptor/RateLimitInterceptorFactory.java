// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.RateLimiter;

import static org.pragmatica.aether.resource.interceptor.RateLimitMethodInterceptor.rateLimitMethodInterceptor;


public final class RateLimitInterceptorFactory implements ResourceFactory<RateLimitMethodInterceptor, RateLimitConfig> {
    @Override
    public Class<RateLimitMethodInterceptor> resourceType() {
        return RateLimitMethodInterceptor.class;
    }

    @Override
    public Class<RateLimitConfig> configType() {
        return RateLimitConfig.class;
    }

    @Override
    public Promise<RateLimitMethodInterceptor> provision(RateLimitConfig config) {
        // Keep the explicit lambda: the factory is overloaded, so `::rateLimitMethodInterceptor` is an inexact reference
        // and ambiguous between Result#map(Fn1) and Result#map(Supplier).
        return limiter(config).map(limiter -> rateLimitMethodInterceptor(limiter))
                      .async();
    }

    /// Meters are bound when the context carries a `MeterRegistry`, the slice id and the section (#280 R27). Any of
    /// them missing provisions the limiter without meters: a node or config that never had a registry keeps working,
    /// unlike [MetricsInterceptorFactory], whose whole purpose is the registry.
    @Override
    public Promise<RateLimitMethodInterceptor> provision(RateLimitConfig config, ProvisioningContext context) {
        return limiter(config).map(limiter -> rateLimitMethodInterceptor(limiter, context))
                      .async();
    }

    /// Unload removes the limiter's meters, so a redeploy leaves its meters bound to the live limiter only.
    @Override
    public Promise<Unit> close(RateLimitMethodInterceptor resource) {
        return Promise.success(resource.release());
    }

    /// #1316: a configuration the limiter cannot represent fails provisioning with its typed cause.
    private static Result<RateLimiter> limiter(RateLimitConfig config) {
        return RateLimiter.builder()
                          .rate(config.maxRequests())
                          .period(config.window())
                          .burst(config.burst())
                          .withDefaultTimeSource();
    }
}
