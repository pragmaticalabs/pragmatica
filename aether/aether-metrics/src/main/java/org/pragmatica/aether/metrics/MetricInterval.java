// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.lang.io.TimeSpan;


/// Delta of cumulative observations. Reset/first samples establish a baseline, never traffic.
record MetricInterval(TimeSpan elapsed,
                      long invocations,
                      long failures,
                      long gcCount,
                      TimeSpan gcPause,
                      TimeSpan invocationDuration) {
    static final MetricInterval EMPTY = new MetricInterval(TimeSpan.timeSpan(0).nanos(),
                                                           0,
                                                           0,
                                                           0,
                                                           TimeSpan.timeSpan(0).nanos(),
                                                           TimeSpan.timeSpan(0).nanos());

    static MetricInterval metricInterval(ComprehensiveSnapshot previous, ComprehensiveSnapshot current) {
        if (current.timestamp() <= previous.timestamp()) {
            return EMPTY;
        }

        var calls = increment(previous.totalInvocations(), current.totalInvocations());
        var failures = Math.min(calls,
                                increment(previous.failedInvocations(), current.failedInvocations()));
        var duration = calls == 0
                       ? 0
                       : Math.max(0,
                                  current.avgLatencyMs() * current.totalInvocations() - previous.avgLatencyMs() * previous.totalInvocations());

        return new MetricInterval(TimeSpan.timeSpan(current.timestamp() - previous.timestamp()).millis(),
                                  calls,
                                  failures,
                                  increment(previous.gc().totalGcCount(),
                                            current.gc().totalGcCount()),
                                  TimeSpan.timeSpan(increment(previous.gc().totalPauseMs(),
                                                              current.gc().totalPauseMs())).millis(),
                                  TimeSpan.timeSpan(Math.round(duration * 1_000_000)).nanos());
    }

    static long increment(long previous, long current) {
        return current >= previous
               ? current - previous
               : 0;
    }

    TimeSpan meanLatency() {
        return TimeSpan.timeSpan(invocations == 0
                                 ? 0
                                 : invocationDuration.nanos() / invocations).nanos();
    }

    double failureRatio() {
        return invocations == 0
               ? 0
               : (double) failures / invocations;
    }
}
