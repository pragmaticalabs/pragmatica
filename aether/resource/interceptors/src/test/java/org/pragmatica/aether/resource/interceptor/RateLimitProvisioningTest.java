// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.RateLimiter.RateLimiterError.InvalidConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1316 — both interceptor configurations validate only sign and presence, so a value the core limiter
/// cannot represent passed them and was truncated into the packed 16-bit token count. Provisioning now
/// carries the limiter's typed refusal instead. A config at the boundary (65535) still provisions.
class RateLimitProvisioningTest {
    @Test
    void rateLimitInterceptor_refusesAnUnrepresentableCapacity_withTheTypedCause() {
        var config = RateLimitConfig.rateLimitConfig(65_000, timeSpan(1).seconds(), 536).unwrap();
        var provisioned = new RateLimitInterceptorFactory().provision(config).await();

        assertThat(provisioned.isFailure()).as("capacity 65536 passes the config and must fail provisioning: %s", provisioned).isTrue();
        provisioned.onFailure(cause -> assertThat(cause).isInstanceOf(InvalidConfiguration.class));
    }

    @Test
    void rateLimitInterceptor_provisionsTheBoundaryCapacity() {
        var config = RateLimitConfig.rateLimitConfig(65_000, timeSpan(1).seconds(), 535).unwrap();

        assertThat(new RateLimitInterceptorFactory().provision(config).await().isSuccess()).isTrue();
    }

    @Test
    void rateGuard_refusesAnUnrepresentableCapacity_withTheTypedCause() {
        var config = RateGuardConfig.rateGuardConfig(70_000, 0).unwrap();
        var provisioned = new RateGuardFactory().provision(config).await();

        assertThat(provisioned.isFailure()).as("capacity 70000 passes the config and must fail provisioning: %s", provisioned).isTrue();
        provisioned.onFailure(cause -> assertThat(cause).isInstanceOf(InvalidConfiguration.class));
    }
}
