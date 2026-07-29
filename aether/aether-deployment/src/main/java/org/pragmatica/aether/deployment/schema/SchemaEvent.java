// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.util.List;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;


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

    /// Emitted by the cluster deployment leader when a FAILED schema record is observed (#542).
    /// `MigrationFailed` reports the failure itself and always carries an empty `blockedSlices` —
    /// the orchestrator has no deployment state to consult. This event reports the *consequence*:
    /// the slices of `owningBlueprint` whose activation the failed migration is holding, which only
    /// the leader can compute. It is what an operator reads to learn WHY a blueprint is stuck.
    record ActivationBlocked(String datasource,
                             BlueprintId owningBlueprint,
                             List<String> blockedSlices,
                             String artifactCoords,
                             int attemptCount,
                             long timestamp) implements SchemaEvent {
        public static ActivationBlocked activationBlocked(String datasource,
                                                          BlueprintId owningBlueprint,
                                                          List<String> blockedSlices,
                                                          String artifactCoords,
                                                          int attemptCount) {
            return new ActivationBlocked(datasource,
                                         owningBlueprint,
                                         List.copyOf(blockedSlices),
                                         artifactCoords,
                                         attemptCount,
                                         System.currentTimeMillis());
        }
    }
}
