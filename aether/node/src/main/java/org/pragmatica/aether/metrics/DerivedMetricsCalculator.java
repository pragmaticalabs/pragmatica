package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.utility.RingBuffer;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Calculates derived metrics from raw comprehensive snapshots.
 * <p>
 * Uses a sliding window of recent samples to compute rates, percentiles, and trends.
 */
public final class DerivedMetricsCalculator {
    private static final int DEFAULT_WINDOW_SIZE = 60;

    // 60 samples = 1 minute at 1/sec
    private static final long EVENT_LOOP_THRESHOLD_NS = EventLoopMetrics.DEFAULT_HEALTH_THRESHOLD_NS;

    private final RingBuffer<ComprehensiveSnapshot> samples;
    private final int windowSize;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private DerivedMetrics current = DerivedMetrics.EMPTY;

    private DerivedMetricsCalculator(int windowSize) {
        this.windowSize = windowSize;
        this.samples = RingBuffer.ringBuffer(windowSize);
    }

    public static DerivedMetricsCalculator derivedMetricsCalculator() {
        return new DerivedMetricsCalculator(DEFAULT_WINDOW_SIZE);
    }

    public static DerivedMetricsCalculator derivedMetricsCalculator(int windowSize) {
        return new DerivedMetricsCalculator(windowSize);
    }

    /**
     * Add a sample and recalculate derived metrics.
     */
    public void addSample(ComprehensiveSnapshot snapshot) {
        lock.writeLock()
            .lock();
        try{
            samples.add(snapshot);
            recalculate();
        } finally{
            lock.writeLock()
                .unlock();
        }
    }

    /**
     * Get current derived metrics.
     */
    public DerivedMetrics current() {
        lock.readLock()
            .lock();
        try{
            return current;
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    private void recalculate() {
        // Called with write lock held
        var sampleList = samples.toList();
        if (sampleList.isEmpty()) {
            current = DerivedMetrics.EMPTY;
            return;
        }
        int n = sampleList.size();
        // Calculate time window
        long firstTs = sampleList.getFirst()
                                 .timestamp();
        long lastTs = sampleList.getLast()
                                .timestamp();
        double windowSeconds = Math.max(1.0, (lastTs - firstTs) / 1000.0);
        // Calculate totals
        long totalInvocations = 0;
        long totalFailed = 0;
        long totalGc = 0;
        long totalBackpressure = 0;
        double sumCpu = 0;
        double sumLatency = 0;
        double sumHeapUsage = 0;
        double sumEventLoopLag = 0;
        for (var sample : sampleList) {
            totalInvocations += sample.totalInvocations();
            totalFailed += sample.failedInvocations();
            totalGc += sample.gc()
                             .totalGcCount();
            totalBackpressure += sample.network()
                                       .backpressureEvents();
            sumCpu += sample.cpuUsage();
            sumLatency += sample.avgLatencyMs();
            sumHeapUsage += sample.heapUsage();
            sumEventLoopLag += sample.eventLoop()
                                     .lagNanos();
        }
        // Rates
        double requestRate = totalInvocations / windowSeconds;
        double errorRate = totalFailed / windowSeconds;
        double gcRate = totalGc / windowSeconds;
        double backpressureRate = totalBackpressure / windowSeconds;
        // Averages
        double avgLatency = sumLatency / n;
        double avgHeapUsage = sumHeapUsage / n;
        double avgEventLoopLag = sumEventLoopLag / n;
        double avgCpu = sumCpu / n;
        // Percentiles (approximate from sorted latencies)
        double[] latencies = sampleList.stream()
                                       .mapToDouble(ComprehensiveSnapshot::avgLatencyMs)
                                       .sorted()
                                       .toArray();
        double p50 = percentile(latencies, 50);
        double p95 = percentile(latencies, 95);
        double p99 = percentile(latencies, 99);
        // Saturation
        double eventLoopSaturation = Math.min(1.0, avgEventLoopLag / EVENT_LOOP_THRESHOLD_NS);
        // Trends (compare first half to second half of window)
        double cpuTrend = 0;
        double latencyTrend = 0;
        double errorTrend = 0;
        if (n >= 10) {
            int halfN = n / 2;
            double firstHalfCpu = 0, secondHalfCpu = 0;
            double firstHalfLatency = 0, secondHalfLatency = 0;
            long firstHalfErrors = 0, secondHalfErrors = 0;
            for (int i = 0; i < halfN; i++) {
                var sample = sampleList.get(i);
                firstHalfCpu += sample.cpuUsage();
                firstHalfLatency += sample.avgLatencyMs();
                firstHalfErrors += sample.failedInvocations();
            }
            for (int i = halfN; i < n; i++) {
                var sample = sampleList.get(i);
                secondHalfCpu += sample.cpuUsage();
                secondHalfLatency += sample.avgLatencyMs();
                secondHalfErrors += sample.failedInvocations();
            }
            cpuTrend = (secondHalfCpu / (n - halfN)) - (firstHalfCpu / halfN);
            latencyTrend = (secondHalfLatency / (n - halfN)) - (firstHalfLatency / halfN);
            double firstHalfWindow = halfN / windowSeconds * n;
            double secondHalfWindow = (n - halfN) / windowSeconds * n;
            if (firstHalfWindow > 0 && secondHalfWindow > 0) {
                errorTrend = (secondHalfErrors / secondHalfWindow) - (firstHalfErrors / firstHalfWindow);
            }
        }
        current = new DerivedMetrics(requestRate,
                                     errorRate,
                                     gcRate,
                                     p50,
                                     p95,
                                     p99,
                                     eventLoopSaturation,
                                     avgHeapUsage,
                                     backpressureRate,
                                     cpuTrend,
                                     latencyTrend,
                                     errorTrend);
    }

    private double percentile(double[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
