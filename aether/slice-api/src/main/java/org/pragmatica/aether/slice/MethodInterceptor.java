// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;


/// Interceptor for slice method invocations.
///
/// Unlike {@link Aspect}, which wraps entire slice instances,
/// MethodInterceptor operates at the individual method level,
/// allowing fine-grained control over method execution (e.g.,
/// adding metrics, tracing, or access control per method).
///
/// Example usage:
/// ```{@code
/// MethodInterceptor metricsInterceptor = new MethodInterceptor() {
///     @Override
///     public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
///         return request -> {
///             var start = System.nanoTime();
///             return method.apply(request)
///                          .onSuccess(_ -> recordLatency(System.nanoTime() - start));
///         };
///     }
/// };
/// }```
@FunctionalInterface public interface MethodInterceptor {
    <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method);
}
