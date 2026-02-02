package org.pragmatica.aether.metrics.network;
/**
 * Snapshot of network I/O metrics for observability.
 *
 * @param bytesRead              Total bytes read from network
 * @param bytesWritten           Total bytes written to network
 * @param messagesRead           Total messages/frames read
 * @param messagesWritten        Total messages/frames written
 * @param activeConnections      Number of currently active connections
 * @param backpressureEvents     Number of channelWritabilityChanged(false) events
 * @param lastBackpressureTimestamp Timestamp of last backpressure event (0 if none)
 */
public record NetworkMetrics(long bytesRead,
                             long bytesWritten,
                             long messagesRead,
                             long messagesWritten,
                             int activeConnections,
                             int backpressureEvents,
                             long lastBackpressureTimestamp) {
    public static final NetworkMetrics EMPTY = new NetworkMetrics(0, 0, 0, 0, 0, 0, 0);

    /**
     * Total bytes transferred (read + written).
     */
    public long totalBytes() {
        return bytesRead + bytesWritten;
    }

    /**
     * Total messages transferred (read + written).
     */
    public long totalMessages() {
        return messagesRead + messagesWritten;
    }

    /**
     * Average message size in bytes (read direction).
     */
    public double avgReadMessageSize() {
        if (messagesRead == 0) {
            return 0.0;
        }
        return bytesRead / (double) messagesRead;
    }

    /**
     * Average message size in bytes (write direction).
     */
    public double avgWriteMessageSize() {
        if (messagesWritten == 0) {
            return 0.0;
        }
        return bytesWritten / (double) messagesWritten;
    }

    /**
     * Check if currently under backpressure (recent event).
     */
    public boolean isUnderBackpressure(long windowMs) {
        if (lastBackpressureTimestamp == 0) {
            return false;
        }
        return System.currentTimeMillis() - lastBackpressureTimestamp < windowMs;
    }
}
