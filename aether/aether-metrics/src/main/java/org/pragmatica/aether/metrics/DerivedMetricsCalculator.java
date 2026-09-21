// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.RingBuffer;

import static org.pragmatica.lang.Result.unitResult;


public final class DerivedMetricsCalculator {
    private final RingBuffer<ComprehensiveSnapshot> samples;
    private DerivedMetrics current = DerivedMetrics.EMPTY;

    private DerivedMetricsCalculator(int windowSize) {
        samples = RingBuffer.ringBuffer(windowSize);
    }

    public static DerivedMetricsCalculator derivedMetricsCalculator() {
        return derivedMetricsCalculator(60);
    }

    public static DerivedMetricsCalculator derivedMetricsCalculator(int windowSize) {
        return new DerivedMetricsCalculator(windowSize);
    }

    public synchronized Result<Unit> addSample(ComprehensiveSnapshot snapshot) {
        var existing = samples.toList();

        if (existing.isEmpty() || snapshot.timestamp() > existing.getLast().timestamp()) {
            samples.add(snapshot);
            current = calculate(samples.toList());
        }

        return unitResult();
    }

    public synchronized DerivedMetrics current() {
        return current;
    }

    private static DerivedMetrics calculate(List<ComprehensiveSnapshot> samples) {
        var intervals = IntStream.range(1,
                                        samples.size())
                                 .mapToObj(i -> MetricInterval.metricInterval(samples.get(i - 1),
                                                                              samples.get(i)))
                                 .toList();
        var seconds = intervals.stream().mapToDouble(interval -> interval.elapsed()
                                                                         .nanos() / 1_000_000_000.0).sum();
        var calls = intervals.stream().mapToLong(MetricInterval::invocations).sum();
        var failures = intervals.stream().mapToLong(MetricInterval::failures).sum();
        var gcCount = intervals.stream().mapToLong(MetricInterval::gcCount).sum();
        var means = intervals.stream()
                             .filter(interval -> interval.invocations() > 0)
                             .mapToDouble(interval -> interval.meanLatency()
                                                              .nanos() / 1_000_000.0)
                             .sorted()
                             .toArray();
        var lag = samples.stream().mapToDouble(sample -> sample.eventLoop()
                                                               .lagNanos()).average().orElse(0);
        var heap = samples.stream().mapToDouble(ComprehensiveSnapshot::heapUsage).average().orElse(0);

        return new DerivedMetrics(seconds == 0
                                  ? 0
                                  : calls / seconds,
                                  calls == 0
                                  ? 0
                                  : (double) failures / calls,
                                  seconds == 0
                                  ? 0
                                  : gcCount / seconds,
                                  percentile(means, 50),
                                  percentile(means, 95),
                                  percentile(means, 99),
                                  Math.min(1, lag / EventLoopMetrics.DEFAULT_HEALTH_THRESHOLD_NS),
                                  heap,
                                  cpuTrend(samples),
                                  latencyTrend(intervals),
                                  errorTrend(intervals));
    }

    private static double cpuTrend(List<ComprehensiveSnapshot> samples) {
        if (samples.size() < 10) {
            return 0;
        }

        var half = samples.size() / 2;

        return averageCpu(samples.subList(half, samples.size())) - averageCpu(samples.subList(0, half));
    }

    private static double averageCpu(List<ComprehensiveSnapshot> samples) {
        return samples.stream()
                      .mapToDouble(ComprehensiveSnapshot::cpuUsage)
                      .average()
                      .orElse(0);
    }

    private static double latencyTrend(List<MetricInterval> intervals) {
        if (intervals.size() < 9) {
            return 0;
        }

        var half = intervals.size() / 2;

        return weightedLatency(intervals.subList(half, intervals.size())) - weightedLatency(intervals.subList(0, half));
    }

    private static double errorTrend(List<MetricInterval> intervals) {
        if (intervals.size() < 9) {
            return 0;
        }

        var half = intervals.size() / 2;

        return failureRatio(intervals.subList(half, intervals.size())) - failureRatio(intervals.subList(0, half));
    }

    private static double weightedLatency(List<MetricInterval> intervals) {
        var calls = intervals.stream().mapToLong(MetricInterval::invocations).sum();

        return calls == 0
               ? 0
               : intervals.stream()
                          .mapToDouble(interval -> interval.invocationDuration()
                                                           .nanos() / 1_000_000.0)
                          .sum() / calls;
    }

    private static double failureRatio(List<MetricInterval> intervals) {
        var calls = intervals.stream().mapToLong(MetricInterval::invocations).sum();

        return calls == 0
               ? 0
               : (double) intervals.stream()
                                   .mapToLong(MetricInterval::failures)
                                   .sum() / calls;
    }

    static double percentile(double[] sorted, int percentile) {
        return sorted.length == 0
               ? 0
               : sorted[Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1)];
    }
}
