package org.pragmatica.aether.environment;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.option;


/// TTL-cached wrapper for any SecretsProvider.
/// Caches resolved secrets in memory and evicts entries after the configured TTL expires.
public record CachingSecretsProvider(SecretsProvider delegate,
                                     TimeSpan ttl,
                                     ConcurrentHashMap<String, CachedEntry> cache) implements SecretsProvider {
    record CachedEntry(String value, long expiresAtMillis){}

    public static CachingSecretsProvider cachingSecretsProvider(SecretsProvider delegate, TimeSpan ttl) {
        return new CachingSecretsProvider(delegate, ttl, new ConcurrentHashMap<>());
    }

    @Override public Promise<String> resolveSecret(String secretPath) {
        return lookupCached(secretPath).orElse(() -> delegateAndCache(secretPath));
    }

    private Promise<String> delegateAndCache(String secretPath) {
        return delegate.resolveSecret(secretPath).onSuccess(value -> cacheValue(secretPath, value));
    }

    @Contract private void cacheValue(String secretPath, String value) {
        cache.put(secretPath,
                  new CachedEntry(value,
                                  System.currentTimeMillis() + ttl.millis()));
    }

    private Promise<String> lookupCached(String secretPath) {
        return freshEntry(secretPath).fold(() -> expireAndMiss(secretPath),
                                           entry -> Promise.success(entry.value()));
    }

    private Promise<String> expireAndMiss(String secretPath) {
        cache.remove(secretPath);
        return EnvironmentError.secretResolutionFailed(secretPath, new IllegalStateException("Cache miss")).promise();
    }

    private Option<CachedEntry> freshEntry(String secretPath) {
        return option(cache.get(secretPath)).filter(entry -> entry.expiresAtMillis() > System.currentTimeMillis());
    }
}
