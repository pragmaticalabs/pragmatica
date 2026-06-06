// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceCapacityExhausted;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.pragmatica.lang.Option.option;


public final class SpiResourceProvider implements ResourceProvider {
    private final Map<Class<?>, List<ResourceFactory<?, ?>>> factories;
    private final Map<CacheKey, Promise<?>> promiseCache;
    private final Map<CacheKey, Set<String>> consumers;
    private final Map<Class<?>, Object> runtimeExtensions;
    private final Fn2<Result<?>, String, Class<?>> configLoader;

    private SpiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        this.configLoader = configLoader;
        this.promiseCache = new ConcurrentHashMap<>();
        this.consumers = new ConcurrentHashMap<>();
        this.runtimeExtensions = new ConcurrentHashMap<>();
        Map<Class<?>, List<ResourceFactory<?, ?>>> factoryMap = new ConcurrentHashMap<>();
        ServiceLoader.load(ResourceFactory.class).stream()
                          .map(ServiceLoader.Provider::get)
                          .forEach(factory -> factoryMap.computeIfAbsent(factory.resourceType(),
                                                                         _ -> new ArrayList<>())
        .add(factory));
        factoryMap.replaceAll((_, list) -> sortByPriorityDescending(list));
        this.factories = Map.copyOf(factoryMap);
    }

    private static List<ResourceFactory<?, ?>> sortByPriorityDescending(List<ResourceFactory<?, ?>> list) {
        list.sort(Comparator.<ResourceFactory<?, ?>>comparingInt(ResourceFactory::priority).reversed());
        return List.copyOf(list);
    }

    public static SpiResourceProvider spiResourceProvider() {
        return new SpiResourceProvider(SpiResourceProvider::loadFromConfigService);
    }

    private static Result<?> loadFromConfigService(String section, Class<?> configClass) {
        return ConfigService.instance().toResult(ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE)
                                     .flatMap(svc -> svc.config(section, configClass));
    }

    public static SpiResourceProvider spiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        return new SpiResourceProvider(configLoader);
    }

    public static SpiResourceProvider spiResourceProvider(Function<String, Result<?>> configLoader) {
        return new SpiResourceProvider((section, configClass) -> configLoader.apply(section));
    }

    @SuppressWarnings("JBCT-RET-01") public <T> void registerExtension(Class<T> type, T instance) {
        runtimeExtensions.put(type, instance);
    }

    @Override@SuppressWarnings("unchecked") public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
        var key = new CacheKey(resourceType, configSection);
        return (Promise<T>) promiseCache.computeIfAbsent(key, k -> createResource(resourceType, configSection));
    }

    @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
        var key = new CacheKey(resourceType, configSection);
        context.extension(String.class)
                         .onSuccess(sliceId -> consumers.computeIfAbsent(key,
                                                                         _ -> ConcurrentHashMap.newKeySet())
        .add(sliceId));
        return createResourceWithContext(resourceType, configSection, context);
    }

    @Override public boolean hasFactory(Class<?> resourceType) {
        return factories.containsKey(resourceType);
    }

    @Override@SuppressWarnings("unchecked") public Promise<Unit> releaseAll(String sliceId) {
        var closeFutures = new ArrayList<Promise<Unit>>();
        var iterator = consumers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var key = entry.getKey();
            var consumerSet = entry.getValue();
            consumerSet.remove(sliceId);
            if (consumerSet.isEmpty()) {
                iterator.remove();
                var cached = promiseCache.remove(key);
                if (cached != null) {
                    var factoryList = factories.get(key.resourceType());
                    if (factoryList != null && !factoryList.isEmpty()) {
                        var factory = (ResourceFactory<Object, ?>) factoryList.getFirst();
                        closeFutures.add(cached.flatMap(resource -> factory.close(resource)));
                    }
                }
            }
        }
        if (closeFutures.isEmpty()) {return Promise.unitPromise();}
        return Promise.allOf(closeFutures).map(_ -> Unit.unit());
    }

    @SuppressWarnings("unchecked") private <T> Promise<T> createResource(Class<T> resourceType, String configSection) {
        return option(factories.get(resourceType)).filter(list -> !list.isEmpty())
                     .map(factoryList -> loadConfigAndInvoke((List<ResourceFactory<T, ?>>)(List<?>) factoryList,
                                                             resourceType,
                                                             configSection))
                     .or(() -> new SliceLoadingFailure.Fatal.ResourceFactoryNotFound(resourceType.getName()).promise());
    }

    @SuppressWarnings("unchecked") private <T> Promise<T> createResourceWithContext(Class<T> resourceType,
                                                                                    String configSection,
                                                                                    ProvisioningContext context) {
        var enrichedContext = enrichWithRuntimeExtensions(context);
        return option(factories.get(resourceType)).filter(list -> !list.isEmpty())
                     .map(factoryList -> loadConfigAndInvokeWithContext((List<ResourceFactory<T, ?>>)(List<?>) factoryList,
                                                                        resourceType,
                                                                        configSection,
                                                                        enrichedContext))
                     .or(() -> new SliceLoadingFailure.Fatal.ResourceFactoryNotFound(resourceType.getName()).promise());
    }

    private ProvisioningContext enrichWithRuntimeExtensions(ProvisioningContext context) {
        var enriched = context;
        for (var entry : runtimeExtensions.entrySet()) {
            @SuppressWarnings("unchecked") var type = (Class<Object>) entry.getKey();
            enriched = enriched.withExtension(type, entry.getValue());
        }
        return enriched;
    }

    private <T> Promise<T> loadConfigAndInvoke(List<ResourceFactory<T, ?>> factoryList,
                                               Class<T> resourceType,
                                               String configSection) {
        return loadConfig(configSection,
                          factoryList.getFirst().configType(),
                          org.pragmatica.lang.Option.<ProvisioningContext>none()).flatMap(config -> selectAndInvoke(factoryList,
                                                                                                 config,
                                                                                                 resourceType,
                                                                                                 configSection));
    }

    private <T> Promise<T> loadConfigAndInvokeWithContext(List<ResourceFactory<T, ?>> factoryList,
                                                          Class<T> resourceType,
                                                          String configSection,
                                                          ProvisioningContext context) {
        return loadConfig(configSection,
                          factoryList.getFirst().configType(),
                          org.pragmatica.lang.Option.some(context)).flatMap(config -> selectAndInvokeWithContext(factoryList,
                                                                                                            config,
                                                                                                            resourceType,
                                                                                                            configSection,
                                                                                                            context));
    }

    @SuppressWarnings("unchecked") private <T, C> Promise<T> selectAndInvoke(List<ResourceFactory<T, ?>> factoryList,
                                                                             C config,
                                                                             Class<T> resourceType,
                                                                             String configSection) {
        for (var factory : factoryList) {
            var typed = (ResourceFactory<T, C>) factory;
            if (typed.supports(config)) {return typed.provision(config)
                                                               .mapError(cause -> classifyProvisionFailure(resourceType, configSection, cause));}
        }
        return new SliceLoadingFailure.Fatal.ResourceFactoryNotFound(resourceType.getName()).promise();
    }

    @SuppressWarnings("unchecked") private <T, C> Promise<T> selectAndInvokeWithContext(List<ResourceFactory<T, ?>> factoryList,
                                                                                        C config,
                                                                                        Class<T> resourceType,
                                                                                        String configSection,
                                                                                        ProvisioningContext context) {
        for (var factory : factoryList) {
            var typed = (ResourceFactory<T, C>) factory;
            if (typed.supports(config)) {return typed.provision(config, context)
                                                               .mapError(cause -> classifyProvisionFailure(resourceType, configSection, cause));}
        }
        return new SliceLoadingFailure.Fatal.ResourceFactoryNotFound(resourceType.getName()).promise();
    }

    /// Classify a resource-provisioning failure for the slice-loading FSM (spec §6 / decision #7).
    /// A TRANSIENT capacity shortage ({@link ResourceCapacityExhausted}, e.g. `STREAM_MEMORY_EXCEEDED`)
    /// becomes {@link SliceLoadingFailure.Intermittent.ResourceUnavailable} so the deployment FSM
    /// RETRIES with backoff (the pool may clear) and surfaces `DeploymentFailed` only after
    /// MAX_RETRIES — visibly failed, not "deployed but dead". Everything else stays
    /// {@link SliceLoadingFailure.Fatal.ResourceCreationFailed} (permanent, no retry).
    private static SliceLoadingFailure classifyProvisionFailure(Class<?> resourceType, String configSection, Cause cause) {
        return ResourceCapacityExhausted.isTransientCapacity(cause)
              ? new SliceLoadingFailure.Intermittent.ResourceUnavailable(resourceType.getSimpleName(), cause)
              : new SliceLoadingFailure.Fatal.ResourceCreationFailed(resourceType.getSimpleName(), configSection, cause);
    }

    @SuppressWarnings("unchecked") private <C> Promise<C> loadConfig(String section,
                                                                      Class<C> configType,
                                                                      org.pragmatica.lang.Option<ProvisioningContext> contextOpt) {
        return resolveConfigLoader(contextOpt).apply(section, configType).mapError(cause -> new SliceLoadingFailure.Fatal.ConfigurationFailed(section,
                                                                                                                           cause))
                                 .map(obj -> (C) obj)
                                 .async();
    }

    /// Resolve the configuration loader for this provisioning call.
    ///
    /// When the provided context carries a `ConfigurationProvider` extension, the per-call
    /// slice-composite is used (wrapped in `ProviderBasedConfigService` for section binding).
    /// Otherwise the loader falls back to the constructor-supplied `configLoader` (typically
    /// the global `ConfigService.instance()` singleton).
    private Fn2<Result<?>, String, Class<?>> resolveConfigLoader(org.pragmatica.lang.Option<ProvisioningContext> contextOpt) {
        return contextOpt.flatMap(SpiResourceProvider::extractCompositeLoader)
                         .or(configLoader);
    }

    private static org.pragmatica.lang.Option<Fn2<Result<?>, String, Class<?>>> extractCompositeLoader(ProvisioningContext context) {
        return context.extension(ConfigurationProvider.class)
                      .option()
                      .map(SpiResourceProvider::loaderFromComposite);
    }

    private static Fn2<Result<?>, String, Class<?>> loaderFromComposite(ConfigurationProvider composite) {
        var svc = ProviderBasedConfigService.providerBasedConfigService(composite);
        return (section, configClass) -> svc.config(section, configClass);
    }

    private record CacheKey(Class<?> resourceType, String configSection){}
}
