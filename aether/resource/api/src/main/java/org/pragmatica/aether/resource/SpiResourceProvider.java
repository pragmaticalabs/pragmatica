package org.pragmatica.aether.resource;

import org.pragmatica.config.ConfigService;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

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
/// Thread-safe: Uses ConcurrentHashMap.computeIfAbsent for atomic caching.
public final class SpiResourceProvider implements ResourceProvider {
    private final Map<Class<?>, ResourceFactory<?, ?>> factories;
    private final Map<CacheKey, Promise<?>> promiseCache;
    private final Fn2<Result<?>, String, Class<?>> configLoader;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SpiResourceProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        this.configLoader = configLoader;
        this.promiseCache = new ConcurrentHashMap<>();
        Map<Class<?>, ResourceFactory<?, ?>> factoryMap = new ConcurrentHashMap<>();
        var providers = ServiceLoader.load(ResourceFactory.class)
                                     .stream();
        providers.map(ServiceLoader.Provider::get)
                 .forEach(factory -> factoryMap.putIfAbsent(factory.resourceType(),
                                                            factory));
        this.factories = Map.copyOf(factoryMap);
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
        // Use computeIfAbsent for atomic cache access - prevents duplicate factory calls
        return (Promise<T>) promiseCache.computeIfAbsent(key, k -> createResource(resourceType, configSection));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
        var key = new CacheKey(resourceType, configSection);
        return (Promise<T>) promiseCache.computeIfAbsent(key,
                                                         k -> createResourceWithContext(resourceType,
                                                                                        configSection,
                                                                                        context));
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<T> createResource(Class<T> resourceType, String configSection) {
        var factoryResult = option(factories.get(resourceType))
        .toResult(ResourceProvisioningError.factoryNotFound(resourceType));
        return factoryResult.async()
                            .flatMap(factory -> loadConfigAndCreate((ResourceFactory<T, ?>) factory,
                                                                    configSection,
                                                                    resourceType));
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<T> createResourceWithContext(Class<T> resourceType,
                                                     String configSection,
                                                     ProvisioningContext context) {
        var factoryResult = option(factories.get(resourceType))
        .toResult(ResourceProvisioningError.factoryNotFound(resourceType));
        return factoryResult.async()
                            .flatMap(factory -> loadConfigAndCreateWithContext((ResourceFactory<T, ?>) factory,
                                                                               configSection,
                                                                               resourceType,
                                                                               context));
    }

    @Override
    public boolean hasFactory(Class<?> resourceType) {
        return factories.containsKey(resourceType);
    }

    private <T, C> Promise<T> loadConfigAndCreate(ResourceFactory<T, C> factory,
                                                  String configSection,
                                                  Class<T> resourceType) {
        return loadConfig(configSection, factory.configType())
        .flatMap(config -> invokeFactory(factory, config, resourceType, configSection));
    }

    private <T, C> Promise<T> loadConfigAndCreateWithContext(ResourceFactory<T, C> factory,
                                                             String configSection,
                                                             Class<T> resourceType,
                                                             ProvisioningContext context) {
        return loadConfig(configSection, factory.configType())
        .flatMap(config -> invokeFactoryWithContext(factory, config, resourceType, configSection, context));
    }

    @SuppressWarnings("unchecked")
    private <C> Promise<C> loadConfig(String section, Class<C> configType) {
        return configLoader.apply(section, configType)
                           .mapError(cause -> ResourceProvisioningError.configLoadFailed(section, cause))
                           .map(obj -> (C) obj)
                           .async();
    }

    private <T, C> Promise<T> invokeFactory(ResourceFactory<T, C> factory,
                                            C config,
                                            Class<T> resourceType,
                                            String configSection) {
        return factory.provision(config)
                      .mapError(cause -> ResourceProvisioningError.creationFailed(resourceType, configSection, cause));
    }

    private <T, C> Promise<T> invokeFactoryWithContext(ResourceFactory<T, C> factory,
                                                       C config,
                                                       Class<T> resourceType,
                                                       String configSection,
                                                       ProvisioningContext context) {
        return factory.provision(config, context)
                      .mapError(cause -> ResourceProvisioningError.creationFailed(resourceType, configSection, cause));
    }

    /// Cache key for (resourceType, configSection) pairs.
    private record CacheKey(Class<?> resourceType, String configSection) {}
}
