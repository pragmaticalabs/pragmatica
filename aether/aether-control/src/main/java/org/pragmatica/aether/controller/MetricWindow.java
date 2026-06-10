// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


public record MetricWindow(double[] valuesInternal, int head, int count, double sum, long recordCount) {
    private static final int SUM_RECALCULATION_INTERVAL = 100;

    public static Result<MetricWindow> metricWindow(int windowSize) {
        return windowSize > 0
               ? Result.success(new MetricWindow(new double[windowSize], 0, 0, 0.0, 0))
               : Causes.cause("windowSize must be positive, got: " + windowSize).result();
    }

    public double[] values() {
        return valuesInternal.clone();
    }

    public boolean isFull() {
        return count == valuesInternal.length;
    }

    public double average() {
        return count > 0
               ? sum / count
               : 0.0;
    }

    public double lastValue() {
        if (count == 0) {
            return 0.0;
        }

        var lastIndex = (head - 1 + valuesInternal.length) % valuesInternal.length;

        return valuesInternal[lastIndex];
    }

    public double relativeChange(double current) {
        var avg = average();

        return avg > 0.0
               ? current / avg
               : 1.0;
    }

    public MetricWindow record(double value) {
        var newValues = valuesInternal.clone();
        var windowSize = newValues.length;
        var newRecordCount = recordCount + 1;
        var newSum = sum + value;

        if (count >= windowSize) {
            newSum -= newValues[head];
        }

        newValues[head] = value;
        var newHead = (head + 1) % windowSize;
        var newCount = Math.min(count + 1, windowSize);

        if (newRecordCount % SUM_RECALCULATION_INTERVAL == 0) {
            newSum = recalculateSum(newValues, newCount);
        }

        return new MetricWindow(newValues, newHead, newCount, newSum, newRecordCount);
    }

    private static double recalculateSum(double[] values, int count) {
        var sum = 0.0;

        for (int i = 0; i < count; i++) {
            sum += values[i];
        }

        return sum;
    }

    public int windowSize() {
        return valuesInternal.length;
    }
}
