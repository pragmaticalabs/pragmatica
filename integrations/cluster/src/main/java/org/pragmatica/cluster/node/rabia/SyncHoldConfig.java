package org.pragmatica.cluster.node.rabia;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for the leader-side `SyncHoldRegistry`. Defines the floor, ceiling, and
/// expected throughput used to derive a per-snapshot hold duration via
/// `clamp(snapshotBytes / expectedSyncBps, minHold, maxHold)`.
///
/// Defaults (RC1, per cluster-convergence-reconciler-spec §5.4):
/// - `minHold = 5s` — guards against tiny snapshots whose ingest still triggers a metrics blip.
/// - `maxHold = 60s` — backstop against runaway holds (e.g. corrupted snapshot length).
/// - `expectedSyncBps = 10_000_000.0` (10 MB/s) — conservative throughput floor that biases toward
///   longer holds; over-protecting a syncing node is safer than under-protecting it.
public record SyncHoldConfig(TimeSpan minHold, TimeSpan maxHold, double expectedSyncBps) {
    public static final TimeSpan DEFAULT_MIN_HOLD = timeSpan(5).seconds();
    public static final TimeSpan DEFAULT_MAX_HOLD = timeSpan(60).seconds();
    public static final double DEFAULT_EXPECTED_SYNC_BPS = 10_000_000.0;

    public static SyncHoldConfig defaults() {
        return new SyncHoldConfig(DEFAULT_MIN_HOLD, DEFAULT_MAX_HOLD, DEFAULT_EXPECTED_SYNC_BPS);
    }

    /// Compute the hold duration in milliseconds for a snapshot of `snapshotBytes`. Result is
    /// clamped to `[minHold.millis(), maxHold.millis()]`. Non-positive `snapshotBytes` collapses
    /// to `minHold.millis()`.
    public long holdMs(long snapshotBytes) {
        var minMs = minHold.millis();
        var maxMs = maxHold.millis();

        if (snapshotBytes <= 0L || expectedSyncBps <= 0.0) {
            return minMs;
        }

        var rawMs = (long)((snapshotBytes * 1000.0) / expectedSyncBps);

        if (rawMs < minMs) {
            return minMs;
        }

        if (rawMs > maxMs) {
            return maxMs;
        }

        return rawMs;
    }
}
