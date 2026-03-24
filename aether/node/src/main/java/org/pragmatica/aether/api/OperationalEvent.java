package org.pragmatica.aether.api;

import org.pragmatica.messaging.Message;

/// Operational audit events emitted to the cluster event stream.
/// Covers security, lifecycle, and configuration changes that operators
/// and AI agents need for full operational awareness.
///
/// These events flow through the MessageRouter to ClusterEventAggregator,
/// which converts them to ClusterEvent records for dashboard and API consumption.
public sealed interface OperationalEvent extends Message.Local {
    record AccessDenied(String principal,
                        String method,
                        String path,
                        String actualRole,
                        String requiredRole,
                        long timestamp) implements OperationalEvent {
        public static AccessDenied accessDenied(String principal,
                                                String method,
                                                String path,
                                                String actualRole,
                                                String requiredRole) {
            return new AccessDenied(principal, method, path, actualRole, requiredRole, System.currentTimeMillis());
        }
    }

    record NodeLifecycleChanged(String nodeId,
                                String transition,
                                String requestedBy,
                                long timestamp) implements OperationalEvent {
        public static NodeLifecycleChanged nodeLifecycleChanged(String nodeId, String transition, String requestedBy) {
            return new NodeLifecycleChanged(nodeId, transition, requestedBy, System.currentTimeMillis());
        }
    }

    record ConfigChanged(String key,
                         String scope,
                         String action,
                         String requestedBy,
                         long timestamp) implements OperationalEvent {
        public static ConfigChanged configChanged(String key, String scope, String action, String requestedBy) {
            return new ConfigChanged(key, scope, action, requestedBy, System.currentTimeMillis());
        }
    }

    record BackupCreated(String commitId, String requestedBy, long timestamp) implements OperationalEvent {
        public static BackupCreated backupCreated(String commitId, String requestedBy) {
            return new BackupCreated(commitId, requestedBy, System.currentTimeMillis());
        }
    }

    record BackupRestored(String commitId, String requestedBy, long timestamp) implements OperationalEvent {
        public static BackupRestored backupRestored(String commitId, String requestedBy) {
            return new BackupRestored(commitId, requestedBy, System.currentTimeMillis());
        }
    }

    record BlueprintDeployed(String artifactCoords, String requestedBy, long timestamp) implements OperationalEvent {
        public static BlueprintDeployed blueprintDeployed(String artifactCoords, String requestedBy) {
            return new BlueprintDeployed(artifactCoords, requestedBy, System.currentTimeMillis());
        }
    }

    record BlueprintDeleted(String artifactId, String requestedBy, long timestamp) implements OperationalEvent {
        public static BlueprintDeleted blueprintDeleted(String artifactId, String requestedBy) {
            return new BlueprintDeleted(artifactId, requestedBy, System.currentTimeMillis());
        }
    }
}
