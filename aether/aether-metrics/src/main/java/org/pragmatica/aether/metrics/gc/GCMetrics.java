package org.pragmatica.aether.metrics.gc;
/**
 * Snapshot of GC metrics for observability.
 *
 * @param youngGcCount           Number of young generation GC events
 * @param youngGcPauseMs         Total pause time for young GC in milliseconds
 * @param oldGcCount             Number of old generation (major) GC events
 * @param oldGcPauseMs           Total pause time for old GC in milliseconds
 * @param reclaimedBytes         Total bytes reclaimed across all GC events
 * @param allocationRateBytesPerSec Heap allocation rate (bytes/second)
 * @param promotionRateBytesPerSec  Promotion rate to old gen (bytes/second)
 * @param lastMajorGcTimestamp   Timestamp of last major GC (0 if none)
 */
public record GCMetrics(long youngGcCount,
                        long youngGcPauseMs,
                        long oldGcCount,
                        long oldGcPauseMs,
                        long reclaimedBytes,
                        long allocationRateBytesPerSec,
                        long promotionRateBytesPerSec,
                        long lastMajorGcTimestamp) {
    public static final GCMetrics EMPTY = new GCMetrics(0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Total GC count (young + old).
     */
    public long totalGcCount() {
        return youngGcCount + oldGcCount;
    }

    /**
     * Total GC pause time in milliseconds.
     */
    public long totalPauseMs() {
        return youngGcPauseMs + oldGcPauseMs;
    }

    /**
     * Average pause time per GC event in milliseconds.
     */
    public double avgPauseMs() {
        long total = totalGcCount();
        if (total == 0) {
            return 0.0;
        }
        return totalPauseMs() / (double) total;
    }
}
