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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Retries through core [Retry], but only failures the configured [RetryOn] policy admits. A
/// failure the policy refuses is returned as-is after the first attempt WITHOUT entering `Retry`,
/// so a business verdict is neither re-driven nor logged as a retry giving up (#280 R26).
///
/// The first attempt is made here and `retry` carries the REMAINING budget (`maxAttempts - 1`,
/// see `RetryInterceptorFactory`), so the method sees exactly `maxAttempts` calls in total. One
/// consequence to know when reading logs: `Retry`'s own "failed after N of M attempts" line counts
/// its M, which is one less than the configured `max_attempts`.
public record RetryMethodInterceptor(Retry retry, int remainingAttempts, RetryOn retryOn) implements MethodInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RetryMethodInterceptor.class);

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> method.apply(request)
                                .fold(result -> result.fold(cause -> retryOrReturn(method, request, cause),
                                                            Promise::success));
    }

    private <R, T> Promise<R> retryOrReturn(Fn1<Promise<R>, T> method, T request, Cause cause) {
        if (remainingAttempts > 0 && retryOn.retries(cause)) {
            return retry.execute(() -> method.apply(request));
        }

        return declined(cause).promise();
    }

    /// A failure the policy declined is returned unchanged, with ONE DEBUG line naming the cause
    /// type and the policy — a retry that silently becomes a no-op is the silent-wrong-state class,
    /// and a WARN per business failure is the #718 flood; DEBUG through SLF4J is the level an
    /// operator can raise for one logger when a retry "stopped working" (#280).
    private Cause declined(Cause cause) {
        if (!cause.isTerminal()) {
            log.debug("Retry declined for {} under retry_on={}: cause is not classified transient (mark it Cause.Transient, or set retry_on=NON_TERMINAL)",
                      cause.getClass().getName(),
                      retryOn);
        }

        return cause;
    }
}
