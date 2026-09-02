// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Resource provider overlay scoped to ONE slice's classloader (#773).
///
/// The node's [SpiResourceProvider] builds its factory registry once, at node boot, from a
/// `ServiceLoader` scan of the thread-context classloader — long before any slice jar exists. A
/// resource type a user defines in their own slice is defined by that slice's `SliceClassLoader`,
/// so it can never be a key in the node's `Class`-keyed map, and provisioning it fails with
/// `SliceLoadingFailure.Fatal.ResourceFactoryNotFound`. The user's `META-INF/services` descriptor
/// already ships inside the slice jar (`PackageSlicesMojo` preserves it deliberately); until now
/// nothing read it.
///
/// This overlay reads it, with the slice's own loader, and is consulted BEFORE the node provider.
/// Keying stays by `Class`, never by name: the call-site literal in the generated factory and the
/// `resourceType()` the slice's factory reports both originate in the slice loader, so identity
/// matches by construction. Name-keying would answer with a node-loader instance and the generated
/// factory's implicit cast to the slice-loader type would throw `ClassCastException`.
///
/// Lifetime is the loading context's: the overlay is reachable only from the `SliceLoadingContext`
/// that built it, so it dies with the context and its loader.
public final class SliceScopedResourceProvider implements ResourceProviderFacade {
    private final SpiResourceProvider sliceScoped;
    private final ResourceProviderFacade nodeProvider;

    private SliceScopedResourceProvider(SpiResourceProvider sliceScoped, ResourceProviderFacade nodeProvider) {
        this.sliceScoped = sliceScoped;
        this.nodeProvider = nodeProvider;
    }

    /// Overlay over `nodeProvider` for the factories `sliceClassLoader` itself defines, resolving
    /// configuration the same way the node does. Returns [Option#none] when the slice ships no
    /// factories of its own — the overwhelmingly common case — so nothing is wrapped for nothing.
    public static Option<ResourceProviderFacade> sliceScopedResourceProvider(ClassLoader sliceClassLoader,
                                                                             ResourceProviderFacade nodeProvider) {
        return sliceScopedResourceProvider(sliceClassLoader, nodeProvider, SpiResourceProvider::loadFromConfigService);
    }

    /// Variant taking an explicit configuration loader, for callers that do not run against the
    /// global `ConfigService` singleton.
    public static Option<ResourceProviderFacade> sliceScopedResourceProvider(ClassLoader sliceClassLoader,
                                                                             ResourceProviderFacade nodeProvider,
                                                                             Fn2<Result<?>, String, Class<?>> configLoader) {
        var sliceFactories = discoverSliceFactories(sliceClassLoader);

        return sliceFactories.isEmpty()
               ? Option.none()
               : Option.some(new SliceScopedResourceProvider(SpiResourceProvider.spiResourceProvider(sliceFactories,
                                                                                                     configLoader),
                                                             nodeProvider));
    }

    /// Keep ONLY the factories the slice's own loader defined.
    ///
    /// `ServiceLoader` walks the whole delegation chain, so this scan also finds every platform
    /// factory the node already registered at boot. Letting those into the overlay would give each
    /// slice its OWN instance of every built-in resource — a separate connection pool, stream
    /// publisher, cache and idempotency store per slice — because the overlay carries its own
    /// promise cache and the node's, which is what makes those resources shared, would never be
    /// consulted. The slice loader defines exactly the classes that came out of the slice jar, which
    /// is exactly the set the node registry cannot hold, so loader identity is the filter.
    ///
    /// The filter is applied to [ServiceLoader.Provider#type], before [ServiceLoader.Provider#get],
    /// so a built-in factory is never even instantiated on this path.
    private static List<ResourceFactory<?, ?>> discoverSliceFactories(ClassLoader sliceClassLoader) {
        var sliceFactories = new ArrayList<ResourceFactory<?, ?>>();

        ServiceLoader.load(ResourceFactory.class, sliceClassLoader)
                     .stream()
                     .filter(provider -> provider.type()
                                                 .getClassLoader() == sliceClassLoader)
                     .map(ServiceLoader.Provider::get)
                     .forEach(sliceFactories::add);

        return List.copyOf(sliceFactories);
    }

    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
        return sliceScoped.hasFactory(resourceType)
               ? sliceScoped.provide(resourceType, configSection)
               : nodeProvider.provide(resourceType, configSection);
    }

    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
        return sliceScoped.hasFactory(resourceType)
               ? sliceScoped.provide(resourceType, configSection, context)
               : nodeProvider.provide(resourceType, configSection, context);
    }

    /// Release BOTH sides. The slice-scoped provider holds its own promise cache, so a resource a
    /// user factory created is closed here or nowhere; inheriting the interface's no-op default
    /// would strand it for the lifetime of the node. Both releases are started before either is
    /// awaited, so a failure on one side cannot skip the other.
    @Override
    public Promise<Unit> releaseAll(String sliceId) {
        var scopedRelease = sliceScoped.releaseAll(sliceId);
        var nodeRelease = nodeProvider.releaseAll(sliceId);

        return Promise.allOf(List.of(scopedRelease, nodeRelease)).map(_ -> Unit.unit());
    }
}
