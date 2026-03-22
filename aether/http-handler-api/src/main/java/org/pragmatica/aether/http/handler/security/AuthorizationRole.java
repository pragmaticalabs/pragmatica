package org.pragmatica.aether.http.handler.security;

/// Authorization roles for management API access.
/// Roles are hierarchical: ADMIN > OPERATOR > VIEWER.
///
/// Used by {@link RoutePermission} to enforce minimum access levels
/// on management API endpoints. Distinct from {@link Role} which is
/// a string-based identity role for authentication context.
public enum AuthorizationRole {
    /// Full access - deploy, drain, shutdown, config, RBAC management.
    ADMIN,
    /// Operational access - status, scaling, drain, schema retry, backup.
    OPERATOR,
    /// Read-only access - status, metrics, logs, traces.
    VIEWER;

    /// Check if this role has at least the required access level.
    /// Uses ordinal ordering: ADMIN(0) <= any, OPERATOR(1) <= OPERATOR or VIEWER, VIEWER(2) <= only VIEWER.
    public boolean hasAccess(AuthorizationRole required) {
        return this.ordinal() <= required.ordinal();
    }
}
