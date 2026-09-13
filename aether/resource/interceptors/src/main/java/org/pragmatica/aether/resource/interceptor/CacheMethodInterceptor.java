// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Fail-open by construction (#279): the cache is an accelerator, not a dependency. A backend that
/// cannot answer a `get` reads as a MISS and the method runs; a backend that cannot take a `put`
/// does not fail a call whose business work already succeeded. The failure is logged at DEBUG —
/// a per-call WARN during a DHT outage would be an unbounded log on the hottest path (#718's
/// lesson); the outage itself is visible on the backend's own side.
@SuppressWarnings("unchecked")
public record CacheMethodInterceptor(CacheBackend cache,
                                     CacheStrategy strategy,
                                     Fn1<Object, ?> keyExtractor,
                                     Option<String> cacheName) implements MethodInterceptor {
    private static final Logger log = LoggerFactory.getLogger(CacheMethodInterceptor.class);

    public CacheMethodInterceptor(CacheBackend cache, CacheStrategy strategy, Fn1<Object, ?> keyExtractor) {
        this(cache, strategy, keyExtractor, Option.empty());
    }

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return switch (strategy) {
            case CACHE_ASIDE, READ_THROUGH -> cacheAside(method);
            case WRITE_THROUGH -> writeThrough(method);
            case WRITE_BACK -> writeBack(method);
            case WRITE_AROUND -> writeAround(method);
        };
    }

    private <R, T> Fn1<Promise<R>, T> cacheAside(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);

            return lookup(key).flatMap(opt -> opt.map(cached -> Promise.<R> success((R) cached))
                                                 .or(() -> method.apply(request)
                                                                 .onSuccess(value -> store(key, value))));
        };
    }

    private <R, T> Fn1<Promise<R>, T> writeThrough(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);

            return method.apply(request)
                         .flatMap(result -> store(key, result).map(_ -> result));
        };
    }

    private <R, T> Fn1<Promise<R>, T> writeBack(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);

            return method.apply(request)
                         .onSuccess(value -> store(key, value));
        };
    }

    private <R, T> Fn1<Promise<R>, T> writeAround(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);

            return method.apply(request)
                         .onSuccess(_ -> cache.remove(key));
        };
    }

    /// A backend failure on read is a miss.
    private Promise<Option<Object>> lookup(Object key) {
        return cache.get(key)
                    .recover(cause -> missBecause("get", key, cause));
    }

    /// A backend failure on write is absorbed; the method's result stands. Whatever the cache held
    /// for the key before is now stale against a write that DID happen, so a best-effort `remove`
    /// follows the failed put — also absorbed, since a full outage fails that too (review of
    /// #1084, N-1). Every strategy's put goes through here, so every dropped put is logged the
    /// same way (N-2).
    private Promise<Unit> store(Object key, Object value) {
        return cache.put(key, value)
                    .fold(result -> result.fold(cause -> invalidateAfterFailedPut(key, cause),
                                                Promise::success));
    }

    private Promise<Unit> invalidateAfterFailedPut(Object key, Cause cause) {
        skippedBecause("put", key, cause);

        return cache.remove(key).recover(removeCause -> skippedBecause("remove", key, removeCause));
    }

    private Option<Object> missBecause(String operation, Object key, Cause cause) {
        log.debug("Cache {} {} failed for key {}, treating as a miss: {}",
                  cacheName.or("<unnamed>"),
                  operation,
                  key,
                  cause.message());

        return Option.none();
    }

    private Unit skippedBecause(String operation, Object key, Cause cause) {
        log.debug("Cache {} {} failed for key {}, entry not cached: {}",
                  cacheName.or("<unnamed>"),
                  operation,
                  key,
                  cause.message());

        return Unit.unit();
    }

    @SuppressWarnings("unchecked")
    private <T> Object extractKey(T request) {
        return ((Fn1<Object, T>)(Fn1<?, ?>) keyExtractor).apply(request);
    }
}
