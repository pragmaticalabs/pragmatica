// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.MethodSnapshot;


/// Maintains monotonic process-local totals across method appearance, disappearance, and reset.
/// A newly observed method is a baseline: its unknown pre-observation activity is not assigned to an interval.
final class InvocationCounterAccumulator {
    record Counters(long calls, long successes, long failures, long durationNs) {
        static final Counters ZERO = new Counters(0, 0, 0, 0);

        static Counters counters(long calls, long successes, long failures, long durationNs) {
            return new Counters(calls, successes, failures, durationNs);
        }

        Counters plus(Counters other) {
            return new Counters(calls + other.calls,
                                successes + other.successes,
                                failures + other.failures,
                                durationNs + other.durationNs);
        }

        Counters since(Counters previous) {
            if (calls < previous.calls || successes < previous.successes || failures < previous.failures || durationNs < previous.durationNs) {
                return ZERO;
            }

            return new Counters(calls - previous.calls,
                                successes - previous.successes,
                                failures - previous.failures,
                                durationNs - previous.durationNs);
        }
    }

    private Map<String, Counters> previous = Map.of();
    private Counters total = Counters.ZERO;

    synchronized Counters accumulate(List<MethodSnapshot> snapshots) {
        var current = new HashMap<String, Counters>();

        snapshots.forEach(snapshot -> current.put(snapshot.artifact().asString() + "|" + snapshot.methodName().name(),
                                                  new Counters(snapshot.metrics().count(),
                                                               snapshot.metrics().successCount(),
                                                               snapshot.metrics().failureCount(),
                                                               snapshot.metrics().totalDurationNs())));

        return accumulateCounters(current);
    }

    synchronized Counters accumulateCounters(Map<String, Counters> current) {
        var increment = current.entrySet()
                               .stream()
                               .filter(entry -> previous.containsKey(entry.getKey()))
                               .map(entry -> entry.getValue()
                                                  .since(previous.get(entry.getKey())))
                               .reduce(Counters.ZERO, Counters::plus);

        total = total.plus(increment);
        previous = Map.copyOf(current);

        return total;
    }
}
