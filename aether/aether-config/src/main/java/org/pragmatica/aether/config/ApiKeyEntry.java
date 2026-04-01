package org.pragmatica.aether.config;

import java.util.Set;

/// Metadata for a configured API key: display name, assigned roles, and authorization level.
///
/// @param name              human-readable name for audit logs
/// @param roles             assigned role names (e.g., "admin", "service")
/// @param authorizationRole hierarchical authorization level (ADMIN, OPERATOR, VIEWER); defaults to ADMIN
public record ApiKeyEntry( String name, Set<String> roles, String authorizationRole) {
    /// Canonical constructor — defensive copy.
    public ApiKeyEntry {
        name = name == null || name.isBlank()
               ? "unnamed"
               : name;
        roles = roles == null || roles.isEmpty()
                ? Set.of("service")
                : Set.copyOf(roles);
        authorizationRole = authorizationRole == null || authorizationRole.isBlank()
                            ? "ADMIN"
                            : authorizationRole.toUpperCase();
    }

    /// Full factory.
    public static ApiKeyEntry apiKeyEntry(String name, Set<String> roles) {
        return new ApiKeyEntry(name, roles, "ADMIN");
    }

    /// Full factory with authorization role.
    public static ApiKeyEntry apiKeyEntry(String name, Set<String> roles, String authorizationRole) {
        return new ApiKeyEntry(name, roles, authorizationRole);
    }

    /// Default entry for backward-compat: hash-derived identifier as name, SERVICE role, ADMIN authorization.
    public static ApiKeyEntry defaultEntry(String keyValue) {
        var hash = Integer.toHexString(keyValue.hashCode());
        var name = "key-" + hash;
        return new ApiKeyEntry(name, Set.of("service"), "ADMIN");
    }
}
