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
}
