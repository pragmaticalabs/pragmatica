package org.pragmatica.aether.metrics;

public record DerivedMetrics(
// Rates (per second)
double requestRate,
double errorRate,
double gcRate,
double latencyP50,
double latencyP95,
double latencyP99,
double eventLoopSaturation,
double heapSaturation,
double backpressureRate,
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

    public double healthScore() {
        double latencyScore = Math.max(0, 1.0 - (latencyP99 / 1000.0));
        double eventLoopScore = 1.0 - eventLoopSaturation;
        double heapScore = 1.0 - heapSaturation;
        double errorScore = Math.max(0, 1.0 - errorRate * 10);
        return (latencyScore * 0.3 + eventLoopScore * 0.3 + heapScore * 0.2 + errorScore * 0.2);
    }

    public boolean stressed() {
        return eventLoopSaturation > 0.7 || heapSaturation > 0.8 || errorRate > 0.05;
    }

    public boolean hasCapacity() {
        return eventLoopSaturation <0.5 && heapSaturation <0.6 && errorRate <0.01;
    }

    public boolean deteriorating() {
        return cpuTrend > 0.1 || latencyTrend > 50.0 || errorTrend > 0.01;
    }

    public boolean improving() {
        return cpuTrend <- 0.05 && latencyTrend <- 10.0 && errorTrend <0;
    }
}
