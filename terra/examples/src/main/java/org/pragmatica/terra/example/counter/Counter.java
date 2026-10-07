// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example.counter;

import java.lang.annotation.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


@Slice
public interface Counter {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.calls")
    @interface Cached {}

    @Cached
    Promise<Integer> value(String key);

    static org.pragmatica.lang.Result<Counter> counter() {
        var counter = new AtomicInteger();

        return org.pragmatica.lang.Result.success(_ -> Promise.success(counter.incrementAndGet()));
    }
}
