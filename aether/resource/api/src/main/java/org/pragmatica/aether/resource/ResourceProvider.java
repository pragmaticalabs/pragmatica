// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface ResourceProvider {
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);
    boolean hasFactory(Class<?> resourceType);
    Promise<Unit> releaseAll(String sliceId);

    static Option<ResourceProvider> instance() {
        return ResourceProviderHolder.instance();
    }

    static Result<Unit> setInstance(ResourceProvider provider) {
        return ResourceProviderHolder.setInstance(provider);
    }

    static Result<Unit> clear() {
        return ResourceProviderHolder.clear();
    }

    static ResourceProvider resourceProvider() {
        return SpiResourceProvider.spiResourceProvider();
    }
}
