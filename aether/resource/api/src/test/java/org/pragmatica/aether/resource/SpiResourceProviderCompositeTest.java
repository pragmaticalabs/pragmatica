// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class SpiResourceProviderCompositeTest {

    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    @Test
    void provide_withCompositeExtension_usesCompositeLoaderAndSkipsConstructorLoader() {
        var fallbackCalled = new AtomicBoolean(false);
        var provider = spiResourceProvider((section, configClass) -> {
            fallbackCalled.set(true);
            return Result.success("from-fallback");
        });
        var composite = ConfigurationProvider.builder().build();
        var ctx = ProvisioningContext.provisioningContext()
                                     .withExtension(ConfigurationProvider.class, composite);

        // Resource type with no registered factory — we only verify config-loader routing,
        // not actual provisioning. The factory-not-found error is expected.
        var result = provider.provide(String.class, "test.section", ctx).await(TIMEOUT);

        // Factory-not-found wins because no String factory is registered. Critical assertion:
        // the constructor-supplied configLoader fallback must NOT have been called when a
        // ConfigurationProvider extension is present in the context.
        assertThat(result.isFailure()).isTrue();
        assertThat(fallbackCalled.get()).isFalse();
    }

    /// The mirror of the test above: with no `ConfigurationProvider` in play, the
    /// constructor-supplied fallback loader IS the one consulted.
    ///
    /// This previously asserted that two calls returned the same promise. That was a proxy for
    /// "the no-context overload caches", but `String.class` has no factory, so what it actually
    /// pinned was a memoized FAILURE — #268 R4. Asserting the loader routing directly tests the
    /// property this case is named for and is indifferent to caching.
    @Test
    void provide_withoutCompositeExtension_usesConstructorSuppliedLoader() {
        var fallbackCalled = new AtomicBoolean(false);
        var provider = spiResourceProvider((section, configClass) -> {
            fallbackCalled.set(true);
            return Result.success(new RecordedResourceConfig(section));
        });

        // RecordedResource, not String: a type with NO registered factory fails before the config
        // loader is ever consulted, so `fallbackCalled` would stay false for the wrong reason.
        var result = provider.provide(RecordedResource.class, "test.section")
                             .await(TIMEOUT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(fallbackCalled.get()).isTrue();
    }
}
