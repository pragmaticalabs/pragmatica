package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.RingBuffer;

import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.pragmatica.lang.Result.unitResult;

/// Calculates derived metrics from raw comprehensive snapshots.
///
/// Uses a sliding window of recent samples to compute rates, percentiles, and trends.
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

    /// Add a sample and recalculate derived metrics.
    public Result<Unit> addSample(ComprehensiveSnapshot snapshot) {
        lock.writeLock()
            .lock();
        try{
            samples.add(snapshot);
            recalculate();
        } finally{
            lock.writeLock()
                .unlock();
        }
        return unitResult();
    }

    /// Get current derived metrics.
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
        var sampleList = samples.toList();
        if (sampleList.isEmpty()) {
            current = DerivedMetrics.EMPTY;
            return;
        }
        int n = sampleList.size();
        double windowSeconds = calculateWindowSeconds(sampleList);
        var totals = accumulateTotals(sampleList);
        var rates = calculateRates(totals, windowSeconds, n);
        var percentiles = calculatePercentiles(sampleList);
        double eventLoopSaturation = Math.min(1.0, totals.sumEventLoopLag / n / EVENT_LOOP_THRESHOLD_NS);
        var trends = calculateTrends(sampleList, n, windowSeconds);
        current = new DerivedMetrics(rates.requestRate,
                                     rates.errorRate,
                                     rates.gcRate,
                                     percentiles.p50,
                                     percentiles.p95,
                                     percentiles.p99,
                                     eventLoopSaturation,
                                     totals.sumHeapUsage / n,
                                     rates.backpressureRate,
                                     trends.cpuTrend,
                                     trends.latencyTrend,
                                     trends.errorTrend);
    }

    private double calculateWindowSeconds(List<ComprehensiveSnapshot> sampleList) {
        long firstTs = sampleList.getFirst()
                                 .timestamp();
        long lastTs = sampleList.getLast()
                                .timestamp();
        return Math.max(1.0, (lastTs - firstTs) / 1000.0);
    }

    private Totals accumulateTotals(List<ComprehensiveSnapshot> sampleList) {
        long totalInvocations = 0, totalFailed = 0, totalGc = 0, totalBackpressure = 0;
        double sumLatency = 0, sumHeapUsage = 0, sumEventLoopLag = 0;
        for (var sample : sampleList) {
            totalInvocations += sample.totalInvocations();
            totalFailed += sample.failedInvocations();
            totalGc += sample.gc()
                             .totalGcCount();
            totalBackpressure += sample.network()
                                       .backpressureEvents();
            sumLatency += sample.avgLatencyMs();
            sumHeapUsage += sample.heapUsage();
            sumEventLoopLag += sample.eventLoop()
                                     .lagNanos();
        }
        return new Totals(totalInvocations,
                          totalFailed,
                          totalGc,
                          totalBackpressure,
                          sumLatency,
                          sumHeapUsage,
                          sumEventLoopLag);
    }

    private Rates calculateRates(Totals totals, double windowSeconds, int n) {
        return new Rates(totals.totalInvocations / windowSeconds,
                         totals.totalFailed / windowSeconds,
                         totals.totalGc / windowSeconds,
                         totals.totalBackpressure / windowSeconds);
    }

    private Percentiles calculatePercentiles(List<ComprehensiveSnapshot> sampleList) {
        double[] latencies = sampleList.stream()
                                       .mapToDouble(ComprehensiveSnapshot::avgLatencyMs)
                                       .sorted()
                                       .toArray();
        return new Percentiles(percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99));
    }

    private Trends calculateTrends(List<ComprehensiveSnapshot> sampleList, int n, double windowSeconds) {
        if (n < 10) {
            return new Trends(0, 0, 0);
        }
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
        double cpuTrend = (secondHalfCpu / (n - halfN)) - (firstHalfCpu / halfN);
        double latencyTrend = (secondHalfLatency / (n - halfN)) - (firstHalfLatency / halfN);
        double errorTrend = calculateErrorTrend(halfN, n, windowSeconds, firstHalfErrors, secondHalfErrors);
        return new Trends(cpuTrend, latencyTrend, errorTrend);
    }

    private double calculateErrorTrend(int halfN,
                                       int n,
                                       double windowSeconds,
                                       long firstHalfErrors,
                                       long secondHalfErrors) {
        double firstHalfWindow = halfN / windowSeconds * n;
        double secondHalfWindow = (n - halfN) / windowSeconds * n;
        if (firstHalfWindow > 0 && secondHalfWindow > 0) {
            return (secondHalfErrors / secondHalfWindow) - (firstHalfErrors / firstHalfWindow);
        }
        return 0;
    }

    private double percentile(double[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private record Totals(long totalInvocations,
                          long totalFailed,
                          long totalGc,
                          long totalBackpressure,
                          double sumLatency,
                          double sumHeapUsage,
                          double sumEventLoopLag) {}

    private record Rates(double requestRate, double errorRate, double gcRate, double backpressureRate) {}

    private record Percentiles(double p50, double p95, double p99) {}

    private record Trends(double cpuTrend, double latencyTrend, double errorTrend) {}
}
