// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.RateLimiter;


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
        return interceptor(config).async();
    }

    /// #1316: a configuration the limiter cannot represent fails provisioning with its typed cause.
    private static Result<RateLimitMethodInterceptor> interceptor(RateLimitConfig config) {
        return RateLimiter.builder()
                          .rate(config.maxRequests())
                          .period(config.window())
                          .burst(config.burst())
                          .withDefaultTimeSource()
                          .map(RateLimitMethodInterceptor::new);
    }
}
