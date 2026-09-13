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


/// Retries through core [Retry] under the configured [RetryOn] policy, evaluated on EVERY failure:
/// a failure the policy refuses ends the call at once with that cause, on attempt one or attempt
/// N alike. (Round-1 of #280 applied the policy to the first failure only and then handed the
/// budget to a loop that stops solely on `isTerminal()`, so a business verdict on attempt two was
/// re-driven — review of #1088, B1.) `max_attempts` counts calls at the method, the first included.
public record RetryMethodInterceptor(Retry retry, RetryOn retryOn) implements MethodInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RetryMethodInterceptor.class);

    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> retry.execute(() -> method.apply(request), this::admits);
    }

    /// The policy, with ONE DEBUG line when it declines a non-terminal cause: a retry that silently
    /// becomes a no-op is the silent-wrong-state class, and a WARN per business failure is the #718
    /// flood; DEBUG through SLF4J is the level an operator raises for one logger when a retry
    /// "stopped working" (#280). A terminal cause is refused by `Retry` before this runs and is not
    /// logged here — it is the documented never-retry state, not a surprise.
    private boolean admits(Cause cause) {
        var admitted = retryOn.retries(cause);

        if (!admitted && !cause.isTerminal()) {
            log.debug("Retry declined for {} under retry_on={}: cause is not classified transient (mark it Cause.Transient, or set retry_on=NON_TERMINAL)",
                      cause.getClass().getName(),
                      retryOn);
        }

        return admitted;
    }
}
