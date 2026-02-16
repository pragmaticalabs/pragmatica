package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Result;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.pragmatica.aether.http.handler.security.Principal.PrincipalType;
import static org.pragmatica.lang.Result.success;

/// Security context carrying authentication and authorization information.
///
/// Created during security validation and passed to slice handlers
/// for access control decisions.
///
/// @param principal the authenticated identity
/// @param roles     assigned roles/permissions
/// @param claims    additional metadata (e.g., JWT claims)
public record SecurityContext(Principal principal,
                              Set<Role> roles,
                              Map<String, String> claims) {
    private static final SecurityContext ANONYMOUS_CONTEXT = securityContext(Principal.ANONYMOUS, Set.of(), Map.of());

    /// Canonical constructor with validation.
    public SecurityContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(claims, "claims");
    }

    /// Create anonymous (unauthenticated) context.
    public static SecurityContext securityContext() {
        return ANONYMOUS_CONTEXT;
    }

    /// Create context for API key authentication.
    ///
    /// @param keyName the API key identifier
    /// @return Result containing security context with SERVICE role or validation error
    public static Result<SecurityContext> securityContext(String keyName) {
        return Principal.principal(keyName, PrincipalType.API_KEY)
                        .map(p -> securityContext(p,
                                                  Set.of(Role.SERVICE),
                                                  Map.of()));
    }

    /// Create context for API key authentication with custom roles.
    ///
    /// @param keyName the API key identifier
    /// @param roles   assigned roles
    /// @return Result containing security context with specified roles or validation error
    public static Result<SecurityContext> securityContext(String keyName, Set<Role> roles) {
        return Principal.principal(keyName, PrincipalType.API_KEY)
                        .map(p -> securityContext(p,
                                                  roles,
                                                  Map.of()));
    }

    /// Create context for bearer token (JWT) authentication.
    ///
    /// @param subject user subject from token
    /// @param roles   assigned roles from token
    /// @param claims  additional claims from token
    /// @return Result containing security context for authenticated user or validation error
    public static Result<SecurityContext> securityContext(String subject, Set<Role> roles, Map<String, String> claims) {
        return Principal.principal(subject, PrincipalType.USER)
                        .map(p -> securityContext(p, roles, claims));
    }

    /// Create context with all parameters (pre-validated principal).
    public static SecurityContext securityContext(Principal principal, Set<Role> roles, Map<String, String> claims) {
        return Result.all(success(principal),
                          success(roles),
                          success(claims))
                     .map(SecurityContext::new)
                     .unwrap();
    }

    /// Check if authenticated (not anonymous).
    public boolean isAuthenticated() {
        return ! principal.isAnonymous();
    }

    /// Check if context has specific role.
    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /// Check if context has role by name.
    /// Returns false if role name is invalid.
    public boolean hasRole(String roleName) {
        return Role.role(roleName)
                   .map(roles::contains)
                   .or(false);
    }

    /// Check if context has any of the specified roles.
    public boolean hasAnyRole(Set<Role> requiredRoles) {
        return requiredRoles.stream()
                            .anyMatch(roles::contains);
    }

    /// Get claim value by key.
    public String claim(String key) {
        return claims.get(key);
    }
}
