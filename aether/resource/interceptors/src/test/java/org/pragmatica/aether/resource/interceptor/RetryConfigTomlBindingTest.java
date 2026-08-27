// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #278 end-to-end proof that the REAL [RetryConfig] (not [ProviderBasedConfigServiceTest]'s
/// structurally-identical `RetryStrategyConfig` local double) binds correctly through the actual
/// TOML -> [ProviderBasedConfigService] -> [RetryInterceptorFactory] path. Closes the "TOML-binding
/// test per interceptor (currently none) - why R24 is latent" gap named in #278's own acceptance
/// text: the generic discriminated-binder mechanism was already proven against a local test record,
/// but never against [RetryConfig] itself or [RetryInterceptorFactory]'s `configType()`.
class RetryConfigTomlBindingTest {
    @Test
    void config_fixedBackoffStrategy_bindsRealRetryConfig() {
        var configService = configServiceFrom("""
                [retry.checkout]
                max_attempts = 5

                [retry.checkout.backoff_strategy]
                type = "fixed"
                interval = "250ms"
                """);

        var config = configService.config("retry.checkout", RetryConfig.class).unwrap();

        assertThat(config.maxAttempts()).isEqualTo(5);
        assertThat(config.backoffStrategy()).isEqualTo(BackoffStrategy.fixed().interval(timeSpan(250).millis()));
    }

    @Test
    void config_linearBackoffStrategy_bindsRealRetryConfig() {
        var configService = configServiceFrom("""
                [retry.checkout]
                max_attempts = 4

                [retry.checkout.backoff_strategy]
                type = "linear"
                initial_delay = "1s"
                increment = "2s"
                max_delay = "30s"
                """);

        var config = configService.config("retry.checkout", RetryConfig.class).unwrap();

        assertThat(config.backoffStrategy()).isEqualTo(BackoffStrategy.linear()
                                                                       .initialDelay(timeSpan(1).seconds())
                                                                       .increment(timeSpan(2).seconds())
                                                                       .maxDelay(timeSpan(30).seconds()));
    }

    /// A *present* `[retry.checkout]` section that omits `backoff_strategy` entirely falls back to
    /// `RetryConfig.DEFAULT.backoffStrategy()` component-wise (`ProviderBasedConfigService#collectComponentAt`
    /// -> `getDefaultComponentValue`, reflecting `RetryConfig.DEFAULT` because it is public and of
    /// `RetryConfig`'s own type per #278's DEFAULT-allowlist design). This is the opposite of the
    /// wholly-absent-section case below - do not conflate the two.
    @Test
    void config_presentSectionOmittingBackoffStrategy_fallsBackToRetryConfigDefault() {
        var configService = configServiceFrom("""
                [retry.checkout]
                max_attempts = 5
                """);

        var result = configService.config("retry.checkout", RetryConfig.class);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.unwrap().backoffStrategy()).isEqualTo(RetryConfig.DEFAULT.backoffStrategy());
    }

    /// A *wholly absent* `[retry.checkout]` section fails loud at `ProviderBasedConfigService#config`'s
    /// `hasSection` gate, before any per-field DEFAULT lookup runs - `RetryConfig.DEFAULT` is never
    /// consulted. Pins the same fail-loud contract `CacheConfig`/`IdempotencyConfig` document for
    /// identity-bearing configs, proving it also holds for a class that DOES publish a DEFAULT.
    @Test
    void config_whollyAbsentSection_failsLoud_doesNotFallBackToDefault() {
        var configService = configServiceFrom("""
                [other]
                key = "value"
                """);

        var result = configService.config("retry.checkout", RetryConfig.class);

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void retryInterceptorFactory_provisionsFromRealTomlBoundConfig_andRetriesUntilSuccess() {
        var configService = configServiceFrom("""
                [retry.flaky]
                max_attempts = 3

                [retry.flaky.backoff_strategy]
                type = "fixed"
                interval = "1ms"
                """);
        var config = configService.config("retry.flaky", RetryConfig.class).unwrap();
        var interceptor = new RetryInterceptorFactory().provision(config)
                                                       .await()
                                                       .onFailureRun(() -> fail("Expected interceptor provisioning to succeed"))
                                                       .unwrap();

        var attempts = new AtomicInteger();
        var intercepted = interceptor.intercept((Integer request) -> attemptOperation(attempts));

        var outcome = intercepted.apply(1).await();

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.unwrap()).isEqualTo("ok after 3 attempts");
        assertThat(attempts.get()).isEqualTo(3);
    }

    private static Promise<String> attemptOperation(AtomicInteger attempts) {
        var attempt = attempts.incrementAndGet();

        return attempt < 3
               ? Promise.failure(Causes.cause("transient failure #" + attempt))
               : Promise.success("ok after " + attempt + " attempts");
    }

    private static ProviderBasedConfigService configServiceFrom(String toml) {
        var provider = ConfigurationProvider.builder()
                                            .withSource(TomlConfigSource.tomlConfigSource(toml).unwrap())
                                            .build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }
}
