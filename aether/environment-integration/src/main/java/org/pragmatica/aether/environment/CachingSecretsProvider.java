package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.ConcurrentHashMap;

/// TTL-cached wrapper for any SecretsProvider.
/// Caches resolved secrets in memory and evicts entries after the configured TTL expires.
public record CachingSecretsProvider(SecretsProvider delegate,
                                      TimeSpan ttl,
                                      ConcurrentHashMap<String, CachedEntry> cache) implements SecretsProvider {
    record CachedEntry(String value, long expiresAtMillis) {}

    public static CachingSecretsProvider cachingSecretsProvider(SecretsProvider delegate, TimeSpan ttl) {
        return new CachingSecretsProvider(delegate, ttl, new ConcurrentHashMap<>());
    }

    @Override
    public Promise<String> resolveSecret(String secretPath) {
        return lookupCached(secretPath)
            .orElse(() -> delegateAndCache(secretPath));
    }

    private Promise<String> delegateAndCache(String secretPath) {
        return delegate.resolveSecret(secretPath)
                       .onSuccess(value -> cacheValue(secretPath, value));
    }

    private void cacheValue(String secretPath, String value) {
        cache.put(secretPath, new CachedEntry(value, System.currentTimeMillis() + ttl.millis()));
    }

    private Promise<String> lookupCached(String secretPath) {
        var cached = cache.get(secretPath);
        return isFresh(cached)
               ? Promise.success(cached.value())
               : expireAndMiss(secretPath);
    }

    private Promise<String> expireAndMiss(String secretPath) {
        cache.remove(secretPath);
        return EnvironmentError.secretResolutionFailed(secretPath,
                                                        new IllegalStateException("Cache miss"))
                               .promise();
    }

    private static boolean isFresh(CachedEntry cached) {
        return cached != null && cached.expiresAtMillis() > System.currentTimeMillis();
    }
}
