package org.pragmatica.aether.deployment.schema;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;

import java.util.List;


/// Events emitted during schema migration lifecycle.
/// The explanation field contains natural language description suitable for both human operators and LLM agents.
public sealed interface SchemaEvent extends Message.Local {
    record MigrationStarted(String datasource, String artifactCoords, NodeId nodeId, long timestamp) implements SchemaEvent {
        public static MigrationStarted migrationStarted(String datasource, String artifactCoords, NodeId nodeId) {
            return new MigrationStarted(datasource, artifactCoords, nodeId, System.currentTimeMillis());
        }
    }

    record MigrationCompleted(String datasource,
                              String artifactCoords,
                              int appliedCount,
                              int currentVersion,
                              long durationMs,
                              NodeId nodeId,
                              long timestamp) implements SchemaEvent {
        public static MigrationCompleted migrationCompleted(String datasource,
                                                            String artifactCoords,
                                                            int appliedCount,
                                                            int currentVersion,
                                                            long durationMs,
                                                            NodeId nodeId) {
            return new MigrationCompleted(datasource,
                                          artifactCoords,
                                          appliedCount,
                                          currentVersion,
                                          durationMs,
                                          nodeId,
                                          System.currentTimeMillis());
        }
    }

    record MigrationFailed(String datasource,
                           String artifactCoords,
                           FailureClassification classification,
                           String causeMessage,
                           List<String> blockedSlices,
                           int attemptNumber,
                           int maxRetries,
                           String explanation,
                           long timestamp) implements SchemaEvent {
        public static MigrationFailed migrationFailed(String datasource,
                                                      String artifactCoords,
                                                      FailureClassification classification,
                                                      String causeMessage,
                                                      List<String> blockedSlices,
                                                      int attemptNumber,
                                                      int maxRetries,
                                                      String explanation) {
            return new MigrationFailed(datasource,
                                       artifactCoords,
                                       classification,
                                       causeMessage,
                                       blockedSlices,
                                       attemptNumber,
                                       maxRetries,
                                       explanation,
                                       System.currentTimeMillis());
        }
    }

    record MigrationRetrying(String datasource,
                             String artifactCoords,
                             int attemptNumber,
                             long nextRetryMs,
                             String explanation,
                             long timestamp) implements SchemaEvent {
        public static MigrationRetrying migrationRetrying(String datasource,
                                                          String artifactCoords,
                                                          int attemptNumber,
                                                          long nextRetryMs,
                                                          String explanation) {
            return new MigrationRetrying(datasource,
                                         artifactCoords,
                                         attemptNumber,
                                         nextRetryMs,
                                         explanation,
                                         System.currentTimeMillis());
        }
    }

    record ManualRetryRequested(String datasource, String requestedBy, long timestamp) implements SchemaEvent {
        public static ManualRetryRequested manualRetryRequested(String datasource, String requestedBy) {
            return new ManualRetryRequested(datasource, requestedBy, System.currentTimeMillis());
        }
    }
}
