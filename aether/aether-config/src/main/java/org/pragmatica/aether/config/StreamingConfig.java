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
                              int reshuffleConcurrency,
                              long caughtUpMaxLagOffsets) {
    public static final long DEFAULT_MAX_READ_RESPONSE_BYTES = 28L * 1024 * 1024;
    /// How many partitions one node may hold in materialize+backfill at once (`reshuffle_concurrency`).
    /// One-at-a-time starves a large reshuffle; unbounded floods backfill. Was a hard-coded constant with
    /// no binding until 2026-08-16, while the paced-materialization error message named it as though it
    /// were a knob.
    public static final int DEFAULT_RESHUFFLE_CONCURRENCY = 2;
    /// How far a `CAUGHT_UP` replica's `confirmedOffset` may trail the freshest peer watermark and still
    /// be trusted to SERVE READS or to count toward the ring-release catch-up gate
    /// (`caught_up_max_lag_offsets`).
    ///
    /// `ReplicationState.CAUGHT_UP` never downgrades: nothing moves a replica out of it when it stops
    /// acking. Under partition the state does not go stale, it FREEZES at its last good value, which
    /// reads as healthy forever — so a replica readers can still reach but which stopped acking to the
    /// owner would keep serving increasingly stale data, and `caughtUpOthers` would over-count so an
    /// owner could release its ring believing enough replicas were caught up.
    ///
    /// Expressed in OFFSETS, deliberately NOT as a time-to-live. `updateWatermark` is driven purely by
    /// acks and backfill milestones; NOTHING refreshes it on a quiet partition, so a TTL would age out
    /// every replica of a write-idle stream and stop serving reads from the healthiest streams in the
    /// cluster — the same trap #333 documented in its own seam. Lag is naturally correct when quiet: if
    /// the owner has not advanced, no peer is behind, so no false staleness.
    ///
    /// Zero would be too strict — replication is asynchronous by design, so a healthy replica is
    /// transiently behind on every write.
    ///
    /// The DEFAULT VALUE IS A GUESS: it is not derived from a measured steady-state lag distribution.
    /// What would settle it is observing actual peer lag under the 02y publish load and choosing a bound
    /// above the normal in-flight batch depth. It is a knob so an operator hitting false staleness can
    /// relieve it without a rebuild.
    public static final long DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS = 1024L;
    /// Opt out of the lag guard entirely — a `CAUGHT_UP` replica is trusted regardless of how far it
    /// trails. Only for callers that genuinely have no freshness requirement (degenerate/test wiring);
    /// production wiring passes a finite bound, and the no-argument factories default to
    /// [#DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS] so an unwired path is GUARDED rather than silently inert.
    public static final long CAUGHT_UP_LAG_UNBOUNDED = Long.MAX_VALUE;

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
                                   DEFAULT_RESHUFFLE_CONCURRENCY,
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   DEFAULT_READ_LINEARIZATION,
                                   DEFAULT_RESHUFFLE_CONCURRENCY,
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes,
                                                  ReadLinearizationMode readLinearization) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   DEFAULT_RESHUFFLE_CONCURRENCY,
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS);
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
                                   reshuffleConcurrency,
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes,
                                                  ReadLinearizationMode readLinearization,
                                                  int reshuffleConcurrency,
                                                  long caughtUpMaxLagOffsets) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets);
    }

    /// The same streaming config with the `LINEARIZABLE`-read mechanism replaced — used by the config
    /// loader to apply the parsed `[durable-entity] read-linearization` knob onto the streaming config.
    public StreamingConfig withReadLinearization(ReadLinearizationMode readLinearization) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets);
    }

    /// Bounded wait for a caught-up source to appear before a cold-start replica self-promotes. Derived
    /// from {@link #readForwardTimeout()} so it scales with the configured transport latency budget.
    public TimeSpan backfillSourceWaitBound() {
        return readForwardTimeout.plus(readForwardTimeout.nanos() * (BACKFILL_SOURCE_WAIT_PROBE_CYCLES - 1L));
    }
}
