package org.pragmatica.aether.http.handler.security;

import org.pragmatica.serialization.Codec;


/// Authorization roles for management API access.
/// Roles are hierarchical: ADMIN > OPERATOR > VIEWER.
///
/// Used by {@link RoutePermission} to enforce minimum access levels
/// on management API endpoints. Distinct from {@link Role} which is
/// a string-based identity role for authentication context.
@Codec public enum AuthorizationRole {
    ADMIN,
    OPERATOR,
    VIEWER;
    public boolean hasAccess(AuthorizationRole required) {
        return this.ordinal() <= required.ordinal();
    }
}
