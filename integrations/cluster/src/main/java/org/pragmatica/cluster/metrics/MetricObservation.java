// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import java.util.Map;

import org.pragmatica.serialization.Codec;


/// Producer identity is the containing map key (or pong sender). Forwarding preserves this
/// envelope unchanged. Sequence is monotonic within an incarnation; observation time is UTC.
@Codec
public record MetricObservation(long incarnation, long sequence, long observedAtMs, Map<String, Double> values) {
    public static final long MAX_AGE_MS = 30_000;
    public static final long CLOCK_SKEW_ALLOWANCE_MS = 5_000;

    public MetricObservation {
        values = Map.copyOf(values);
    }

    public static MetricObservation metricObservation(long incarnation,
                                                      long sequence,
                                                      long observedAtMs,
                                                      Map<String, Double> values) {
        return new MetricObservation(incarnation, sequence, observedAtMs, values);
    }

    public static boolean isTimestampFresh(long observedAtMs, long nowMs) {
        var age = nowMs - observedAtMs;

        return age >= -CLOCK_SKEW_ALLOWANCE_MS && age <= MAX_AGE_MS;
    }

    public boolean isAfter(MetricObservation previous) {
        return incarnation > previous.incarnation() || incarnation == previous.incarnation() && sequence > previous.sequence();
    }
}
