package org.pragmatica.aether.metrics;

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

    public static long alignToMinute(long timestamp) {
        return (timestamp / 60_000L) * 60_000L;
    }

    public boolean hasData() {
        return sampleCount > 0;
    }

    public boolean healthy() {
        return errorRate <0.1 && avgHeapUsage <0.9 && avgEventLoopLagMs <10.0;
    }

    public float[] toFeatureArray() {
        return new float[]{(float) avgCpuUsage, (float) avgHeapUsage, (float) avgEventLoopLagMs, (float) avgLatencyMs, (float) totalInvocations, (float) totalGcPauseMs, (float) latencyP50, (float) latencyP95, (float) latencyP99, (float) errorRate, (float) eventCount};
    }

    public static String[] featureNames() {
        return new String[]{"cpu_usage", "heap_usage", "event_loop_lag_ms", "latency_ms", "invocations", "gc_pause_ms", "latency_p50", "latency_p95", "latency_p99", "error_rate", "event_count"};
    }
}
