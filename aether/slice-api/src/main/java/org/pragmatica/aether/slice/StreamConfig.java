package org.pragmatica.aether.slice;
/// Configuration for a stream, parsed from blueprint TOML `[streams.xxx]` sections.
///
/// Defines the stream name, partition count, retention policy, and default consumer offset.
public record StreamConfig(String name,
                           int partitions,
                           RetentionPolicy retention,
                           String autoOffsetReset) {
    private static final int DEFAULT_PARTITIONS = 4;
    private static final String DEFAULT_AUTO_OFFSET_RESET = "latest";

    /// Create a stream configuration with defaults (4 partitions, default retention, "latest" offset).
    public static StreamConfig streamConfig(String name) {
        return new StreamConfig(name, DEFAULT_PARTITIONS, RetentionPolicy.retentionPolicy(), DEFAULT_AUTO_OFFSET_RESET);
    }

    /// Create a stream configuration with custom values.
    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset) {
        return new StreamConfig(name, partitions, retention, autoOffsetReset);
    }
}
