package org.pragmatica.aether.metrics;

import org.pragmatica.utility.RingBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Aggregates {@link ComprehensiveSnapshot} samples into minute-level summaries.
 * <p>
 * Features:
 * <ul>
 *   <li>Stores last 120 minutes (2-hour window)</li>
 *   <li>Automatic minute boundary alignment</li>
 *   <li>Percentile calculation from samples</li>
 *   <li>TTM-ready float[][] output</li>
 * </ul>
 */
public final class MinuteAggregator {
    private static final int DEFAULT_CAPACITY = 120;

    // 2 hours of minute aggregates
    private final RingBuffer<MinuteAggregate> aggregates;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // Accumulator for current minute
    private long currentMinute = 0;
    private final List<ComprehensiveSnapshot> currentSamples = new ArrayList<>(60);
    private final List<Double> currentLatencies = new ArrayList<>(60);

    private MinuteAggregator(int capacity) {
        this.aggregates = RingBuffer.ringBuffer(capacity);
    }

    public static MinuteAggregator minuteAggregator() {
        return new MinuteAggregator(DEFAULT_CAPACITY);
    }

    public static MinuteAggregator minuteAggregator(int capacity) {
        return new MinuteAggregator(capacity);
    }

    /**
     * Add a snapshot sample. Automatically rolls over on minute boundaries.
     */
    public void addSample(ComprehensiveSnapshot snapshot) {
        lock.writeLock()
            .lock();
        try{
            long minute = MinuteAggregate.alignToMinute(snapshot.timestamp());
            // Check for minute rollover
            if (currentMinute != minute && !currentSamples.isEmpty()) {
                finalizeCurrentMinute();
            }
            currentMinute = minute;
            currentSamples.add(snapshot);
            if (snapshot.avgLatencyMs() > 0) {
                currentLatencies.add(snapshot.avgLatencyMs());
            }
        } finally{
            lock.writeLock()
                .unlock();
        }
    }

    /**
     * Force finalization of current minute (useful at shutdown).
     */
    public void flush() {
        lock.writeLock()
            .lock();
        try{
            if (!currentSamples.isEmpty()) {
                finalizeCurrentMinute();
            }
        } finally{
            lock.writeLock()
                .unlock();
        }
    }

    /**
     * Get all minute aggregates.
     */
    public List<MinuteAggregate> all() {
        lock.readLock()
            .lock();
        try{
            return aggregates.toList();
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    /**
     * Get recent N minute aggregates.
     */
    public List<MinuteAggregate> recent(int count) {
        lock.readLock()
            .lock();
        try{
            var all = aggregates.toList();
            if (all.size() <= count) {
                return all;
            }
            return all.subList(all.size() - count, all.size());
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    /**
     * Get aggregates since timestamp.
     */
    public List<MinuteAggregate> since(long timestamp) {
        lock.readLock()
            .lock();
        try{
            return aggregates.filter(a -> a.minuteTimestamp() >= timestamp);
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    /**
     * Convert to TTM input matrix.
     *
     * @param windowMinutes Number of minutes to include
     * @return float[windowMinutes][features] matrix, zero-padded if insufficient data
     */
    public float[][] toTTMInput(int windowMinutes) {
        lock.readLock()
            .lock();
        try{
            var recentAggregates = recent(windowMinutes);
            float[][] result = new float[windowMinutes][];
            // Initialize with zeros
            for (int i = 0; i < windowMinutes; i++) {
                result[i] = new float[MinuteAggregate.featureNames().length];
            }
            // Fill from the end (most recent last)
            int offset = windowMinutes - recentAggregates.size();
            for (int i = 0; i < recentAggregates.size(); i++) {
                result[offset + i] = recentAggregates.get(i)
                                                     .toFeatureArray();
            }
            return result;
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    /**
     * Get current sample count in accumulator.
     */
    public int currentSampleCount() {
        lock.readLock()
            .lock();
        try{
            return currentSamples.size();
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    /**
     * Get total aggregate count.
     */
    public int aggregateCount() {
        lock.readLock()
            .lock();
        try{
            return aggregates.size();
        } finally{
            lock.readLock()
                .unlock();
        }
    }

    private void finalizeCurrentMinute() {
        // Called with write lock held
        if (currentSamples.isEmpty()) {
            return;
        }
        // Calculate averages
        double sumCpu = 0, sumHeap = 0, sumLag = 0, sumLatency = 0;
        long sumInvocations = 0, sumGcPause = 0;
        double sumErrorRate = 0;
        for (var sample : currentSamples) {
            sumCpu += sample.cpuUsage();
            sumHeap += sample.heapUsage();
            sumLag += sample.eventLoop()
                            .lagMs();
            sumLatency += sample.avgLatencyMs();
            sumInvocations += sample.totalInvocations();
            sumGcPause += sample.gc()
                                .totalPauseMs();
            sumErrorRate += sample.errorRate();
        }
        int n = currentSamples.size();
        // Calculate percentiles from latencies
        double p50 = 0, p95 = 0, p99 = 0;
        if (!currentLatencies.isEmpty()) {
            double[] sorted = currentLatencies.stream()
                                              .mapToDouble(Double::doubleValue)
                                              .sorted()
                                              .toArray();
            p50 = percentile(sorted, 50);
            p95 = percentile(sorted, 95);
            p99 = percentile(sorted, 99);
        }
        int events = 0;
        var aggregate = new MinuteAggregate(currentMinute,
                                            sumCpu / n,
                                            sumHeap / n,
                                            sumLag / n,
                                            sumLatency / n,
                                            sumInvocations,
                                            sumGcPause,
                                            p50,
                                            p95,
                                            p99,
                                            sumErrorRate / n,
                                            events,
                                            n);
        aggregates.add(aggregate);
        // Reset accumulators
        currentSamples.clear();
        currentLatencies.clear();
    }

    private double percentile(double[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
