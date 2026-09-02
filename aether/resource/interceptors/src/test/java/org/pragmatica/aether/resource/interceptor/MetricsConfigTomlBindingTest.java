// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Promise;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #278 end-to-end proof that the REAL [MetricsConfig] (not [ProviderBasedConfigServiceTest]'s
/// structurally-identical `TagsConfig` local double) binds correctly through the actual TOML ->
/// [ProviderBasedConfigService] -> [MetricsInterceptorFactory] -> [MetricsMethodInterceptor] path,
/// recording into the [MeterRegistry] resolved from [ProvisioningContext] rather than a
/// record-carried field. Closes the "TOML-binding test per interceptor (currently none)" gap named
/// in #278's own acceptance text for the Metrics side, mirroring [RetryConfigTomlBindingTest].
///
/// `metricsInterceptorFactory_provisionsFromRealTomlBoundConfig_andRecordsIntoResolvedRegistry`
/// caught a real defect on first write: `MetricsMethodInterceptor` was passing bare tag names
/// straight into Micrometer's `MeterRegistry#timer(String, String...)`, which requires an
/// alternating key/value array and threw `IllegalArgumentException: size must be even` at the
/// first non-empty `tags` list — proof the config-service-layer mechanism tests (which never touch
/// the real Micrometer call) could not have caught this. Fixed by treating each `tags()` entry as a
/// `"key=value"` pair, parsed once by `MetricsInterceptorFactory#parseTags` at provisioning time
/// (a `Result`, not a thrown exception, per this project's no-business-exceptions convention) —
/// see `metricsInterceptorFactory_failsProvisioning_whenTagMissingEqualsSign`. `MetricsConfig`'s
/// header comment now documents the format.
class MetricsConfigTomlBindingTest {
    @Test
    void config_bindsExplicitFields_includingCommaJoinedKeyValueTags() {
        var configService = configServiceFrom("""
                [metrics.checkout]
                name = "checkout.process"
                record_timing = true
                record_counts = false
                tags = "region=eu,tier=gold"
                """);

        var config = configService.config("metrics.checkout", MetricsConfig.class).unwrap();

        assertThat(config.name()).isEqualTo("checkout.process");
        assertThat(config.recordTiming()).isTrue();
        assertThat(config.recordCounts()).isFalse();
        assertThat(config.tags()).containsExactly("region=eu", "tier=gold");
    }

    /// `name` (String) is derived from the section's trailing segment when absent
    /// (`ProviderBasedConfigService#deriveNameFromSectionSuffix`) - the same convention the
    /// resource-reference.md Metrics section documents. `tags` defaults to an empty list when
    /// absent, independent of any DEFAULT field ([MetricsConfig] deliberately has none per
    /// `InterceptorConfigDefaultAllowlistTest#assertNoPublicDefaultField`).
    @Test
    void config_derivesNameFromSectionSuffix_andDefaultsTagsToEmpty_whenBothOmitted() {
        var configService = configServiceFrom("""
                [metrics.account.getBalance]
                record_timing = true
                record_counts = true
                """);

        var config = configService.config("metrics.account.getBalance", MetricsConfig.class).unwrap();

        assertThat(config.name()).isEqualTo("getBalance");
        assertThat(config.tags()).isEmpty();
    }

    /// Mirrors `RetryConfigTomlBindingTest#config_whollyAbsentSection_failsLoud_doesNotFallBackToDefault`:
    /// [MetricsConfig] has no DEFAULT field at all, so there is nothing to fall back to even in
    /// principle - a wholly absent `[metrics.*]` section fails at the `hasSection` gate exactly like
    /// CacheConfig/IdempotencyConfig.
    @Test
    void config_whollyAbsentSection_failsLoud() {
        var configService = configServiceFrom("""
                [other]
                key = "value"
                """);

        var result = configService.config("metrics.checkout", MetricsConfig.class);

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void metricsInterceptorFactory_provisionsFromRealTomlBoundConfig_andRecordsIntoResolvedRegistry() {
        var configService = configServiceFrom("""
                [metrics.checkout]
                name = "checkout.process"
                record_timing = true
                record_counts = true
                tags = "region=eu"
                """);
        var config = configService.config("metrics.checkout", MetricsConfig.class).unwrap();
        var registry = new SimpleMeterRegistry();
        var context = ProvisioningContext.provisioningContext().withExtension(MeterRegistry.class, registry);

        var interceptor = new MetricsInterceptorFactory().provision(config, context)
                                                         .await()
                                                         .onFailureRun(() -> fail("Expected interceptor provisioning to succeed"))
                                                         .unwrap();

        var intercepted = interceptor.intercept((Integer request) -> Promise.success("ok"));
        var outcome = intercepted.apply(1).await();

        assertThat(outcome.isSuccess()).isTrue();
        var timer = registry.find("checkout.process.success").tag("region", "eu").timer();
        assertThat(timer).as("timer tagged region=eu, as parsed from tags = \"region=eu\"").isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    /// Pins the fix: a `tags` entry without `=` fails provisioning loud with the offending value
    /// named, instead of Micrometer's generic "size must be even" error three frames deeper on the
    /// interceptor's first invocation (`MetricsInterceptorFactory#parseTags`,
    /// `MetricsMethodInterceptor#tagOf`). Tags are parsed once at provisioning time, not per
    /// request, so a malformed entry never reaches `intercept(...)` at all.
    @Test
    void metricsInterceptorFactory_failsProvisioning_whenTagMissingEqualsSign() {
        var configService = configServiceFrom("""
                [metrics.checkout]
                name = "checkout.process"
                record_timing = true
                record_counts = true
                tags = "region"
                """);
        var config = configService.config("metrics.checkout", MetricsConfig.class).unwrap();
        var registry = new SimpleMeterRegistry();
        var context = ProvisioningContext.provisioningContext().withExtension(MeterRegistry.class, registry);

        var result = new MetricsInterceptorFactory().provision(config, context).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("region"));
    }

    /// Proves the resource-reference.md claim that `[metrics.*]` provisioning depends on a real
    /// `MeterRegistry` extension being present in the [ProvisioningContext] - there is no
    /// record-carried fallback registry, so an empty context fails provisioning rather than
    /// silently fabricating a disconnected one.
    @Test
    void metricsInterceptorFactory_failsProvisioning_whenMeterRegistryExtensionMissing() {
        var configService = configServiceFrom("""
                [metrics.checkout]
                name = "checkout.process"
                record_timing = true
                record_counts = true
                """);
        var config = configService.config("metrics.checkout", MetricsConfig.class).unwrap();
        var emptyContext = ProvisioningContext.provisioningContext();

        var result = new MetricsInterceptorFactory().provision(config, emptyContext).await();

        assertThat(result.isFailure()).isTrue();
    }

    private static ProviderBasedConfigService configServiceFrom(String toml) {
        var provider = ConfigurationProvider.builder()
                                            .withSource(TomlConfigSource.tomlConfigSource(toml).unwrap())
                                            .build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }
}
