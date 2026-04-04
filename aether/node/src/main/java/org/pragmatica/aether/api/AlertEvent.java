package org.pragmatica.aether.api;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;

import java.util.List;


/// Events for the internal alert stream.
///
///
/// Components can subscribe to these events via MessageRouter
/// for automated responses (e.g., rollback triggers).
public sealed interface AlertEvent {
    enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    String alertId();
    long timestamp();
    Severity severity();

    record ThresholdAlert(String alertId,
                          long timestamp,
                          Severity severity,
                          String metric,
                          NodeId nodeId,
                          double value,
                          double threshold) implements AlertEvent{}

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

    record AlertResolved(String alertId, long timestamp, Severity severity, String resolvedBy) implements AlertEvent {
        public static AlertResolved resolved(String alertId, String resolvedBy) {
            return new AlertResolved(alertId, System.currentTimeMillis(), Severity.INFO, resolvedBy);
        }
    }
}
