// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;


@SuppressWarnings("unchecked") public record CacheMethodInterceptor(CacheBackend cache,
                                                                    CacheStrategy strategy,
                                                                    Fn1<Object, ?> keyExtractor) implements MethodInterceptor {
    @Override public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return switch (strategy){
            case CACHE_ASIDE, READ_THROUGH -> cacheAside(method);
            case WRITE_THROUGH -> writeThrough(method);
            case WRITE_BACK -> writeBack(method);
            case WRITE_AROUND -> writeAround(method);
        };
    }

    private <R, T> Fn1<Promise<R>, T> cacheAside(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);
            return cache.get(key)
                            .flatMap(opt -> opt.map(cached -> Promise.<R>success((R) cached))
                                                   .or(() -> method.apply(request)
                                                                         .onSuccess(value -> cache.put(key, value))));
        };
    }

    private <R, T> Fn1<Promise<R>, T> writeThrough(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);
            return method.apply(request).flatMap(result -> cache.put(key, result).map(_ -> result));
        };
    }

    private <R, T> Fn1<Promise<R>, T> writeBack(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);
            return method.apply(request).onSuccess(value -> cache.put(key, value));
        };
    }

    private <R, T> Fn1<Promise<R>, T> writeAround(Fn1<Promise<R>, T> method) {
        return request -> {
            var key = extractKey(request);
            return method.apply(request).onSuccess(_ -> cache.remove(key));
        };
    }

    @SuppressWarnings("unchecked") private <T> Object extractKey(T request) {
        return ((Fn1<Object, T>)(Fn1<?, ?>) keyExtractor).apply(request);
    }
}
