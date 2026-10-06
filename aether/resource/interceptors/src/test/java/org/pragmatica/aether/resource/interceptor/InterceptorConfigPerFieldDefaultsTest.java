// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;

import static org.assertj.core.api.Assertions.assertThat;

/// #822 — `resource-reference.md` promises these defaults, but the binder only reaches a record's `DEFAULT`
/// instance and a validating factory is called only when EVERY component is bound, so a section that omitted
/// them failed with a missing-field error the reference said could not happen. They are now `DEFAULT_<COMPONENT>`
/// constants the binder reads by name; the records keep no whole-record `DEFAULT`.
class InterceptorConfigPerFieldDefaultsTest {
    @Test
    void metrics_omittedBooleans_bindTheDocumentedDefaults() {
        var config = configServiceFrom("""
                [metrics.checkout]
                name = "checkout.process"
                """).config("metrics.checkout", MetricsConfig.class).unwrap();

        assertThat(config.recordTiming()).isTrue();
        assertThat(config.recordCounts()).isTrue();
        assertThat(config.tags()).isEmpty();
    }

    @Test
    void metrics_explicitValueBeatsTheDefault() {
        var config = configServiceFrom("""
                [metrics.checkout]
                name = "checkout.process"
                record_timing = false
                """).config("metrics.checkout", MetricsConfig.class).unwrap();

        assertThat(config.recordTiming()).isFalse();
        assertThat(config.recordCounts()).isTrue();
    }

    @Test
    void rateGuard_omittedFields_bindTheDocumentedDefaults() {
        var config = configServiceFrom("""
                [guard.api]
                requests_per_second = 5
                """).config("guard.api", RateGuardConfig.class).unwrap();

        assertThat(config.requestsPerSecond()).isEqualTo(5);
        assertThat(config.burst()).isEqualTo(20);
        assertThat(config.type()).isEqualTo("local");
    }

    @Test
    void rateGuard_everythingOmitted_bindsAllThreeDefaults() {
        var config = configServiceFrom("""
                [guard.api]
                unrelated = "x"
                """).config("guard.api", RateGuardConfig.class);

        assertThat(config.isSuccess()).isTrue();
        assertThat(config.unwrap().requestsPerSecond()).isEqualTo(100);
    }

    /// The control that no field lost its requirement: `max_requests` of the rate limit is documented required.
    @Test
    void rateLimit_omittedRequiredField_isStillReported() {
        var result = configServiceFrom("""
                [rl.api]
                window = "1s"
                """).config("rl.api", RateLimitConfig.class);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("rl.api.max_requests"));
    }

    private static ProviderBasedConfigService configServiceFrom(String toml) {
        var provider = ConfigurationProvider.builder()
                                            .withSource(TomlConfigSource.tomlConfigSource(toml).unwrap())
                                            .build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }
}
