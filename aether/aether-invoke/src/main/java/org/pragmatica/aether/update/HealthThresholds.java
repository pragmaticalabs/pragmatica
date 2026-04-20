// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Health thresholds for automatic rolling update progression.
///
/// An update can progress automatically if health criteria are met:
///
///   - Error rate below threshold
///   - Latency below threshold
///   - Or manual approval (bypasses automatic checks)
///
/// @param maxErrorRate maximum allowed error rate (0.0-1.0, default 0.01 = 1%)
/// @param maxLatencyMs maximum allowed p99 latency in milliseconds (default 500ms)
/// @param requireManualApproval if true, requires explicit approval regardless of metrics
public record HealthThresholds(double maxErrorRate, long maxLatencyMs, boolean requireManualApproval) {
    private static final Cause INVALID_ERROR_RATE = Causes.cause("Error rate must be between 0.0 and 1.0");

    private static final Cause NEGATIVE_LATENCY = Causes.cause("Latency must be non-negative");

    @SuppressWarnings("JBCT-VO-02") public static final HealthThresholds DEFAULT = new HealthThresholds(0.01, 500, false);

    @SuppressWarnings("JBCT-VO-02") public static final HealthThresholds STRICT = new HealthThresholds(0.001, 200, false);

    @SuppressWarnings("JBCT-VO-02") public static final HealthThresholds MANUAL_ONLY = new HealthThresholds(0.0, 0, true);

    public static Result<HealthThresholds> healthThresholds(double maxErrorRate,
                                                            long maxLatencyMs,
                                                            boolean requireManualApproval) {
        if (maxErrorRate <0.0 || maxErrorRate > 1.0) {return INVALID_ERROR_RATE.result();}
        if (maxLatencyMs <0) {return NEGATIVE_LATENCY.result();}
        return Result.success(new HealthThresholds(maxErrorRate, maxLatencyMs, requireManualApproval));
    }

    public static Result<HealthThresholds> withErrorRate(double maxErrorRate) {
        return healthThresholds(maxErrorRate, DEFAULT.maxLatencyMs, false);
    }

    public static Result<HealthThresholds> withLatency(long maxLatencyMs) {
        return healthThresholds(DEFAULT.maxErrorRate, maxLatencyMs, false);
    }

    public boolean isHealthy(double errorRate, long latencyMs) {
        if (requireManualApproval) {return false;}
        return errorRate <= maxErrorRate && latencyMs <= maxLatencyMs;
    }

    @SuppressWarnings("JBCT-VO-02") public HealthThresholds withManualApproval() {
        return new HealthThresholds(maxErrorRate, maxLatencyMs, true);
    }

    @SuppressWarnings("JBCT-VO-02") public HealthThresholds withAutoApproval() {
        return new HealthThresholds(maxErrorRate, maxLatencyMs, false);
    }
}
