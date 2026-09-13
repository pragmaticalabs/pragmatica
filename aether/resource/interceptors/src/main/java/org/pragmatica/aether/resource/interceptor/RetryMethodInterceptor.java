// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Retry;


/// Retries through core [Retry], but only failures the configured [RetryOn] policy admits. A
/// failure the policy refuses is returned as-is after the first attempt WITHOUT entering `Retry`,
/// so a business verdict is neither re-driven nor logged as a retry giving up (#280 R26).
///
/// The first attempt is made here and `retry` carries the REMAINING budget (`maxAttempts - 1`,
/// see `RetryInterceptorFactory`), so the method sees exactly `maxAttempts` calls in total. One
/// consequence to know when reading logs: `Retry`'s own "failed after N of M attempts" line counts
/// its M, which is one less than the configured `max_attempts`.
public record RetryMethodInterceptor(Retry retry, int remainingAttempts, RetryOn retryOn) implements MethodInterceptor {
    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> method.apply(request)
                                .fold(result -> result.fold(cause -> retryOrReturn(method, request, cause),
                                                            Promise::success));
    }

    private <R, T> Promise<R> retryOrReturn(Fn1<Promise<R>, T> method, T request, Cause cause) {
        return remainingAttempts > 0 && retryOn.retries(cause)
               ? retry.execute(() -> method.apply(request))
               : cause.promise();
    }
}
