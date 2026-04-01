package org.pragmatica.aether.http.handler.security;
public record RoutePermission( AuthorizationRole minimumRole) {
    /// Only ADMIN role can access.
    public static final RoutePermission ADMIN_ONLY = new RoutePermission(AuthorizationRole.ADMIN);

    /// ADMIN and OPERATOR roles can access.
    public static final RoutePermission OPERATOR_AND_ABOVE = new RoutePermission(AuthorizationRole.OPERATOR);

    /// Any authenticated role (ADMIN, OPERATOR, VIEWER) can access.
    public static final RoutePermission ALL_AUTHENTICATED = new RoutePermission(AuthorizationRole.VIEWER);

    /// Factory method for creating route permissions.
    ///
    /// @param minimumRole the minimum role required
    /// @return a RoutePermission with the specified minimum role
    public static RoutePermission routePermission(AuthorizationRole minimumRole) {
        return new RoutePermission(minimumRole);
    }

    /// Check if the given role satisfies this permission.
    ///
    /// @param role the role to check
    /// @return true if the role meets or exceeds the minimum requirement
    public boolean allows(AuthorizationRole role) {
        return role.hasAccess(minimumRole);
    }
}
