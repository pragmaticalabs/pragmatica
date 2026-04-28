// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Result;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;


public interface SecurityValidator {
    Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy);

    default boolean hasConfiguredCredentials() {
        return false;
    }

    static SecurityValidator apiKeyValidator(Set<String> validKeys) {
        return new ApiKeySecurityValidator(ApiKeySecurityValidator.fromKeySet(validKeys));
    }

    static SecurityValidator apiKeyValidator(Map<String, ApiKeyEntry> keyEntries) {
        return new ApiKeySecurityValidator(keyEntries);
    }

    static SecurityValidator kvStoreAwareValidator(SecurityValidator configValidator,
                                                   Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        return new KvStoreApiKeyValidator(configValidator, kvStoreSupplier);
    }

    static SecurityValidator jwtValidator(JwtConfig jwtConfig) {
        return new JwtSecurityValidator(jwtConfig);
    }

    static SecurityValidator noOpValidator() {
        var systemContext = SecurityContext.securityContext(Principal.principal("system",
                                                                                Principal.PrincipalType.SERVICE)
        .unwrap(),
                                                            Set.of(Role.ADMIN, Role.SERVICE),
                                                            Map.of());
        return (_, _) -> Result.success(systemContext);
    }
}
