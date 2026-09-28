// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Bounds of `system:cluster-events:1.0.0`, from the `CLUSTER_EVENTS_MAX_*` environment overrides, checked
/// at boot (#1549).
///
/// Before, an unparseable value fell back to the default silently, and `0` (or a count past the ring's
/// indexable capacity) reached the stream engine, which refuses such a bound at creation — so the system
/// stream never came up while its registrar retried. Now a set value must be a whole number from 1 to the
/// bound's maximum, or the node refuses to boot with [InvalidLimit] naming the variable. Unset or blank
/// means the default.
public record ClusterEventsLimits(long maxCount, long maxBytes, long maxAgeMs, long maxEventSizeBytes) {
    public static final String MAX_COUNT_VARIABLE = "CLUSTER_EVENTS_MAX_COUNT";
    public static final String MAX_BYTES_VARIABLE = "CLUSTER_EVENTS_MAX_BYTES";
    public static final String MAX_AGE_MS_VARIABLE = "CLUSTER_EVENTS_MAX_AGE_MS";
    public static final String MAX_EVENT_SIZE_BYTES_VARIABLE = "CLUSTER_EVENTS_MAX_EVENT_SIZE_BYTES";
    /// Default 10_000 — matches [AetherNode#CLUSTER_EVENTS_MAX_RETAINED], the aggregator read window.
    public static final long DEFAULT_MAX_COUNT = AetherNode.CLUSTER_EVENTS_MAX_RETAINED;
    /// Default 16MB — the byte hard-cap OOM guard for the off-heap partition store. Cluster events are small
    /// JSON records, so 16MB retains many thousands of them while leaving the bulk of the per-node stream
    /// budget (128MB) for app streams — the prior 64MB default reserved HALF the budget for one system
    /// stream and starved app-stream creation (STREAM_MEMORY_EXCEEDED).
    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
    /// Default ~24h.
    public static final long DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    /// Default ~64KB.
    public static final long DEFAULT_MAX_EVENT_SIZE_BYTES = 64L * 1024;

    public static Result<ClusterEventsLimits> clusterEventsLimits(Fn1<Option<String>, String> environment) {
        return Result.all(limit(environment, MAX_COUNT_VARIABLE, DEFAULT_MAX_COUNT, OffHeapRingBuffer.MAX_CAPACITY),
                          limit(environment, MAX_BYTES_VARIABLE, DEFAULT_MAX_BYTES, Long.MAX_VALUE),
                          limit(environment, MAX_AGE_MS_VARIABLE, DEFAULT_MAX_AGE_MS, Long.MAX_VALUE),
                          limit(environment, MAX_EVENT_SIZE_BYTES_VARIABLE, DEFAULT_MAX_EVENT_SIZE_BYTES, Long.MAX_VALUE)).map(ClusterEventsLimits::new);
    }

    /// Read from the process environment.
    public static Result<ClusterEventsLimits> clusterEventsLimits() {
        return clusterEventsLimits(variable -> option(System.getenv(variable)));
    }

    /// Bounded on count, bytes (off-heap hard cap) and age, mode ANY.
    public RetentionPolicy retention() {
        return RetentionPolicy.retentionPolicy(maxCount, maxBytes, maxAgeMs, RetentionMode.ANY);
    }

    private static Result<Long> limit(Fn1<Option<String>, String> environment,
                                      String variable,
                                      long defaultValue,
                                      long maximum) {
        return environment.apply(variable)
                          .filter(Verify.Is::notBlank)
                          .map(raw -> parse(variable, raw, maximum))
                          .or(success(defaultValue));
    }

    private static Result<Long> parse(String variable, String raw, long maximum) {
        return Number.parseLong(raw.trim())
                     .mapError(_ -> InvalidLimit.FACTORY.apply(variable, raw, maximum))
                     .flatMap(value -> inRange(variable, raw, value, maximum));
    }

    private static Result<Long> inRange(String variable, String raw, long value, long maximum) {
        return Verify.Is.between(value, 1L, maximum)
               ? success(value)
               : InvalidLimit.FACTORY.apply(variable, raw, maximum).result();
    }

    /// A `CLUSTER_EVENTS_MAX_*` value that is not a whole number from 1 to `maximum`.
    public record InvalidLimit(String variable, String value, Long maximum, String message) implements Cause {
        static final Fn3<InvalidLimit, String, String, Long> FACTORY = Causes.forThreeValues("%s='%s' is not a whole number from 1 to %d; the node refuses to boot rather than declare"
                                                                                            + " system:cluster-events:1.0.0 with a bound its stream engine refuses. Unset it for the"
                                                                                            + " default, or set a value in range.",
                                                                                             InvalidLimit::new);
    }
}
