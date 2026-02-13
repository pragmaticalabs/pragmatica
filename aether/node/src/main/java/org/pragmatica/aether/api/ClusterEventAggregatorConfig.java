package org.pragmatica.aether.api;

/// Configuration for the cluster event aggregator.
///
/// @param maxEvents maximum number of events to retain in the ring buffer
public record ClusterEventAggregatorConfig(int maxEvents) {
    public static final int DEFAULT_MAX_EVENTS = 1000;

    /// Create a default configuration with 1000 max events.
    public static ClusterEventAggregatorConfig defaultConfig() {
        return new ClusterEventAggregatorConfig(DEFAULT_MAX_EVENTS);
    }
}
