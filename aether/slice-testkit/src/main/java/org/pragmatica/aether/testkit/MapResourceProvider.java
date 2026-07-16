// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import java.util.Map;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.lang.Promise;

import static org.pragmatica.lang.Option.option;


/// Map-backed [ResourceProviderFacade] — the entire injection seam of the kit (spec §2.2, §5).
///
/// Where production `SpiResourceProvider` discovers `ResourceFactory` SPIs and resolves config from
/// the global `ConfigService`, this returns pre-registered instances keyed by `(resourceType,
/// configSection)` — the exact coordinate the generated `{Interface}Factory` passes to
/// `ctx.resources().provide(...)`. A requested coordinate with no entry fails fast with
/// [TestKitError.MissingResource], listing what was missing.
public record MapResourceProvider(Map<ResourceKey, Object> resources) implements ResourceProviderFacade {
    public static MapResourceProvider mapResourceProvider(Map<ResourceKey, Object> resources) {
        return new MapResourceProvider(Map.copyOf(resources));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
        return option((T) resources.get(new ResourceKey(resourceType, configSection))).async(new TestKitError.MissingResource(resourceType.getName(),
                                                                                                                              configSection));
    }

    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
        return provide(resourceType, configSection);
    }

    /// A resource coordinate: the runtime type the generated factory asks for plus its config
    /// section. Registration and lookup use exactly this key.
    public record ResourceKey(Class<?> resourceType, String configSection) {}
}
