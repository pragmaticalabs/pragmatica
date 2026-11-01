// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record StreamingConfig(TimeSpan publishForwardTimeout,
                              TimeSpan readForwardTimeout,
                              long maxReadResponseBytes,
                              ReadLinearizationMode readLinearization,
                              int reshuffleConcurrency,
                              long caughtUpMaxLagOffsets,
                              long segmentDiskMaxBytes,
                              long dhtSegmentMaxBytes,
                              TimeSpan isrLagMax,
                              TimeSpan promotionEscapeAfter,
                              long sealRunBytes,
                              int sealRunEvents,
                              TimeSpan sealRunAge,
                              long sealMergeBytes,
                              long maxMemoryBytes,
                              long sealPendingCapBytes) {
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
    /// `segment_disk_max_bytes` unset (#1604): the node derives the streams disk-tier cap from the filesystem
    /// holding its stream segments at boot. Disk size is a fact about the node, not cluster policy -- the
    /// previous hard-coded 4 GiB wedged long-lived nodes whatever their real disk.
    public static final long DERIVE_SEGMENT_DISK_MAX_BYTES = 0L;
    /// `dht_segment_max_bytes` unset (#2258): the node caps the live bytes of `stream-segments` its DHT engine holds at
    /// [#DHT_SEGMENT_MAX_BYTES_FLOOR] or a quarter of its maximum heap, whichever is larger (128 MiB on the shipped
    /// `-Xmx512m` launchers). THE DEFAULT IS A GUESS (spec 3.2 item 7); what would settle it is the measured heap one block costs
    /// the engine, beside the heap the node's other DHT prefixes and tombstones hold.
    public static final long DERIVE_DHT_SEGMENT_MAX_BYTES = 0L;
    /// The smallest derived `dht_segment_max_bytes`: 64 MiB.
    public static final long DHT_SEGMENT_MAX_BYTES_FLOOR = 64L * 1024 * 1024;
    /// #1730 `isr_lag_max`: how long an in-sync replica may stay behind the owner's head before the owner asks for it
    /// to leave the partition's ISR (Kafka's `replica.lag.time.max.ms`). A LIVENESS knob only: the ISR changes only
    /// by a committed write, and a confirmation waits for every committed ISR member, so no value of it can make an
    /// acknowledgement unsafe. Smaller drops a slow replica sooner (shorter ack stalls, fewer copies); larger keeps
    /// it longer (an ack waits up to this long for a lagging member).
    public static final TimeSpan DEFAULT_ISR_LAG_MAX = timeSpan(30).seconds();
    /// #2080 `promotion_escape_after`: how long a stream partition's promotion (the owner activation gate, the replica promotion
    /// contest) waits for members that do not answer before a candidate named in the partition's COMMITTED in-sync set goes ahead
    /// without them. Distinct from, and at least, the alarm bound (two SWIM suspect windows, 20 s at the default): after a full-cluster
    /// cold restart the in-sync set is minted from placement, so a node whose disk is ahead and which merely boots slowly (JVM start
    /// plus WAL replay) must not be escaped past, or divergence becomes routine on staggered restarts. THE DEFAULT IS A GUESS (the
    /// owner's, 2026-10-09); what would settle it is the measured boot spread of a cold restart.
    public static final TimeSpan DEFAULT_PROMOTION_ESCAPE_AFTER = timeSpan(120).seconds();
    /// #2201 C-02 (spec 4.2) `seal_run_bytes`: the segment sealer seals a partition's run of evicted events once its queued
    /// bytes reach this. With `seal_run_events` and `seal_run_age`, whichever is reached first. ALL FOUR SEALER DEFAULTS ARE
    /// GUESSES (spec 4.3); what would settle them is the seal rate and WAL size trend of the workload-1 baseline run.
    public static final long DEFAULT_SEAL_RUN_BYTES = 1024 * 1024L;
    /// `seal_run_events`: ... or once its queued events reach this (bounds the decode of a block of tiny events).
    public static final int DEFAULT_SEAL_RUN_EVENTS = 4096;
    /// `seal_run_age`: ... or once its oldest queued event has waited this long. Without a WAL this is also how much longer
    /// an acknowledged event can be lost to a crash before its seal.
    public static final TimeSpan DEFAULT_SEAL_RUN_AGE = timeSpan(5).seconds();
    /// `seal_merge_bytes`: the cap on one block when a backlog has queued behind an in-flight seal.
    public static final long DEFAULT_SEAL_MERGE_BYTES = 4 * 1024 * 1024L;
    /// `max_memory_bytes` unset (#2201 C-03, spec 4.4): the node derives its off-heap stream memory budget from the physical
    /// memory of its host ([#derivedMaxMemoryBytes]). The `STREAM_MAX_MEMORY_BYTES` environment variable overrides both.
    public static final long DERIVE_MAX_MEMORY_BYTES = 0L;
    /// The smallest derived `max_memory_bytes`: 128 MiB, the budget every node had before the key existed.
    public static final long MIN_DERIVED_MAX_MEMORY_BYTES = 128 * 1024 * 1024L;
    /// The largest derived `max_memory_bytes`: 1 GiB. THE FORMULA IS A GUESS (spec 4.4 item 2): one eighth of physical memory,
    /// clamped to [128 MiB, 1 GiB]; the spec's envelope (six SME workloads at peak need about 526 MiB per node) is a SIZING
    /// assumption for how much ring growth the budget should afford, not a mechanism that holds rings to a window.
    public static final long MAX_DERIVED_MAX_MEMORY_BYTES = 1024 * 1024 * 1024L;
    /// `seal_pending_cap_bytes` unset: the cap on the sealer's heap copies of evicted, not yet sealed events is derived from
    /// the off-heap budget ([#effectiveSealPendingCapBytes]).
    public static final long DERIVE_SEAL_PENDING_CAP_BYTES = 0L;
    /// The largest derived `seal_pending_cap_bytes`: 256 MiB. A GUESS (spec 4.4 item 3): it decouples the heap the sealer may
    /// hold from the off-heap budget, so raising the budget does not raise the heap exposure past this.
    public static final long MAX_DERIVED_SEAL_PENDING_CAP_BYTES = 256 * 1024 * 1024L;

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

    /// Multiplier applied to {@link #readForwardTimeout()} to derive how long one partition backfill may go WITHOUT
    /// PROGRESS and keep its single-flight slot (#1638 N1, B2). Progress is a catch-up page received or a record
    /// applied. Between two progress points a live backfill performs at most one watermark-probe round (parallel, each
    /// probe bounded by {@link #readForwardTimeout()}) and one catch-up page read (bounded by it too), plus local work
    /// (apply, durability sync); 3 covers those two network legs with one leg of slack for the local work. A judgement
    /// from the leg structure, not a measurement.
    public static final int BACKFILL_FLIGHT_IDLE_READ_TIMEOUTS = 3;

    public static StreamingConfig streamingConfig() {
        return new StreamingConfig(timeSpan(5).seconds(),
                                   timeSpan(2).seconds(),
                                   DEFAULT_MAX_READ_RESPONSE_BYTES,
                                   DEFAULT_READ_LINEARIZATION,
                                   DEFAULT_RESHUFFLE_CONCURRENCY,
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS,
                                   DERIVE_SEGMENT_DISK_MAX_BYTES,
                                   DERIVE_DHT_SEGMENT_MAX_BYTES,
                                   DEFAULT_ISR_LAG_MAX,
                                   DEFAULT_PROMOTION_ESCAPE_AFTER,
                                   DEFAULT_SEAL_RUN_BYTES,
                                   DEFAULT_SEAL_RUN_EVENTS,
                                   DEFAULT_SEAL_RUN_AGE,
                                   DEFAULT_SEAL_MERGE_BYTES,
                                   DERIVE_MAX_MEMORY_BYTES,
                                   DERIVE_SEAL_PENDING_CAP_BYTES);
    }

    public static StreamingConfig streamingConfig(TimeSpan publishForwardTimeout,
                                                  TimeSpan readForwardTimeout,
                                                  long maxReadResponseBytes) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   DEFAULT_READ_LINEARIZATION,
                                   DEFAULT_RESHUFFLE_CONCURRENCY,
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS,
                                   DERIVE_SEGMENT_DISK_MAX_BYTES,
                                   DERIVE_DHT_SEGMENT_MAX_BYTES,
                                   DEFAULT_ISR_LAG_MAX,
                                   DEFAULT_PROMOTION_ESCAPE_AFTER,
                                   DEFAULT_SEAL_RUN_BYTES,
                                   DEFAULT_SEAL_RUN_EVENTS,
                                   DEFAULT_SEAL_RUN_AGE,
                                   DEFAULT_SEAL_MERGE_BYTES,
                                   DERIVE_MAX_MEMORY_BYTES,
                                   DERIVE_SEAL_PENDING_CAP_BYTES);
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
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS,
                                   DERIVE_SEGMENT_DISK_MAX_BYTES,
                                   DERIVE_DHT_SEGMENT_MAX_BYTES,
                                   DEFAULT_ISR_LAG_MAX,
                                   DEFAULT_PROMOTION_ESCAPE_AFTER,
                                   DEFAULT_SEAL_RUN_BYTES,
                                   DEFAULT_SEAL_RUN_EVENTS,
                                   DEFAULT_SEAL_RUN_AGE,
                                   DEFAULT_SEAL_MERGE_BYTES,
                                   DERIVE_MAX_MEMORY_BYTES,
                                   DERIVE_SEAL_PENDING_CAP_BYTES);
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
                                   DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS,
                                   DERIVE_SEGMENT_DISK_MAX_BYTES,
                                   DERIVE_DHT_SEGMENT_MAX_BYTES,
                                   DEFAULT_ISR_LAG_MAX,
                                   DEFAULT_PROMOTION_ESCAPE_AFTER,
                                   DEFAULT_SEAL_RUN_BYTES,
                                   DEFAULT_SEAL_RUN_EVENTS,
                                   DEFAULT_SEAL_RUN_AGE,
                                   DEFAULT_SEAL_MERGE_BYTES,
                                   DERIVE_MAX_MEMORY_BYTES,
                                   DERIVE_SEAL_PENDING_CAP_BYTES);
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
                                   caughtUpMaxLagOffsets,
                                   DERIVE_SEGMENT_DISK_MAX_BYTES,
                                   DERIVE_DHT_SEGMENT_MAX_BYTES,
                                   DEFAULT_ISR_LAG_MAX,
                                   DEFAULT_PROMOTION_ESCAPE_AFTER,
                                   DEFAULT_SEAL_RUN_BYTES,
                                   DEFAULT_SEAL_RUN_EVENTS,
                                   DEFAULT_SEAL_RUN_AGE,
                                   DEFAULT_SEAL_MERGE_BYTES,
                                   DERIVE_MAX_MEMORY_BYTES,
                                   DERIVE_SEAL_PENDING_CAP_BYTES);
    }

    /// The same streaming config with the streams disk-tier cap set (`segment_disk_max_bytes`, #1604);
    /// [#DERIVE_SEGMENT_DISK_MAX_BYTES] derives it from the filesystem.
    public StreamingConfig withSegmentDiskMaxBytes(long segmentDiskMaxBytes) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets,
                                   segmentDiskMaxBytes,
                                   dhtSegmentMaxBytes,
                                   isrLagMax,
                                   promotionEscapeAfter,
                                   sealRunBytes,
                                   sealRunEvents,
                                   sealRunAge,
                                   sealMergeBytes,
                                   maxMemoryBytes,
                                   sealPendingCapBytes);
    }

    /// The same streaming config with the cap on this node's DHT bytes of `stream-segments` set (`dht_segment_max_bytes`,
    /// #2258); [#DERIVE_DHT_SEGMENT_MAX_BYTES] derives it from the heap.
    public StreamingConfig withDhtSegmentMaxBytes(long dhtSegmentMaxBytes) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets,
                                   segmentDiskMaxBytes,
                                   dhtSegmentMaxBytes,
                                   isrLagMax,
                                   promotionEscapeAfter,
                                   sealRunBytes,
                                   sealRunEvents,
                                   sealRunAge,
                                   sealMergeBytes,
                                   maxMemoryBytes,
                                   sealPendingCapBytes);
    }

    /// The cap on the live bytes of `stream-segments` in this node's DHT engine: the configured value, or when it is
    /// [#DERIVE_DHT_SEGMENT_MAX_BYTES] the larger of [#DHT_SEGMENT_MAX_BYTES_FLOOR] and a quarter of `maxHeapBytes`.
    public long effectiveDhtSegmentMaxBytes(long maxHeapBytes) {
        return dhtSegmentMaxBytes == DERIVE_DHT_SEGMENT_MAX_BYTES
               ? Math.max(DHT_SEGMENT_MAX_BYTES_FLOOR, maxHeapBytes / 4)
               : dhtSegmentMaxBytes;
    }

    /// The same streaming config with the ISR lag bound set (`isr_lag_max`, #1730).
    public StreamingConfig withIsrLagMax(TimeSpan isrLagMax) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets,
                                   segmentDiskMaxBytes,
                                   dhtSegmentMaxBytes,
                                   isrLagMax,
                                   promotionEscapeAfter,
                                   sealRunBytes,
                                   sealRunEvents,
                                   sealRunAge,
                                   sealMergeBytes,
                                   maxMemoryBytes,
                                   sealPendingCapBytes);
    }

    /// The same streaming config with the promotion escape bound set (`promotion_escape_after`, #2080).
    public StreamingConfig withPromotionEscapeAfter(TimeSpan promotionEscapeAfter) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets,
                                   segmentDiskMaxBytes,
                                   dhtSegmentMaxBytes,
                                   isrLagMax,
                                   promotionEscapeAfter,
                                   sealRunBytes,
                                   sealRunEvents,
                                   sealRunAge,
                                   sealMergeBytes,
                                   maxMemoryBytes,
                                   sealPendingCapBytes);
    }

    /// The same streaming config with the run-forming sealer's thresholds set (`seal_run_bytes`, `seal_run_events`,
    /// `seal_run_age`, `seal_merge_bytes`; #2201 C-02).
    public StreamingConfig withSealRun(long sealRunBytes, int sealRunEvents, TimeSpan sealRunAge, long sealMergeBytes) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets,
                                   segmentDiskMaxBytes,
                                   dhtSegmentMaxBytes,
                                   isrLagMax,
                                   promotionEscapeAfter,
                                   sealRunBytes,
                                   sealRunEvents,
                                   sealRunAge,
                                   sealMergeBytes,
                                   maxMemoryBytes,
                                   sealPendingCapBytes);
    }

    /// The same streaming config with the off-heap stream memory budget and the sealer's pending-seal heap cap set
    /// (`max_memory_bytes`, `seal_pending_cap_bytes`; #2201 C-03); [#DERIVE_MAX_MEMORY_BYTES] and
    /// [#DERIVE_SEAL_PENDING_CAP_BYTES] derive them.
    public StreamingConfig withMemoryBudget(long maxMemoryBytes, long sealPendingCapBytes) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets,
                                   segmentDiskMaxBytes,
                                   dhtSegmentMaxBytes,
                                   isrLagMax,
                                   promotionEscapeAfter,
                                   sealRunBytes,
                                   sealRunEvents,
                                   sealRunAge,
                                   sealMergeBytes,
                                   maxMemoryBytes,
                                   sealPendingCapBytes);
    }

    /// The off-heap stream memory budget a node derives when `max_memory_bytes` is unset: one eighth of `totalMemoryBytes`
    /// (the host's physical memory, as the JVM sees it inside a container), clamped to
    /// [[#MIN_DERIVED_MAX_MEMORY_BYTES], [#MAX_DERIVED_MAX_MEMORY_BYTES]].
    public static long derivedMaxMemoryBytes(long totalMemoryBytes) {
        return Math.clamp(totalMemoryBytes / 8, MIN_DERIVED_MAX_MEMORY_BYTES, MAX_DERIVED_MAX_MEMORY_BYTES);
    }

    /// The sealer's pending-seal heap cap: the configured value, or when it is [#DERIVE_SEAL_PENDING_CAP_BYTES] the smaller
    /// of `maxMemoryBytes` (the node's resolved off-heap budget) and [#MAX_DERIVED_SEAL_PENDING_CAP_BYTES].
    public long effectiveSealPendingCapBytes(long maxMemoryBytes) {
        return sealPendingCapBytes == DERIVE_SEAL_PENDING_CAP_BYTES
               ? Math.min(maxMemoryBytes, MAX_DERIVED_SEAL_PENDING_CAP_BYTES)
               : sealPendingCapBytes;
    }

    /// The owner activation gate's alarm bound (#1555 item 8): two SWIM suspect windows. The promotion escape bound may not be below it.
    public static TimeSpan ownerPromotionAlarmWindow(TimeSpan suspectTimeout) {
        return suspectTimeout.plus(suspectTimeout);
    }

    /// The same streaming config with the `LINEARIZABLE`-read mechanism replaced — used by the config
    /// loader to apply the parsed `[durable-entity] read-linearization` knob onto the streaming config.
    public StreamingConfig withReadLinearization(ReadLinearizationMode readLinearization) {
        return new StreamingConfig(publishForwardTimeout,
                                   readForwardTimeout,
                                   maxReadResponseBytes,
                                   readLinearization,
                                   reshuffleConcurrency,
                                   caughtUpMaxLagOffsets,
                                   segmentDiskMaxBytes,
                                   dhtSegmentMaxBytes,
                                   isrLagMax,
                                   promotionEscapeAfter,
                                   sealRunBytes,
                                   sealRunEvents,
                                   sealRunAge,
                                   sealMergeBytes,
                                   maxMemoryBytes,
                                   sealPendingCapBytes);
    }

    /// Bounded wait for a caught-up source to appear before a cold-start replica self-promotes. Derived
    /// from {@link #readForwardTimeout()} so it scales with the configured transport latency budget.
    public TimeSpan backfillSourceWaitBound() {
        return readForwardTimeout.plus(readForwardTimeout.nanos() * (BACKFILL_SOURCE_WAIT_PROBE_CYCLES - 1L));
    }

    /// How long one partition backfill may go without progress and keep its single-flight slot (#1638 N1, B2):
    /// {@link #readForwardTimeout()} times {@link #BACKFILL_FLIGHT_IDLE_READ_TIMEOUTS}.
    public TimeSpan backfillFlightIdleBound() {
        return readForwardTimeout.plus(readForwardTimeout.nanos() * (BACKFILL_FLIGHT_IDLE_READ_TIMEOUTS - 1L));
    }
}
