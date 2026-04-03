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
        if (thresholdNs <= 0) {return 0.0;}
        return Math.min(1.0, lagNanos / (double) thresholdNs);
    }
}
