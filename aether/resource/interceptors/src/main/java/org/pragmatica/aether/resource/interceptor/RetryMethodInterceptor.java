// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Retry;


public record RetryMethodInterceptor(Retry retry) implements MethodInterceptor {
    @Override public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> retry.execute(() -> method.apply(request));
    }
}
