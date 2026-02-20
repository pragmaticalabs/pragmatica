package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        var cache = cacheRegistry.computeIfAbsent(config.cacheName(),
                                                  _ -> InMemoryCache.inMemoryCache(config.ttlSeconds(),
                                                                                   config.maxEntries()));
        var keyExtractor = (Fn1<Object, ?>) context.keyExtractor()
                                                  .or(Fn1.id());
        return Promise.success(new CacheMethodInterceptor(cache, config.strategy(), keyExtractor));
    }
}
