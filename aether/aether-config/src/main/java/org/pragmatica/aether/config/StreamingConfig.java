// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record StreamingConfig(TimeSpan publishForwardTimeout,
                              TimeSpan readForwardTimeout,
                              long maxReadResponseBytes,
                              ReadLinearizationMode readLinearization,
                              int reshuffleConcurrency) {
    public static final long DEFAULT_MAX_READ_RESPONSE_BYTES = 28L * 1024 * 1024;
    /// How many partitions one node may hold in materialize+backfill at once (`reshuffle_concurrency`).
    /// One-at-a-time starves a large reshuffle; unbounded floods backfill. Was a hard-coded constant with
    /// no binding until 2026-08-16, while the paced-materialization error message named it as though it
    /// were a knob.
    public static final int DEFAULT_RESHUFFLE_CONCURRENCY = 2;

    /// The default `LINEARIZABLE`-read mechanism (spec §8.1): the no-op consensus round (#345 item
    /// 1e-a). The alternative `lease` mechanism is rejected at config parse until validated.
    public static final ReadLinearizationMode DEFAULT_READ_LINEARIZATION = ReadLinearizationMode.NO_OP_ROUND;

    /// Multiplier applied to {@link #readForwardTimeout()} to derive the cold-start backfill source-wait
    /// bound. After a SIMULTANEOUS full-cluster restart every replica is SYNCING and waits for a
    /// CAUGHT_UP source that cannot exist; once this bound elapses with no source the highest-watermark
    /// replica self-promotes to break the deadlock (see {@code PartitionBackfill}). Derived from the
    /// existing per-probe transport timeout rather than introducing a new magic global, and gives a
    /// staggered survivor several probe cycles to converge before symmetry is broken.
    public static final int BACKFILL_SOURCE_WAIT_PROBE_CYCLES = 10;

    public static StreamingConfig streamingConfig() {
        return new StreamingConfig(timeSpan(5).seconds(),
                                   timeSpan(2).seconds(),
                                   DEFAULT_MAX_READ_RESPONSE_BYTES,
                                   DEFAULT_READ_LINEARIZATION,
                                   DEFAULT_RESHUFFLE_CONCURRENCY);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   DEFAULT_READ_LINEARIZATION,
                                   DEFAULT_RESHUFFLE_CONCURRENCY);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes,
                                                  ReadLinearizationMode readLinearization) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   DEFAULT_RESHUFFLE_CONCURRENCY);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes,
                                                  ReadLinearizationMode readLinearization,
                                                  int reshuffleConcurrency) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency);
    }

    /// The same streaming config with the `LINEARIZABLE`-read mechanism replaced — used by the config
    /// loader to apply the parsed `[durable-entity] read-linearization` knob onto the streaming config.
    public StreamingConfig withReadLinearization(ReadLinearizationMode readLinearization) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency);
    }

    /// Bounded wait for a caught-up source to appear before a cold-start replica self-promotes. Derived
    /// from {@link #readForwardTimeout()} so it scales with the configured transport latency budget.
    public TimeSpan backfillSourceWaitBound() {
        return readForwardTimeout.plus(readForwardTimeout.nanos() * (BACKFILL_SOURCE_WAIT_PROBE_CYCLES - 1L));
    }
}
