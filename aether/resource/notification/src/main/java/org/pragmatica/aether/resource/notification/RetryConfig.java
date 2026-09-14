// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record RetryConfig(int maxAttempts, TimeSpan initialDelay, TimeSpan maxDelay, double backoffMultiplier) {
    public static final RetryConfig DEFAULT = new RetryConfig(3, timeSpan(1).seconds(), timeSpan(30).seconds(), 2.0);

    public static RetryConfig retryConfig() {
        return DEFAULT;
    }

    public static RetryConfig retryConfig(int maxAttempts,
                                          long initialDelayMs,
                                          long maxDelayMs,
                                          double backoffMultiplier) {
        return new RetryConfig(maxAttempts,
                               timeSpan(initialDelayMs).millis(),
                               timeSpan(maxDelayMs).millis(),
                               backoffMultiplier);
    }

    /// The core [Retry] this config describes: exponential backoff on the shared scheduler, stopping
    /// early on a terminal cause. Both senders retry through this rather than each carrying its own
    /// loop, delay arithmetic and a virtual thread per sleep (#271 R9/R10).
    public Retry retry() {
        return Retry.retry()
                    .attempts(maxAttempts)
                    .strategy(BackoffStrategy.exponential()
                                             .initialDelay(initialDelay)
                                             .maxDelay(maxDelay)
                                             .factor(backoffMultiplier)
                                             .withoutJitter());
    }
}
