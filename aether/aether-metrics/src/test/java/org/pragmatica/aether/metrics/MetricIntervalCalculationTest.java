// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.Map;

import org.pragmatica.aether.metrics.consensus.RabiaMetrics;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.aether.metrics.gc.GCMetrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;


class MetricIntervalCalculationTest {
    @Test
    void constantCumulativeCountersProduceNoActivity() {
        var calculator = DerivedMetricsCalculator.derivedMetricsCalculator();

        calculator.addSample(sample(1000, 1000, 100, 50, 100, 10));
        calculator.addSample(sample(7000, 1000, 100, 50, 100, 10));
        assertThat(calculator.current().requestRate()).isZero();
        assertThat(calculator.current().gcRate()).isZero();
        assertThat(calculator.current().errorRate()).isZero();
        assertThat(calculator.current().intervalMeanLatencyP95()).isZero();
    }

    @Test
    void irregularSpacingAndSampleFrequencyConserveRatesAndRatio() {
        var coarse = DerivedMetricsCalculator.derivedMetricsCalculator();
        var fine = DerivedMetricsCalculator.derivedMetricsCalculator();
        var start = sample(1000, 1000, 100, 50, 100, 10);
        var end = sample(11000, 1200, 110, 60, 140, 10);

        coarse.addSample(start);
        coarse.addSample(end);
        fine.addSample(start);
        fine.addSample(sample(3000, 1040, 102, 52, 108, 10));
        fine.addSample(sample(8000, 1140, 107, 57, 128, 10));
        fine.addSample(end);
        assertThat(coarse.current().requestRate()).isEqualTo(20);
        assertThat(coarse.current().gcRate()).isEqualTo(1);
        assertThat(coarse.current().errorRate()).isCloseTo(0.05, within(0.00001));
        assertThat(fine.current().requestRate()).isEqualTo(coarse.current().requestRate());
        assertThat(fine.current().errorRate()).isEqualTo(coarse.current().errorRate());
    }

    @Test
    void equalFailureRatiosAtDifferentVolumesHaveEqualHealthDecisions() {
        var low = DerivedMetricsCalculator.derivedMetricsCalculator();
        var high = DerivedMetricsCalculator.derivedMetricsCalculator();

        low.addSample(sample(1000, 0, 0, 0, 0, 0));
        high.addSample(sample(1000, 0, 0, 0, 0, 0));
        low.addSample(sample(2000, 100, 2, 0, 0, 10));
        high.addSample(sample(2000, 10_000, 200, 0, 0, 10));
        assertThat(low.current().errorRate()).isEqualTo(high.current().errorRate());
        assertThat(low.current().healthScore()).isEqualTo(high.current().healthScore());
        assertThat(low.current().stressed()).isEqualTo(high.current().stressed());
        assertThat(low.current().hasCapacity()).isEqualTo(high.current().hasCapacity());
    }

    @Test
    void resetStartsNewBaselineAndReorderedSamplesDoNotMoveIt() {
        var calculator = DerivedMetricsCalculator.derivedMetricsCalculator();

        calculator.addSample(sample(1000, 1000, 100, 50, 100, 10));
        calculator.addSample(sample(2000, 2, 0, 0, 0, 10));
        calculator.addSample(sample(1500, 2000, 200, 100, 200, 10));
        calculator.addSample(sample(3000, 12, 1, 2, 4, 10));
        assertThat(calculator.current().requestRate()).isEqualTo(5);
        assertThat(calculator.current().errorRate()).isEqualTo(0.1);
    }

    @Test
    void minuteTotalsIncludeCrossBoundaryDeltaOnceAndWeightFailureRatio() {
        var aggregator = MinuteAggregator.minuteAggregator();

        aggregator.addSample(sample(59000, 1000, 100, 50, 100, 10));
        aggregator.addSample(sample(61000, 1100, 101, 52, 110, 10));
        aggregator.addSample(sample(62000, 1200, 120, 53, 115, 10));
        aggregator.flush();
        assertThat(aggregator.all().stream().mapToLong(MinuteAggregate::totalInvocations).sum()).isEqualTo(200);
        assertThat(aggregator.all().stream().mapToLong(MinuteAggregate::totalGcPauseMs).sum()).isEqualTo(15);
        assertThat(aggregator.all().getLast().errorRate()).isEqualTo(0.1);
        aggregator.addSample(sample(63000, 1200, 120, 53, 115, 10));
        aggregator.flush();
        assertThat(aggregator.all().getLast().totalInvocations()).isZero();
    }

    @Test
    void unequalIntervalsDescribeMeansAndDoNotClaimRequestPercentiles() {
        var calculator = DerivedMetricsCalculator.derivedMetricsCalculator();

        calculator.addSample(sample(1000, 0, 0, 0, 0, 0));
        calculator.addSample(sample(2000, 1000, 0, 0, 0, 1));
        calculator.addSample(sample(3000, 1001, 0, 0, 0, 2000.0 / 1001));
        // 1000 requests at 1ms, then one at 1000ms: request p95 is 1ms,
        // while the deliberately named interval-mean p95 is 1000ms.
        assertThat(calculator.current().intervalMeanLatencyP95()).isCloseTo(1000, within(0.00001));
    }

    @Test
    void perMethodAppearanceRemovalAndResetDoNotCreateAggregateTraffic() {
        var accumulator = new InvocationCounterAccumulator();
        var a = counters(100);

        assertThat(accumulator.accumulateCounters(Map.of("a", a)).calls()).isZero();
        assertThat(accumulator.accumulateCounters(Map.of("a", counters(110), "b", counters(900))).calls()).isEqualTo(10);
        assertThat(accumulator.accumulateCounters(Map.of("b", counters(910))).calls()).isEqualTo(20);
        assertThat(accumulator.accumulateCounters(Map.of("b", counters(1))).calls()).isEqualTo(20);
        assertThat(accumulator.accumulateCounters(Map.of("b", counters(3))).calls()).isEqualTo(22);
    }

    private static InvocationCounterAccumulator.Counters counters(long calls) {
        return new InvocationCounterAccumulator.Counters(calls, calls, 0, calls * 1000);
    }

    private static ComprehensiveSnapshot sample(long time,
                                                long calls,
                                                long failures,
                                                long gc,
                                                long pause,
                                                double mean) {
        return new ComprehensiveSnapshot(time,
                                         0.2,
                                         10,
                                         100,
                                         new GCMetrics(gc, pause, 0, 0, 0, 0, 0, 0),
                                         EventLoopMetrics.EMPTY,
                                         RabiaMetrics.EMPTY,
                                         calls,
                                         calls - failures,
                                         failures,
                                         mean,
                                         Map.of());
    }
}
