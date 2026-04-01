package org.pragmatica.aether.api;
public record ClusterEventAggregatorConfig( int maxEvents) {
    public static final int DEFAULT_MAX_EVENTS = 1000;

    /// Create a default configuration with 1000 max events.
    public static ClusterEventAggregatorConfig defaultConfig() {
        return new ClusterEventAggregatorConfig(DEFAULT_MAX_EVENTS);
    }
}
