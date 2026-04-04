package org.pragmatica.aether.http.handler.security;

public record RoutePermission(AuthorizationRole minimumRole) {
    public static final RoutePermission ADMIN_ONLY = new RoutePermission(AuthorizationRole.ADMIN);

    public static final RoutePermission OPERATOR_AND_ABOVE = new RoutePermission(AuthorizationRole.OPERATOR);

    public static final RoutePermission ALL_AUTHENTICATED = new RoutePermission(AuthorizationRole.VIEWER);

    public static RoutePermission routePermission(AuthorizationRole minimumRole) {
        return new RoutePermission(minimumRole);
    }

    public boolean allows(AuthorizationRole role) {
        return role.hasAccess(minimumRole);
    }
}
