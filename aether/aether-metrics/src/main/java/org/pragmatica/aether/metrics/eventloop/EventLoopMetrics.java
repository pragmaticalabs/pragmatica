package org.pragmatica.aether.metrics.eventloop;
/**
 * Snapshot of Netty event loop metrics for observability.
 *
 * @param lagNanos      Time from task submit to execution (event loop latency)
 * @param pendingTasks  Number of tasks waiting in event loop queue
 * @param activeChannels Number of active channels across all event loops
 * @param healthy       Whether event loop is healthy (lag < threshold)
 */
public record EventLoopMetrics(long lagNanos,
                               int pendingTasks,
                               int activeChannels,
                               boolean healthy) {
    public static final EventLoopMetrics EMPTY = new EventLoopMetrics(0, 0, 0, true);

    // Default threshold: 10ms = unhealthy event loop
    public static final long DEFAULT_HEALTH_THRESHOLD_NS = 10_000_000L;

    /**
     * Event loop lag in milliseconds.
     */
    public double lagMs() {
        return lagNanos / 1_000_000.0;
    }

    /**
     * Check if event loop lag exceeds threshold.
     */
    public boolean isOverloaded(long thresholdNs) {
        return lagNanos > thresholdNs;
    }

    /**
     * Calculate saturation as ratio of lag to threshold.
     */
    public double saturation(long thresholdNs) {
        if (thresholdNs <= 0) {
            return 0.0;
        }
        return Math.min(1.0, lagNanos / (double) thresholdNs);
    }
}
