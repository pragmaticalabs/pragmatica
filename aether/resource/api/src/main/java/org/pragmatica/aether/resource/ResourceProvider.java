// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


public interface ResourceProvider {
    <T> Promise<T> provide(Class<T> resourceType, String configSection);
    <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context);
    boolean hasFactory(Class<?> resourceType);
    Promise<Unit> releaseAll(String sliceId);

    /// The facade view of this provider for the slice-loading chain, forwarding EVERY method.
    ///
    /// The node used to build this inline as an anonymous class implementing the two `provide`
    /// overloads and nothing else, so `releaseAll` fell through to `ResourceProviderFacade`'s
    /// default — `Promise.unitPromise()`, a silent success. No release ever reached a provider on
    /// a deployed node, so no provisioned resource was ever closed there, independently of the
    /// release IDENTITY defect on the same path (#892) and of the close DISPATCH defect before it
    /// (#891). Three silent successes in series, each sufficient on its own.
    ///
    /// Naming the adapter does not make partial implementation impossible — the interface's
    /// default is still inheritable — but it removes the reason to write one: a caller that needs
    /// a facade over a provider now has a complete one to hand, at one site to audit instead of
    /// per call site.
    default ResourceProviderFacade facade() {
        return new ProviderFacade(this);
    }

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

/// The complete forwarding adapter behind [ResourceProvider#facade]. Every method of
/// [ResourceProviderFacade] is forwarded explicitly; none is left to the interface's default.
record ProviderFacade(ResourceProvider provider) implements ResourceProviderFacade {
    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
        return provider.provide(resourceType, configSection);
    }

    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
        return provider.provide(resourceType, configSection, context);
    }

    @Override
    public Promise<Unit> releaseAll(String sliceId) {
        return provider.releaseAll(sliceId);
    }
}
