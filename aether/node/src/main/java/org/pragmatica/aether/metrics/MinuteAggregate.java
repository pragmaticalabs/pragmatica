package org.pragmatica.aether.metrics;
/**
 * Pre-computed minute-level aggregate for TTM (Tiny Time Mixer) input.
 * <p>
 * Aggregates 60 {@link ComprehensiveSnapshot} entries into statistical summaries
 * suitable for time-series forecasting models.
 *
 * @param minuteTimestamp Timestamp of the minute start (aligned to minute boundary)
 * @param avgCpuUsage     Average CPU usage over the minute (0.0-1.0)
 * @param avgHeapUsage    Average heap usage ratio (0.0-1.0)
 * @param avgEventLoopLagMs Average event loop lag in milliseconds
 * @param avgLatencyMs    Average invocation latency in milliseconds
 * @param totalInvocations Total invocations during the minute
 * @param totalGcPauseMs  Total GC pause time during the minute
 * @param latencyP50      50th percentile latency in milliseconds
 * @param latencyP95      95th percentile latency in milliseconds
 * @param latencyP99      99th percentile latency in milliseconds
 * @param errorRate       Error rate ratio (0.0-1.0)
 * @param eventCount      Number of cluster events during the minute
 * @param sampleCount     Number of samples aggregated (up to 60)
 */
public record MinuteAggregate(long minuteTimestamp,
                              double avgCpuUsage,
                              double avgHeapUsage,
                              double avgEventLoopLagMs,
                              double avgLatencyMs,
                              long totalInvocations,
                              long totalGcPauseMs,
                              double latencyP50,
                              double latencyP95,
                              double latencyP99,
                              double errorRate,
                              int eventCount,
                              int sampleCount) {
    public static final MinuteAggregate EMPTY = new MinuteAggregate(0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0,
                                                                    0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0.0,
                                                                    0,
                                                                    0);

    /**
     * Factory method following JBCT naming convention.
     */
    public static MinuteAggregate minuteAggregate(long minuteTimestamp,
                                                  double avgCpuUsage,
                                                  double avgHeapUsage,
                                                  double avgEventLoopLagMs,
                                                  double avgLatencyMs,
                                                  long totalInvocations,
                                                  long totalGcPauseMs,
                                                  double latencyP50,
                                                  double latencyP95,
                                                  double latencyP99,
                                                  double errorRate,
                                                  int eventCount,
                                                  int sampleCount) {
        return new MinuteAggregate(minuteTimestamp,
                                   avgCpuUsage,
                                   avgHeapUsage,
                                   avgEventLoopLagMs,
                                   avgLatencyMs,
                                   totalInvocations,
                                   totalGcPauseMs,
                                   latencyP50,
                                   latencyP95,
                                   latencyP99,
                                   errorRate,
                                   eventCount,
                                   sampleCount);
    }

    /**
     * Align timestamp to minute boundary.
     */
    public static long alignToMinute(long timestamp) {
        return ( timestamp / 60_000L) * 60_000L;
    }

    /**
     * Check if this aggregate has enough samples to be useful.
     */
    public boolean hasData() {
        return sampleCount > 0;
    }

    /**
     * Check if this aggregate is healthy (no concerning patterns).
     */
    public boolean healthy() {
        return errorRate < 0.1 && avgHeapUsage < 0.9 && avgEventLoopLagMs < 10.0;
    }

    /**
     * Convert to float array for TTM input.
     * Order: [cpuUsage, heapUsage, eventLoopLag, latency, invocations, gcPause, p50, p95, p99, errorRate, events]
     */
    public float[] toFeatureArray() {
        return new float[] {(float) avgCpuUsage,
        (float) avgHeapUsage,
        (float) avgEventLoopLagMs,
        (float) avgLatencyMs,
        (float) totalInvocations,
        (float) totalGcPauseMs,
        (float) latencyP50,
        (float) latencyP95,
        (float) latencyP99,
        (float) errorRate,
        (float) eventCount};
    }

    /**
     * Feature names matching toFeatureArray() order.
     */
    public static String[] featureNames() {
        return new String[] {"cpu_usage",
        "heap_usage",
        "event_loop_lag_ms",
        "latency_ms",
        "invocations",
        "gc_pause_ms",
        "latency_p50",
        "latency_p95",
        "latency_p99",
        "error_rate",
        "event_count"};
    }
}
