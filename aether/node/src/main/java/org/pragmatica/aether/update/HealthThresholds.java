package org.pragmatica.aether.update;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/**
 * Health thresholds for automatic rolling update progression.
 *
 * <p>An update can progress automatically if health criteria are met:
 * <ul>
 *   <li>Error rate below threshold</li>
 *   <li>Latency below threshold</li>
 *   <li>Or manual approval (bypasses automatic checks)</li>
 * </ul>
 *
 * @param maxErrorRate maximum allowed error rate (0.0-1.0, default 0.01 = 1%)
 * @param maxLatencyMs maximum allowed p99 latency in milliseconds (default 500ms)
 * @param requireManualApproval if true, requires explicit approval regardless of metrics
 */
public record HealthThresholds(double maxErrorRate,
                               long maxLatencyMs,
                               boolean requireManualApproval) {
    private static final Cause INVALID_ERROR_RATE = Causes.cause("Error rate must be between 0.0 and 1.0");
    private static final Cause NEGATIVE_LATENCY = Causes.cause("Latency must be non-negative");

    /**
     * Default thresholds: 1% error rate, 500ms latency, no manual approval required.
     */
    public static final HealthThresholds DEFAULT = new HealthThresholds(0.01, 500, false);

    /**
     * Strict thresholds for critical services: 0.1% error rate, 200ms latency.
     */
    public static final HealthThresholds STRICT = new HealthThresholds(0.001, 200, false);

    /**
     * Manual-only: always requires manual approval.
     */
    public static final HealthThresholds MANUAL_ONLY = new HealthThresholds(0.0, 0, true);

    /**
     * Creates health thresholds with validation.
     *
     * @return health thresholds, or failure if values are invalid
     */
    public static Result<HealthThresholds> healthThresholds(double maxErrorRate,
                                                            long maxLatencyMs,
                                                            boolean requireManualApproval) {
        if (maxErrorRate < 0.0 || maxErrorRate > 1.0) {
            return INVALID_ERROR_RATE.result();
        }
        if (maxLatencyMs < 0) {
            return NEGATIVE_LATENCY.result();
        }
        return Result.success(new HealthThresholds(maxErrorRate, maxLatencyMs, requireManualApproval));
    }

    /**
     * Creates thresholds with default values and custom error rate.
     */
    public static Result<HealthThresholds> withErrorRate(double maxErrorRate) {
        return healthThresholds(maxErrorRate, DEFAULT.maxLatencyMs, false);
    }

    /**
     * Creates thresholds with default values and custom latency.
     */
    public static Result<HealthThresholds> withLatency(long maxLatencyMs) {
        return healthThresholds(DEFAULT.maxErrorRate, maxLatencyMs, false);
    }

    /**
     * Checks if the given metrics meet the health criteria.
     *
     * @param errorRate current error rate
     * @param latencyMs current p99 latency
     * @return true if healthy, false otherwise
     */
    public boolean isHealthy(double errorRate, long latencyMs) {
        if (requireManualApproval) {
            return false;
        }
        return errorRate <= maxErrorRate && latencyMs <= maxLatencyMs;
    }

    /**
     * Returns a copy with manual approval required.
     */
    public HealthThresholds withManualApproval() {
        return new HealthThresholds(maxErrorRate, maxLatencyMs, true);
    }

    /**
     * Returns a copy without manual approval requirement.
     */
    public HealthThresholds withAutoApproval() {
        return new HealthThresholds(maxErrorRate, maxLatencyMs, false);
    }
}
