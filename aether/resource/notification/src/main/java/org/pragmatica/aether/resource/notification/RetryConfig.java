// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.io.TimeSpan;

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
}
