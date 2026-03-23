package org.pragmatica.aether.http.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Dedicated audit logger for security events.
///
/// Uses a separate logger name (org.pragmatica.aether.audit) so security events
/// can be routed to a dedicated log file/sink via logback configuration.
/// Never logs raw API key values — only principals and paths.
@SuppressWarnings("JBCT-RET-01")
public final class AuditLog {
    private static final Logger AUDIT = LoggerFactory.getLogger("org.pragmatica.aether.audit");

    private AuditLog() {}

    /// Log successful authentication.
    public static void authSuccess(String requestId, String principal, String method, String path) {
        AUDIT.debug("AUTH_SUCCESS requestId={} principal={} method={} path={}", requestId, principal, method, path);
    }

    /// Log authentication failure.
    public static void authFailure(String requestId, String reason, String method, String path) {
        AUDIT.warn("AUTH_FAILURE requestId={} reason={} method={} path={}", requestId, reason, method, path);
    }

    /// Log management API access.
    public static void managementAccess(String requestId, String principal, String method, String path) {
        AUDIT.debug("MGMT_ACCESS requestId={} principal={} method={} path={}", requestId, principal, method, path);
    }

    /// Log WebSocket authentication success.
    public static void wsAuthSuccess(String sessionId, String principal) {
        AUDIT.debug("WS_AUTH_SUCCESS sessionId={} principal={}", sessionId, principal);
    }

    /// Log WebSocket authentication failure.
    public static void wsAuthFailure(String sessionId, String reason) {
        AUDIT.warn("WS_AUTH_FAILURE sessionId={} reason={}", sessionId, reason);
    }

    /// Log deployment strategy start.
    public static void deploymentStart(String type, String id, String artifact, String oldVersion, String newVersion) {
        AUDIT.info("DEPLOY_START type={} id={} artifact={} oldVersion={} newVersion={}",
                   type,
                   id,
                   artifact,
                   oldVersion,
                   newVersion);
    }

    /// Log deployment strategy promotion/advancement.
    public static void deploymentPromote(String type, String id, String artifact, String routing) {
        AUDIT.info("DEPLOY_PROMOTE type={} id={} artifact={} routing={}", type, id, artifact, routing);
    }

    /// Log deployment strategy rollback.
    public static void deploymentRollback(String type, String id, String artifact, String reason) {
        AUDIT.warn("DEPLOY_ROLLBACK type={} id={} artifact={} reason={}", type, id, artifact, reason);
    }

    /// Log deployment strategy completion.
    public static void deploymentComplete(String type, String id, String artifact) {
        AUDIT.info("DEPLOY_COMPLETE type={} id={} artifact={}", type, id, artifact);
    }

    /// Log automatic rollback triggered by health evaluation.
    public static void deploymentAutoRollback(String type, String id, String artifact, String verdict) {
        AUDIT.warn("DEPLOY_AUTO_ROLLBACK type={} id={} artifact={} verdict={}", type, id, artifact, verdict);
    }

    /// Log dynamic config set.
    public static void configSet(String key, String scope) {
        AUDIT.info("CONFIG_SET key={} scope={}", key, scope);
    }

    /// Log dynamic config removed.
    public static void configRemoved(String key, String scope) {
        AUDIT.info("CONFIG_REMOVED key={} scope={}", key, scope);
    }

    /// Log backup created.
    public static void backupCreated(boolean success, String message) {
        AUDIT.info("BACKUP_CREATED success={} message={}", success, message);
    }

    /// Log backup restored.
    public static void backupRestored(boolean success, String commitId, String message) {
        AUDIT.info("BACKUP_RESTORED success={} commitId={} message={}", success, commitId, message);
    }

    /// Log node lifecycle transition.
    public static void nodeLifecycleTransition(String nodeId, String targetState, boolean success, String message) {
        AUDIT.info("NODE_LIFECYCLE_TRANSITION nodeId={} targetState={} success={} message={}", nodeId, targetState, success, message);
    }

    /// Log blueprint deployed.
    public static void blueprintDeployed(String blueprintId, int sliceCount) {
        AUDIT.info("BLUEPRINT_DEPLOYED blueprintId={} sliceCount={}", blueprintId, sliceCount);
    }

    /// Log blueprint deleted.
    public static void blueprintDeleted(String blueprintId) {
        AUDIT.info("BLUEPRINT_DELETED blueprintId={}", blueprintId);
    }

    /// Log schema manual retry requested.
    public static void schemaManualRetry(String datasource) {
        AUDIT.info("SCHEMA_MANUAL_RETRY datasource={}", datasource);
    }

    /// Log authorization denial — principal lacked required role for the requested endpoint.
    public static void accessDenied(String principal,
                                     String method,
                                     String path,
                                     String actualRole,
                                     String requiredRole) {
        AUDIT.warn("ACCESS_DENIED principal={} method={} path={} role={} required={}",
                   principal, method, path, actualRole, requiredRole);
    }
}
