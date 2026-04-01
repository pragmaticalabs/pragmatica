package org.pragmatica.aether.metrics.gc;
public record GCMetrics( long youngGcCount,
                         long youngGcPauseMs,
                         long oldGcCount,
                         long oldGcPauseMs,
                         long reclaimedBytes,
                         long allocationRateBytesPerSec,
                         long promotionRateBytesPerSec,
                         long lastMajorGcTimestamp) {
    public static final GCMetrics EMPTY = new GCMetrics(0, 0, 0, 0, 0, 0, 0, 0);

    /// Total GC count (young + old).
    public long totalGcCount() {
        return youngGcCount + oldGcCount;
    }

    /// Total GC pause time in milliseconds.
    public long totalPauseMs() {
        return youngGcPauseMs + oldGcPauseMs;
    }

    /// Average pause time per GC event in milliseconds.
    public double avgPauseMs() {
        long total = totalGcCount();
        if ( total == 0) {
        return 0.0;}
        return totalPauseMs() / (double) total;
    }
}
