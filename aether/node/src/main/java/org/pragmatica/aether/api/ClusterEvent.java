package org.pragmatica.aether.api;

import java.time.Instant;
import java.util.Map;

/// Structured cluster event for dashboard timeline and LLM consumption.
///
/// Events are created by ClusterEventAggregator from MessageRouter fan-out
/// and stored in a bounded ring buffer.
///
/// @param timestamp when the event occurred
/// @param type the category of event
/// @param severity the severity level
/// @param summary human-readable description
/// @param details context-specific key-value pairs
public record ClusterEvent(Instant timestamp,
                           EventType type,
                           Severity severity,
                           String summary,
                           Map<String, String> details) {
    public enum EventType {
        NODE_JOINED,
        NODE_LEFT,
        NODE_FAILED,
        LEADER_ELECTED,
        LEADER_LOST,
        QUORUM_ESTABLISHED,
        QUORUM_LOST,
        DEPLOYMENT_STARTED,
        DEPLOYMENT_COMPLETED,
        DEPLOYMENT_FAILED,
        SLICE_FAILURE,
        CONNECTION_ESTABLISHED,
        CONNECTION_FAILED
    }

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /// Factory method following JBCT naming convention.
    public static ClusterEvent clusterEvent(EventType type,
                                            Severity severity,
                                            String summary,
                                            Map<String, String> details) {
        return new ClusterEvent(Instant.now(), type, severity, summary, details);
    }
}
