package org.pragmatica.aether.http.handler.security;
/// Declares the minimum role required to access a route.
///
/// Used alongside {@link RouteSecurityPolicy} to add authorization
/// checks after authentication succeeds. While {@link RouteSecurityPolicy}
/// determines HOW a request is authenticated, {@link RoutePermission}
/// determines WHAT level of access is required.
///
/// @param minimumRole the minimum {@link AuthorizationRole} needed
public record RoutePermission(AuthorizationRole minimumRole) {
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
