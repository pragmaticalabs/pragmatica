package org.pragmatica.aether.config;

import java.util.Set;

/// Metadata for a configured API key: display name and assigned roles.
///
/// @param name  human-readable name for audit logs
/// @param roles assigned role names (e.g., "admin", "service")
public record ApiKeyEntry(String name, Set<String> roles) {
    /// Canonical constructor — defensive copy.
    public ApiKeyEntry {
        name = name == null || name.isBlank()
               ? "unnamed"
               : name;
        roles = roles == null || roles.isEmpty()
                ? Set.of("service")
                : Set.copyOf(roles);
    }

    /// Full factory.
    public static ApiKeyEntry apiKeyEntry(String name, Set<String> roles) {
        return new ApiKeyEntry(name, roles);
    }

    /// Default entry for backward-compat: hash-derived identifier as name, SERVICE role.
    public static ApiKeyEntry defaultEntry(String keyValue) {
        var hash = Integer.toHexString(keyValue.hashCode());
        var name = "key-" + hash;
        return new ApiKeyEntry(name, Set.of("service"));
    }
}
