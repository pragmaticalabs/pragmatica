package org.pragmatica.aether.metrics;
/**
 * Pre-computed derived metrics from raw measurements.
 * <p>
 * These metrics are calculated from recent samples to provide:
 * <ul>
 *   <li>Rates (per second)</li>
 *   <li>Percentiles</li>
 *   <li>Saturation signals</li>
 *   <li>Trend indicators</li>
 * </ul>
 *
 * @param requestRate          Invocations per second
 * @param errorRate            Errors per second
 * @param gcRate               GC events per second
 * @param latencyP50           50th percentile latency (ms)
 * @param latencyP95           95th percentile latency (ms)
 * @param latencyP99           99th percentile latency (ms)
 * @param eventLoopSaturation  Event loop saturation (0.0-1.0, lag/threshold)
 * @param heapSaturation       Heap saturation (0.0-1.0, used/max)
 * @param backpressureRate     Backpressure events per second
 * @param cpuTrend             CPU usage trend (positive = increasing)
 * @param latencyTrend         Latency trend (positive = increasing)
 * @param errorTrend           Error rate trend (positive = increasing)
 */
public record DerivedMetrics(// Rates (per second)
double requestRate,
double errorRate,
double gcRate,
// Percentiles
double latencyP50,
double latencyP95,
double latencyP99,
// Saturation signals
double eventLoopSaturation,
double heapSaturation,
double backpressureRate,
// Trends (1-minute deltas)
double cpuTrend,
double latencyTrend,
double errorTrend) {
    public static final DerivedMetrics EMPTY = new DerivedMetrics(0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0,
                                                                  0.0);

    /**
     * Overall system health score (0.0-1.0, higher is healthier).
     */
    public double healthScore() {
        // Weight factors for different metrics
        double latencyScore = Math.max(0, 1.0 - (latencyP99 / 1000.0));
        // 1s = 0
        double eventLoopScore = 1.0 - eventLoopSaturation;
        double heapScore = 1.0 - heapSaturation;
        double errorScore = Math.max(0, 1.0 - errorRate * 10);
        // 10% error = 0
        // Weighted average
        return ( latencyScore * 0.3 + eventLoopScore * 0.3 + heapScore * 0.2 + errorScore * 0.2);
    }

    /**
     * Check if system is under stress.
     */
    public boolean stressed() {
        return eventLoopSaturation > 0.7 || heapSaturation > 0.8 || errorRate > 0.05;
    }

    /**
     * Check if system has capacity for scaling up.
     */
    public boolean hasCapacity() {
        return eventLoopSaturation < 0.5 && heapSaturation < 0.6 && errorRate < 0.01;
    }

    /**
     * Check if trends indicate deteriorating performance.
     */
    public boolean deteriorating() {
        return cpuTrend > 0.1 || latencyTrend > 50.0 || errorTrend > 0.01;
    }

    /**
     * Check if trends indicate improving performance.
     */
    public boolean improving() {
        return cpuTrend < - 0.05 && latencyTrend < - 10.0 && errorTrend < 0;
    }
}
