package org.pragmatica.aether.infra;

import org.pragmatica.aether.config.ConfigService;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Functions.Fn2;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
        ServiceLoader.load(ResourceFactory.class)
                     .stream()
                     .map(ServiceLoader.Provider::get)
                     .forEach(factory -> factoryMap.putIfAbsent(factory.resourceType(),
                                                                factory));
        this.factories = Map.copyOf(factoryMap);
    }

    /// Create an SpiResourceProvider that uses the ConfigService instance for loading.
    ///
    /// @return New SpiResourceProvider
    public static SpiResourceProvider spiResourceProvider() {
        return new SpiResourceProvider((section, configClass) -> ConfigService.instance()
                                                                              .toResult(ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE)
                                                                              .flatMap(configService -> configService.config(section,
                                                                                                                             configClass)));
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

    @SuppressWarnings("unchecked")
    private <T> Promise<T> createResource(Class<T> resourceType, String configSection) {
        return Option.option(factories.get(resourceType))
                     .toResult(ResourceProvisioningError.factoryNotFound(resourceType))
                     .async()
                     .flatMap(factory -> loadConfigAndCreate((ResourceFactory<T, ?>) factory,
                                                             configSection,
                                                             resourceType));
    }

    @Override
    public boolean hasFactory(Class<?> resourceType) {
        return factories.containsKey(resourceType);
    }

    private <T, C> Promise<T> loadConfigAndCreate(ResourceFactory<T, C> factory,
                                                  String configSection,
                                                  Class<T> resourceType) {
        return loadConfig(configSection, factory.configType())
        .flatMap(config -> createResource(factory, config, resourceType, configSection));
    }

    @SuppressWarnings("unchecked")
    private <C> Promise<C> loadConfig(String section, Class<C> configType) {
        return configLoader.apply(section, configType)
                           .mapError(cause -> ResourceProvisioningError.configLoadFailed(section, cause))
                           .map(obj -> (C) obj)
                           .async();
    }

    private <T, C> Promise<T> createResource(ResourceFactory<T, C> factory,
                                             C config,
                                             Class<T> resourceType,
                                             String configSection) {
        return factory.provision(config)
                      .mapError(cause -> ResourceProvisioningError.creationFailed(resourceType, configSection, cause));
    }

    /// Cache key for (resourceType, configSection) pairs.
    private record CacheKey(Class<?> resourceType, String configSection) {}
}
