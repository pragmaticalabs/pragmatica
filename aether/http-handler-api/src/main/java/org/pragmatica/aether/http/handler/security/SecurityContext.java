package org.pragmatica.aether.http.handler.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Security context carrying authentication and authorization information.
 * <p>
 * Created during security validation and passed to slice handlers
 * for access control decisions.
 *
 * @param principal the authenticated identity
 * @param roles     assigned roles/permissions
 * @param claims    additional metadata (e.g., JWT claims)
 */
public record SecurityContext(Principal principal,
                              Set<Role> roles,
                              Map<String, String> claims) {
    private static final SecurityContext ANONYMOUS_CONTEXT = new SecurityContext(Principal.ANONYMOUS, Set.of(), Map.of());

    /**
     * Canonical constructor with validation.
     */
    public SecurityContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(claims, "claims");
    }

    /**
     * Create anonymous (unauthenticated) context.
     */
    public static SecurityContext anonymous() {
        return ANONYMOUS_CONTEXT;
    }

    /**
     * Create context for API key authentication.
     *
     * @param keyName the API key identifier
     * @return security context with SERVICE role
     */
    public static SecurityContext forApiKey(String keyName) {
        return new SecurityContext(Principal.apiKeyPrincipal(keyName), Set.of(Role.SERVICE), Map.of());
    }

    /**
     * Create context for API key authentication with custom roles.
     *
     * @param keyName the API key identifier
     * @param roles   assigned roles
     * @return security context with specified roles
     */
    public static SecurityContext forApiKey(String keyName, Set<Role> roles) {
        return new SecurityContext(Principal.apiKeyPrincipal(keyName), roles, Map.of());
    }

    /**
     * Create context for bearer token (JWT) authentication.
     *
     * @param subject user subject from token
     * @param roles   assigned roles from token
     * @param claims  additional claims from token
     * @return security context for authenticated user
     */
    public static SecurityContext forBearer(String subject, Set<Role> roles, Map<String, String> claims) {
        return new SecurityContext(Principal.userPrincipal(subject), roles, claims);
    }

    /**
     * Create context with all parameters.
     */
    public static SecurityContext securityContext(Principal principal, Set<Role> roles, Map<String, String> claims) {
        return new SecurityContext(principal, roles, claims);
    }

    /**
     * Check if authenticated (not anonymous).
     */
    public boolean isAuthenticated() {
        return ! principal.isAnonymous();
    }

    /**
     * Check if context has specific role.
     */
    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /**
     * Check if context has role by name.
     */
    public boolean hasRole(String roleName) {
        return roles.contains(Role.role(roleName));
    }

    /**
     * Check if context has any of the specified roles.
     */
    public boolean hasAnyRole(Set<Role> requiredRoles) {
        for (var role : requiredRoles) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get claim value by key.
     */
    public String claim(String key) {
        return claims.get(key);
    }
}
