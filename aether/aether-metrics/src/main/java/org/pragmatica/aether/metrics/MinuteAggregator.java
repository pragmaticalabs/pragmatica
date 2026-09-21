// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.RingBuffer;

import static org.pragmatica.lang.Result.unitResult;


/// Intervals belong to their closing sample's minute, including an interval crossing a boundary.
/// A baseline survives flush/minute changes so cumulative totals are never counted twice.
public final class MinuteAggregator {
    private final RingBuffer<MinuteAggregate> aggregates;
    private long currentMinute;
    private final List<ComprehensiveSnapshot> currentSamples = new ArrayList<>();
    private final List<MetricInterval> currentIntervals = new ArrayList<>();
    private Option<ComprehensiveSnapshot> previous = Option.none();

    private MinuteAggregator(int capacity) {
        aggregates = RingBuffer.ringBuffer(capacity);
    }

    public static MinuteAggregator minuteAggregator() {
        return minuteAggregator(120);
    }

    public static MinuteAggregator minuteAggregator(int capacity) {
        return new MinuteAggregator(capacity);
    }

    public synchronized Result<Unit> addSample(ComprehensiveSnapshot snapshot) {
        if (previous.filter(old -> snapshot.timestamp() <= old.timestamp()).isPresent()) {
            return unitResult();
        }

        var minute = MinuteAggregate.alignToMinute(snapshot.timestamp());

        if (currentMinute != minute) {
            finalizeCurrentMinute();
        }

        currentMinute = minute;
        currentSamples.add(snapshot);
        previous.map(old -> MetricInterval.metricInterval(old, snapshot)).onPresent(currentIntervals::add);
        previous = Option.some(snapshot);

        return unitResult();
    }

    public synchronized Result<Unit> flush() {
        finalizeCurrentMinute();

        return unitResult();
    }

    public synchronized List<MinuteAggregate> all() {
        return aggregates.toList();
    }

    public synchronized List<MinuteAggregate> recent(int count) {
        var all = aggregates.toList();

        return List.copyOf(all.subList(Math.max(0,
                                                all.size() - Math.max(0, count)),
                                       all.size()));
    }

    public synchronized List<MinuteAggregate> since(long timestamp) {
        return aggregates.filter(aggregate -> aggregate.minuteTimestamp() >= timestamp);
    }

    public synchronized int currentSampleCount() {
        return currentSamples.size();
    }

    public synchronized int aggregateCount() {
        return aggregates.size();
    }

    public synchronized float[][] toTTMInput(int windowMinutes) {
        var recent = recent(windowMinutes);
        var result = new float[windowMinutes][MinuteAggregate.featureNames().length];
        var offset = windowMinutes - recent.size();

        for (var index = 0; index < recent.size(); index++) {
            result[offset + index] = recent.get(index).toFeatureArray();
        }

        return result;
    }

    private void finalizeCurrentMinute() {
        if (currentSamples.isEmpty()) {
            return;
        }

        var calls = currentIntervals.stream().mapToLong(MetricInterval::invocations).sum();
        var failures = currentIntervals.stream().mapToLong(MetricInterval::failures).sum();
        var duration = currentIntervals.stream()
                                       .mapToDouble(interval -> interval.invocationDuration()
                                                                        .nanos() / 1_000_000.0)
                                       .sum();
        var means = currentIntervals.stream()
                                    .filter(interval -> interval.invocations() > 0)
                                    .mapToDouble(interval -> interval.meanLatency()
                                                                     .nanos() / 1_000_000.0)
                                    .sorted()
                                    .toArray();

        aggregates.add(MinuteAggregate.minuteAggregate(currentMinute,
                                                       currentSamples.stream()
                                                                     .mapToDouble(ComprehensiveSnapshot::cpuUsage)
                                                                     .average()
                                                                     .orElse(0),
                                                       currentSamples.stream()
                                                                     .mapToDouble(ComprehensiveSnapshot::heapUsage)
                                                                     .average()
                                                                     .orElse(0),
                                                       currentSamples.stream()
                                                                     .mapToDouble(sample -> sample.eventLoop()
                                                                                                  .lagMs())
                                                                     .average()
                                                                     .orElse(0),
                                                       calls == 0
                                                       ? 0
                                                       : duration / calls,
                                                       calls,
                                                       currentIntervals.stream()
                                                                       .mapToLong(interval -> interval.gcPause()
                                                                                                      .millis())
                                                                       .sum(),
                                                       DerivedMetricsCalculator.percentile(means, 50),
                                                       DerivedMetricsCalculator.percentile(means, 95),
                                                       DerivedMetricsCalculator.percentile(means, 99),
                                                       calls == 0
                                                       ? 0
                                                       : (double) failures / calls,
                                                       0,
                                                       currentSamples.size()));
        currentSamples.clear();
        currentIntervals.clear();
    }
}
