package org.pragmatica.aether.resource;

import org.pragmatica.config.ConfigService;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.pragmatica.lang.Option.option;

/// SPI-based implementation of ResourceProvider.
///
/// Discovers {@link ResourceFactory} implementations via ServiceLoader
/// and caches created instances by (resourceType, configSection) key.
///
/// When multiple factories are registered for the same resource type,
/// they are sorted by priority (descending). At provision time, the config
/// is loaded once and factories are tried in order — the first one where
/// {@code supports(config)} returns true is selected.
///
/// Thread-safe: Uses ConcurrentHashMap.computeIfAbsent for atomic caching.
public final class SpiResourceProvider implements ResourceProvider {
    private final Map<Class<?>, List<ResourceFactory<?, ?>>> factories;
    private final Map<CacheKey, Promise<?>> promiseCache;
    private final Fn2<Result<?>, String, Class<?>> configLoader;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SpiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        this.configLoader = configLoader;
        this.promiseCache = new ConcurrentHashMap<>();
        Map<Class<?>, List<ResourceFactory<?, ?>>> factoryMap = new ConcurrentHashMap<>();
        ServiceLoader.load(ResourceFactory.class)
                     .stream()
                     .map(ServiceLoader.Provider::get)
                     .forEach(factory -> factoryMap.computeIfAbsent(factory.resourceType(),
                                                                    _ -> new ArrayList<>())
                                                   .add(factory));
        // Sort each list by priority descending (highest first) and make immutable
        factoryMap.replaceAll((_, list) -> sortByPriorityDescending(list));
        this.factories = Map.copyOf(factoryMap);
    }

    private static List<ResourceFactory<?, ?>> sortByPriorityDescending(List<ResourceFactory<?, ?>> list) {
        list.sort(Comparator.<ResourceFactory<?, ?>> comparingInt(ResourceFactory::priority)
                            .reversed());
        return List.copyOf(list);
    }

    /// Create an SpiResourceProvider that uses the ConfigService instance for loading.
    ///
    /// @return New SpiResourceProvider
    public static SpiResourceProvider spiResourceProvider() {
        return new SpiResourceProvider(SpiResourceProvider::loadFromConfigService);
    }

    private static Result<?> loadFromConfigService(String section, Class<?> configClass) {
        return ConfigService.instance()
                            .toResult(ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE)
                            .flatMap(svc -> svc.config(section, configClass));
    }

    /// Create an SpiResourceProvider with a custom config loader.
    ///
    /// Useful for testing or custom configuration sources.
    ///
    /// @param configLoader Function that loads config sections with (section, configClass)
    /// @return New SpiResourceProvider
    public static SpiResourceProvider spiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        return new SpiResourceProvider(configLoader);
    }

    /// Create an SpiResourceProvider with a simple config loader (section only).
    ///
    /// Backwards-compatible factory for testing scenarios where config type is not needed.
    ///
    /// @param configLoader Function that loads config sections by name
    /// @return New SpiResourceProvider
    public static SpiResourceProvider spiResourceProvider(Function<String, Result<?>> configLoader) {
        return new SpiResourceProvider((section, configClass) -> configLoader.apply(section));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
        var key = new CacheKey(resourceType, configSection);
        return (Promise<T>) promiseCache.computeIfAbsent(key, k -> createResource(resourceType, configSection));
    }

    @Override
    public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
        return createResourceWithContext(resourceType, configSection, context);
    }

    @Override
    public boolean hasFactory(Class<?> resourceType) {
        return factories.containsKey(resourceType);
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<T> createResource(Class<T> resourceType, String configSection) {
        return option(factories.get(resourceType)).filter(list -> !list.isEmpty())
                     .map(factoryList -> loadConfigAndInvoke((List<ResourceFactory<T, ?>>)(List<?>) factoryList,
                                                             resourceType,
                                                             configSection))
                     .or(() -> ResourceProvisioningError.factoryNotFound(resourceType)
                                                        .promise());
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<T> createResourceWithContext(Class<T> resourceType,
                                                     String configSection,
                                                     ProvisioningContext context) {
        return option(factories.get(resourceType)).filter(list -> !list.isEmpty())
                     .map(factoryList -> loadConfigAndInvokeWithContext((List<ResourceFactory<T, ?>>)(List<?>) factoryList,
                                                                        resourceType,
                                                                        configSection,
                                                                        context))
                     .or(() -> ResourceProvisioningError.factoryNotFound(resourceType)
                                                        .promise());
    }

    private <T> Promise<T> loadConfigAndInvoke(List<ResourceFactory<T, ?>> factoryList,
                                               Class<T> resourceType,
                                               String configSection) {
        return loadConfig(configSection,
                          factoryList.getFirst()
                                     .configType())
        .flatMap(config -> selectAndInvoke(factoryList, config, resourceType, configSection));
    }

    private <T> Promise<T> loadConfigAndInvokeWithContext(List<ResourceFactory<T, ?>> factoryList,
                                                          Class<T> resourceType,
                                                          String configSection,
                                                          ProvisioningContext context) {
        return loadConfig(configSection,
                          factoryList.getFirst()
                                     .configType())
        .flatMap(config -> selectAndInvokeWithContext(factoryList, config, resourceType, configSection, context));
    }

    @SuppressWarnings("unchecked")
    private <T, C> Promise<T> selectAndInvoke(List<ResourceFactory<T, ?>> factoryList,
                                              C config,
                                              Class<T> resourceType,
                                              String configSection) {
        for (var factory : factoryList) {
            var typed = (ResourceFactory<T, C>) factory;
            if (typed.supports(config)) {
                return typed.provision(config)
                            .mapError(cause -> ResourceProvisioningError.creationFailed(resourceType,
                                                                                        configSection,
                                                                                        cause));
            }
        }
        return ResourceProvisioningError.factoryNotFound(resourceType)
                                        .promise();
    }

    @SuppressWarnings("unchecked")
    private <T, C> Promise<T> selectAndInvokeWithContext(List<ResourceFactory<T, ?>> factoryList,
                                                         C config,
                                                         Class<T> resourceType,
                                                         String configSection,
                                                         ProvisioningContext context) {
        for (var factory : factoryList) {
            var typed = (ResourceFactory<T, C>) factory;
            if (typed.supports(config)) {
                return typed.provision(config, context)
                            .mapError(cause -> ResourceProvisioningError.creationFailed(resourceType,
                                                                                        configSection,
                                                                                        cause));
            }
        }
        return ResourceProvisioningError.factoryNotFound(resourceType)
                                        .promise();
    }

    @SuppressWarnings("unchecked")
    private <C> Promise<C> loadConfig(String section, Class<C> configType) {
        return configLoader.apply(section, configType)
                           .mapError(cause -> ResourceProvisioningError.configLoadFailed(section, cause))
                           .map(obj -> (C) obj)
                           .async();
    }

    /// Cache key for (resourceType, configSection) pairs.
    private record CacheKey(Class<?> resourceType, String configSection) {}
}
