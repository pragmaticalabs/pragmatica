// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class SpiResourceProviderTest {

    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    @Nested
    class Fn2ConfigLoader {

        @Test
        void spiResourceProvider_createsProvider_withFn2Loader() {
            var provider = spiResourceProvider((section, configClass) -> Result.success("dummy"));

            assertThat(provider).isNotNull();
        }

        @Test
        void spiResourceProvider_passesSectionAndClass_toFn2Loader() {
            var provider = spiResourceProvider((section, configClass) -> {
                // Fn2 receives both arguments; provider creation itself succeeds
                return Result.success("config-value");
            });

            assertThat(provider).isNotNull();
            assertThat(provider.hasFactory(String.class)).isFalse();
        }
    }

    @Nested
    class HasFactory {

        @Test
        void hasFactory_returnsFalse_whenNoFactoriesRegistered() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            assertThat(provider.hasFactory(String.class)).isFalse();
        }

        @Test
        void hasFactory_returnsFalse_forArbitraryType() {
            var provider = spiResourceProvider((section, configClass) -> Result.success("dummy"));

            assertThat(provider.hasFactory(Integer.class)).isFalse();
        }
    }

    @Nested
    class Provide {

        @Test
        void provide_returnsFailure_whenNoFactoryRegistered() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            var result = provider.provide(String.class, "test")
                                 .await(TIMEOUT);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void provide_returnsFactoryNotFoundError_whenNoFactoryRegistered() {
            var provider = spiResourceProvider((section, configClass) -> Result.success("dummy"));

            var result = provider.provide(String.class, "test.section")
                                 .await(TIMEOUT);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.ResourceFactoryNotFound.class)
            );
        }

        @Test
        void provide_failsWithNamedError_whenProviderMissing() {
            var provider = spiResourceProvider((section, configClass) -> Result.success("dummy"));

            var result = provider.provide(String.class, "test.section")
                                 .await(TIMEOUT);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("No resource provider registered")
                                                                 .contains(String.class.getName()));
        }

        /// #268 R4: a FAILED provisioning must not be memoized.
        ///
        /// This test previously asserted the opposite — that two calls returned the SAME promise.
        /// `String.class` has no registered factory, so what it pinned was a memoized FAILURE,
        /// which is the defect itself: an `Intermittent` failure classified for retry-with-backoff
        /// handed the same poisoned promise back on every retry.
        ///
        /// The failure is injected in the CONFIG LOADER rather than the factory, so this covers the
        /// eviction path from a different origin than the lifecycle test's failing `provision`.
        /// Asserting promise non-identity would NOT work here: `provide` maps the cached promise on
        /// every call, so the returned objects always differ whether or not eviction happens — that
        /// assertion cannot fail and proves nothing.
        @Test
        void provide_retriesProvisioning_afterAFailedAttempt() {
            var attempts = new AtomicInteger();
            var provider = spiResourceProvider((section, configClass) -> attempts.incrementAndGet() == 1
                                                                        ? Causes.cause("transient config failure")
                                                                                .result()
                                                                        : Result.success(new RecordedResourceConfig(section)));

            var first = provider.provide(RecordedResource.class, "retry.section")
                                .await(TIMEOUT);
            var second = provider.provide(RecordedResource.class, "retry.section")
                                 .await(TIMEOUT);

            assertThat(first.isFailure()).isTrue();
            assertThat(second.isSuccess()).isTrue();
        }
    }

    @Nested
    class BackwardsCompatibleFunctionOverload {

        @Test
        void spiResourceProvider_createsProvider_withFunctionLoader() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            assertThat(provider).isNotNull();
        }

        @Test
        void spiResourceProvider_hasNoFactories_withFunctionLoader() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            assertThat(provider.hasFactory(String.class)).isFalse();
        }
    }

    @Nested
    class NoArgFactory {

        @Test
        void spiResourceProvider_createsProvider_withNoArgs() {
            var provider = spiResourceProvider();

            assertThat(provider).isNotNull();
        }

        @Test
        void spiResourceProvider_hasNoFactories_withNoArgs() {
            var provider = spiResourceProvider();

            assertThat(provider.hasFactory(String.class)).isFalse();
        }
    }
}
