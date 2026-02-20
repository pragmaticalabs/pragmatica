package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Result.success;

/// Factory that provisions {@link CacheMethodInterceptor} instances from configuration.
///
/// Shared cache instances: configurations with the same {@link CacheConfig#cacheName()}
/// share a single {@link CacheBackend} via {@code computeIfAbsent}.
public final class CacheInterceptorFactory implements ResourceFactory<CacheMethodInterceptor, CacheConfig> {
    private final Map<String, CacheBackend> cacheRegistry = new ConcurrentHashMap<>();

    @Override
    public Class<CacheMethodInterceptor> resourceType() {
        return CacheMethodInterceptor.class;
    }

    @Override
    public Class<CacheConfig> configType() {
        return CacheConfig.class;
    }

    @Override
    public Promise<CacheMethodInterceptor> provision(CacheConfig config) {
        return provision(config, ProvisioningContext.provisioningContext());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Promise<CacheMethodInterceptor> provision(CacheConfig config, ProvisioningContext context) {
        var keyExtractor = (Fn1<Object, ?>) context.keyExtractor().or(Fn1.id());

        return createCache(config, context).onSuccess(cache -> cacheRegistry.computeIfAbsent(config.cacheName(), _ -> cache))
                                           .map(cache -> new CacheMethodInterceptor(cache, config.strategy(), keyExtractor))
                                           .async();
    }

    private Result<? extends CacheBackend> createCache(CacheConfig config, ProvisioningContext context) {
        return switch (config.mode()) {
            case LOCAL -> success(createInMemory(config));
            case DISTRIBUTED -> createDHTBackend(config, context);
            case TIERED -> createDHTBackend(config, context).map(dhtCache -> createTiered(config, dhtCache));
        };
    }

    private static TieredCache createTiered(CacheConfig config, CacheBackend dhtCache) {
        return TieredCache.tieredCache(createInMemory(config), dhtCache);
    }

    private static InMemoryCache createInMemory(CacheConfig config) {
        return InMemoryCache.inMemoryCache(config.ttlSeconds(), config.maxEntries());
    }

    private Result<CacheBackend> createDHTBackend(CacheConfig config, ProvisioningContext context) {
        return Result.all(context.extension(DHTClient.class),
                          context.extension(Serializer.class),
                          context.extension(Deserializer.class),
                          success(config.cacheName()))
                     .map(DHTCacheBackend::dhtCacheBackend);
    }
}
