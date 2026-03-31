package org.pragmatica.aether.slice;
/// Configuration for a stream, parsed from blueprint TOML `[streams.xxx]` sections.
///
/// Defines the stream name, partition count, retention policy, default consumer offset,
/// and maximum event size.
public record StreamConfig(String name,
                           int partitions,
                           RetentionPolicy retention,
                           String autoOffsetReset,
                           long maxEventSizeBytes,
                           ConsistencyMode consistencyMode) {
    private static final int DEFAULT_PARTITIONS = 4;
    private static final String DEFAULT_AUTO_OFFSET_RESET = "latest";
    private static final long DEFAULT_MAX_EVENT_SIZE_BYTES = 1_048_576L;

    /// Create a stream configuration with defaults (4 partitions, default retention, "latest" offset, 1MB max event, EVENTUAL).
    public static StreamConfig streamConfig(String name) {
        return new StreamConfig(name,
                                DEFAULT_PARTITIONS,
                                RetentionPolicy.retentionPolicy(),
                                DEFAULT_AUTO_OFFSET_RESET,
                                DEFAULT_MAX_EVENT_SIZE_BYTES,
                                ConsistencyMode.EVENTUAL);
    }

    /// Create a stream configuration with core values (uses default max event size, EVENTUAL consistency).
    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset) {
        return new StreamConfig(name, partitions, retention, autoOffsetReset, DEFAULT_MAX_EVENT_SIZE_BYTES, ConsistencyMode.EVENTUAL);
    }

    /// Create a stream configuration with all values except consistency mode (defaults to EVENTUAL).
    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes) {
        return new StreamConfig(name, partitions, retention, autoOffsetReset, maxEventSizeBytes, ConsistencyMode.EVENTUAL);
    }

    /// Create a stream configuration with all values including consistency mode.
    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes,
                                            ConsistencyMode consistencyMode) {
        return new StreamConfig(name, partitions, retention, autoOffsetReset, maxEventSizeBytes, consistencyMode);
    }
}
