package org.pragmatica.aether.api;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;

import java.util.List;

/**
 * Events for the internal alert stream.
 *
 * <p>Components can subscribe to these events via MessageRouter
 * for automated responses (e.g., rollback triggers).
 */
public sealed interface AlertEvent {
    /**
     * Alert severity levels.
     */
    enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * Common alert metadata.
     */
    String alertId();
    long timestamp();
    Severity severity();

    /**
     * Alert raised when a metric threshold is exceeded.
     *
     * @param alertId Unique identifier for this alert
     * @param timestamp When the alert was raised
     * @param severity Alert severity
     * @param metric The metric that triggered the alert
     * @param nodeId The node where the threshold was exceeded
     * @param value Current metric value
     * @param threshold Threshold that was exceeded
     */
    record ThresholdAlert(String alertId,
                          long timestamp,
                          Severity severity,
                          String metric,
                          NodeId nodeId,
                          double value,
                          double threshold) implements AlertEvent {}

    /**
     * Alert raised when all instances of a slice fail.
     *
     * @param alertId Unique identifier for this alert
     * @param timestamp When the alert was raised
     * @param severity Always CRITICAL
     * @param artifact The failing slice
     * @param method The method being invoked
     * @param requestId Distributed tracing ID
     * @param attemptedNodes Nodes that were tried
     * @param lastError Description of the last error
     */
    record SliceFailureAlert(String alertId,
                             long timestamp,
                             Severity severity,
                             Artifact artifact,
                             MethodName method,
                             String requestId,
                             List<NodeId> attemptedNodes,
                             String lastError) implements AlertEvent {
        public static SliceFailureAlert sliceFailureAlert(String alertId,
                                                          Artifact artifact,
                                                          MethodName method,
                                                          String requestId,
                                                          List<NodeId> attemptedNodes,
                                                          String lastError) {
            return new SliceFailureAlert(alertId,
                                         System.currentTimeMillis(),
                                         Severity.CRITICAL,
                                         artifact,
                                         method,
                                         requestId,
                                         attemptedNodes,
                                         lastError);
        }
    }

    /**
     * Alert resolved notification.
     *
     * @param alertId The resolved alert ID
     * @param timestamp When the resolution occurred
     * @param severity Original alert severity
     * @param resolvedBy How/by whom the alert was resolved
     */
    record AlertResolved(String alertId,
                         long timestamp,
                         Severity severity,
                         String resolvedBy) implements AlertEvent {
        public static AlertResolved resolved(String alertId, String resolvedBy) {
            return new AlertResolved(alertId, System.currentTimeMillis(), Severity.INFO, resolvedBy);
        }
    }
}
