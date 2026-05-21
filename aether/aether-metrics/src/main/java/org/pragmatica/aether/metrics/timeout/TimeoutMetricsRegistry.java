// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.timeout;

import org.pragmatica.lang.Contract;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/// Counts the number of timeouts fired per `TimeoutSubsystem`. Backed by one
/// `LongAdder` per subsystem (zero-contention multi-writer accumulator). Snapshot
/// reads return a stable point-in-time view via `LongAdder::sum`.
///
/// Wired as a node-level singleton (`ManageableNode#timeoutMetrics`) so any
/// subsystem can record from its own thread without dependency injection
/// churn. The companion REST endpoint `/api/metrics/timeouts` and CLI
/// `aether metrics timeouts` both consume `snapshot()` directly.
///
/// MVP NOTE (P-NEW-A, 2026-05-21): the registry ships now; subsystem-side
/// `recordTimeout(...)` calls at each of the 14 timeout-fire sites are a
/// follow-up. Until those hooks land the snapshot reports all-zero counters,
/// which is the correct contract — the endpoint shape is asserted by tests,
/// and TC-07-G3 can already query the endpoint to detect regressions in the
/// shape itself.
public interface TimeoutMetricsRegistry {
    @Contract void recordTimeout(TimeoutSubsystem subsystem);
    Map<TimeoutSubsystem, Long> snapshot();
    long firedCount(TimeoutSubsystem subsystem);

    static TimeoutMetricsRegistry timeoutMetricsRegistry() {
        return new TimeoutMetricsRegistryImpl();
    }

    final class TimeoutMetricsRegistryImpl implements TimeoutMetricsRegistry {
        private final EnumMap<TimeoutSubsystem, LongAdder> counters = new EnumMap<>(TimeoutSubsystem.class);

        TimeoutMetricsRegistryImpl() {
            for (var subsystem : TimeoutSubsystem.values()) {
                counters.put(subsystem, new LongAdder());
            }
        }

        @Override
        @Contract
        public void recordTimeout(TimeoutSubsystem subsystem) {
            counters.get(subsystem).increment();
        }

        @Override
        public Map<TimeoutSubsystem, Long> snapshot() {
            return counters.entrySet()
                           .stream()
                           .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                  entry -> entry.getValue().sum()));
        }

        @Override
        public long firedCount(TimeoutSubsystem subsystem) {
            return counters.get(subsystem).sum();
        }
    }
}
