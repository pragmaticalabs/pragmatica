// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Set;


public record ApiKeyEntry(String name, Set<String> roles, String authorizationRole) {
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
