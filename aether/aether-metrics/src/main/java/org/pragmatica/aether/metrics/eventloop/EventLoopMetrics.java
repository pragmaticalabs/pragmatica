// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.eventloop;

public record EventLoopMetrics(long lagNanos, int pendingTasks, int activeChannels, boolean healthy) {
    public static final EventLoopMetrics EMPTY = new EventLoopMetrics(0, 0, 0, true);
    public static final long DEFAULT_HEALTH_THRESHOLD_NS = 10_000_000L;

    public double lagMs() {
        return lagNanos / 1_000_000.0;
    }

    public boolean isOverloaded(long thresholdNs) {
        return lagNanos > thresholdNs;
    }

    public double saturation(long thresholdNs) {
        if (thresholdNs <= 0) {
            return 0.0;
        }

        return Math.min(1.0, lagNanos / (double) thresholdNs);
    }
}
