// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Set;


/// Metadata for a configured API key: display name, assigned roles, and authorization level.
///
/// @param name              human-readable name for audit logs
/// @param roles             assigned role names (e.g., "admin", "service")
/// @param authorizationRole hierarchical authorization level (ADMIN, OPERATOR, VIEWER); defaults to VIEWER
///                          for secure-by-default — operators must explicitly opt up.
public record ApiKeyEntry(String name, Set<String> roles, String authorizationRole) {
    /// Default authorization role for keys with no explicit role specified.
    /// Read-only access is the secure-by-default choice.
    public static final String DEFAULT_ROLE = "VIEWER";

    public ApiKeyEntry {
        name = name == null || name.isBlank()
              ? "unnamed"
              : name;
        roles = roles == null || roles.isEmpty()
               ? Set.of("service")
               : Set.copyOf(roles);
        authorizationRole = authorizationRole == null || authorizationRole.isBlank()
                           ? DEFAULT_ROLE
                           : authorizationRole.toUpperCase();
    }

    public static ApiKeyEntry apiKeyEntry(String name, Set<String> roles) {
        return new ApiKeyEntry(name, roles, DEFAULT_ROLE);
    }

    public static ApiKeyEntry apiKeyEntry(String name, Set<String> roles, String authorizationRole) {
        return new ApiKeyEntry(name, roles, authorizationRole);
    }

    public static ApiKeyEntry defaultEntry(String keyValue) {
        var hash = Integer.toHexString(keyValue.hashCode());
        var name = "key-" + hash;
        return new ApiKeyEntry(name, Set.of("service"), DEFAULT_ROLE);
    }
}
