package org.pragmatica.aether.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Audit logger for deployment-level events (schema migrations, scaling decisions).
///
/// Uses the same logger name (org.pragmatica.aether.audit) as the node-level AuditLog
/// so all audit events are routed to a single sink via logback configuration.
@SuppressWarnings("JBCT-RET-01") public final class AuditLog {
    private static final Logger AUDIT = LoggerFactory.getLogger("org.pragmatica.aether.audit");

    private AuditLog() {}

    public static void schemaMigrationStarted(String datasource, String artifactCoords, String nodeId) {
        AUDIT.info("SCHEMA_MIGRATION_STARTED datasource={} artifact={} nodeId={}", datasource, artifactCoords, nodeId);
    }

    public static void schemaMigrationCompleted(String datasource,
                                                String artifactCoords,
                                                int appliedCount,
                                                int currentVersion,
                                                long durationMs) {
        AUDIT.info("SCHEMA_MIGRATION_COMPLETED datasource={} artifact={} appliedCount={} currentVersion={} durationMs={}",
                   datasource,
                   artifactCoords,
                   appliedCount,
                   currentVersion,
                   durationMs);
    }

    public static void schemaMigrationFailed(String datasource,
                                             String artifactCoords,
                                             String classification,
                                             String cause) {
        AUDIT.warn("SCHEMA_MIGRATION_FAILED datasource={} artifact={} classification={} cause={}",
                   datasource,
                   artifactCoords,
                   classification,
                   cause);
    }

    public static void schemaMigrationRetrying(String datasource,
                                               String artifactCoords,
                                               int attemptNumber,
                                               long nextRetryMs) {
        AUDIT.info("SCHEMA_MIGRATION_RETRYING datasource={} artifact={} attempt={} nextRetryMs={}",
                   datasource,
                   artifactCoords,
                   attemptNumber,
                   nextRetryMs);
    }

    public static void reconciliationScaleUp(String artifact, int currentInstances, int desiredInstances) {
        AUDIT.info("RECONCILIATION_SCALE_UP artifact={} currentInstances={} desiredInstances={}",
                   artifact,
                   currentInstances,
                   desiredInstances);
    }

    public static void reconciliationScaleDown(String artifact, int currentInstances, int desiredInstances) {
        AUDIT.info("RECONCILIATION_SCALE_DOWN artifact={} currentInstances={} desiredInstances={}",
                   artifact,
                   currentInstances,
                   desiredInstances);
    }
}
