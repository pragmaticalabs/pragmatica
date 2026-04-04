package org.pragmatica.aether.metrics.network;

public record NetworkMetrics(long bytesRead,
                             long bytesWritten,
                             long messagesRead,
                             long messagesWritten,
                             int activeConnections,
                             int backpressureEvents,
                             long lastBackpressureTimestamp) {
    public static final NetworkMetrics EMPTY = new NetworkMetrics(0, 0, 0, 0, 0, 0, 0);

    public long totalBytes() {
        return bytesRead + bytesWritten;
    }

    public long totalMessages() {
        return messagesRead + messagesWritten;
    }

    public double avgReadMessageSize() {
        if (messagesRead == 0) {return 0.0;}
        return bytesRead / (double) messagesRead;
    }

    public double avgWriteMessageSize() {
        if (messagesWritten == 0) {return 0.0;}
        return bytesWritten / (double) messagesWritten;
    }

    public boolean isUnderBackpressure(long windowMs) {
        if (lastBackpressureTimestamp == 0) {return false;}
        return System.currentTimeMillis() - lastBackpressureTimestamp <windowMs;
    }
}
