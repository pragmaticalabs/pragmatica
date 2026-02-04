package org.pragmatica.aether.infra;

import org.pragmatica.aether.config.ConfigService;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * SPI-based implementation of ResourceProvider.
 * <p>
 * Discovers {@link ResourceFactory} implementations via ServiceLoader
 * and caches created instances by (resourceType, configSection) key.
 * <p>
 * Thread-safe: Uses ConcurrentHashMap for instance caching.
 */
public final class SpiResourceProvider implements ResourceProvider {
    private final Map<Class<?>, ResourceFactory<?, ?>> factories;
    private final Map<CacheKey, Object> instanceCache;
    private final Function<String, Result<?>> configLoader;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SpiResourceProvider(Function<String, Result<?>> configLoader) {
        this.configLoader = configLoader;
        this.instanceCache = new ConcurrentHashMap<>();
        Map<Class<?>, ResourceFactory<?, ?>> factoryMap = new ConcurrentHashMap<>();
        ServiceLoader.load(ResourceFactory.class)
                     .stream()
                     .map(ServiceLoader.Provider::get)
                     .forEach(factory -> factoryMap.putIfAbsent(factory.resourceType(), factory));
        this.factories = factoryMap;
    }

    /**
     * Create an SpiResourceProvider that uses the ConfigService instance for loading.
     *
     * @return New SpiResourceProvider
     */
    public static SpiResourceProvider spiResourceProvider() {
        return new SpiResourceProvider(section ->
            ConfigService.instance()
                         .toResult(ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE)
                         .flatMap(configService -> configService.config(section, Object.class))
        );
    }

    /**
     * Create an SpiResourceProvider with a custom config loader.
     * <p>
     * Useful for testing or custom configuration sources.
     *
     * @param configLoader Function that loads config sections
     * @return New SpiResourceProvider
     */
    public static SpiResourceProvider spiResourceProvider(Function<String, Result<?>> configLoader) {
        return new SpiResourceProvider(configLoader);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
        var key = new CacheKey(resourceType, configSection);

        // Check cache first
        return Option.option(instanceCache.get(key))
                     .map(cached -> Promise.success((T) cached))
                     .or(() -> findAndCreateResource(resourceType, configSection, key));
    }

    @SuppressWarnings("unchecked")
    private <T> Promise<T> findAndCreateResource(Class<T> resourceType, String configSection, CacheKey key) {
        return Option.option(factories.get(resourceType))
                     .toResult(ResourceProvisioningError.factoryNotFound(resourceType))
                     .async()
                     .flatMap(factory -> loadConfigAndCreate((ResourceFactory<T, ?>) factory, configSection, resourceType, key));
    }

    @Override
    public boolean hasFactory(Class<?> resourceType) {
        return factories.containsKey(resourceType);
    }

    private <T, C> Promise<T> loadConfigAndCreate(ResourceFactory<T, C> factory,
                                                   String configSection,
                                                   Class<T> resourceType,
                                                   CacheKey key) {
        // Load config using the config loader (which should delegate to ConfigService)
        return loadConfig(configSection, factory.configType())
            .flatMap(config -> createAndCache(factory, config, key, resourceType, configSection));
    }

    @SuppressWarnings("unchecked")
    private <C> Promise<C> loadConfig(String section, Class<C> configType) {
        return configLoader.apply(section)
                           .mapError(cause -> ResourceProvisioningError.configLoadFailed(section, cause))
                           .map(obj -> (C) obj)
                           .async();
    }

    @SuppressWarnings("unchecked")
    private <T, C> Promise<T> createAndCache(ResourceFactory<T, C> factory,
                                              C config,
                                              CacheKey key,
                                              Class<T> resourceType,
                                              String configSection) {
        return factory.create(config)
                      .mapError(cause -> ResourceProvisioningError.creationFailed(resourceType, configSection, cause))
                      .onSuccess(instance -> instanceCache.putIfAbsent(key, instance))
                      .map(instance -> returnCachedOrNew(key, instance));
    }

    @SuppressWarnings("unchecked")
    private <T> T returnCachedOrNew(CacheKey key, T instance) {
        return Option.option(instanceCache.get(key))
                     .map(existing -> (T) existing)
                     .or(instance);
    }

    /**
     * Cache key for (resourceType, configSection) pairs.
     */
    private record CacheKey(Class<?> resourceType, String configSection) {}
}
