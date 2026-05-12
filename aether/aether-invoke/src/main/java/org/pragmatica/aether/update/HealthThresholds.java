// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record HealthThresholds(double maxErrorRate, TimeSpan maxLatency, boolean requireManualApproval) {
    private static final Cause INVALID_ERROR_RATE = Causes.cause("Error rate must be between 0.0 and 1.0");

    private static final Cause NEGATIVE_LATENCY = Causes.cause("Latency must be non-negative");

    public static final HealthThresholds DEFAULT = new HealthThresholds(0.01, timeSpan(500).millis(), false);

    public static final HealthThresholds STRICT = new HealthThresholds(0.001, timeSpan(200).millis(), false);

    public static final HealthThresholds MANUAL_ONLY = new HealthThresholds(0.0, timeSpan(0).millis(), true);

    public static Result<HealthThresholds> healthThresholds(double maxErrorRate,
                                                            long maxLatencyMs,
                                                            boolean requireManualApproval) {
        if (maxErrorRate <0.0 || maxErrorRate > 1.0) {return INVALID_ERROR_RATE.result();}
        if (maxLatencyMs <0) {return NEGATIVE_LATENCY.result();}
        return Result.success(new HealthThresholds(maxErrorRate, timeSpan(maxLatencyMs).millis(), requireManualApproval));
    }

    public static Result<HealthThresholds> withErrorRate(double maxErrorRate) {
        return healthThresholds(maxErrorRate, DEFAULT.maxLatency.millis(), false);
    }

    public static Result<HealthThresholds> withLatency(long maxLatencyMs) {
        return healthThresholds(DEFAULT.maxErrorRate, maxLatencyMs, false);
    }

    public boolean isHealthy(double errorRate, long latencyMs) {
        if (requireManualApproval) {return false;}
        return errorRate <= maxErrorRate && latencyMs <= maxLatency.millis();
    }

    public HealthThresholds withManualApproval() {
        return new HealthThresholds(maxErrorRate, maxLatency, true);
    }

    public HealthThresholds withAutoApproval() {
        return new HealthThresholds(maxErrorRate, maxLatency, false);
    }
}
