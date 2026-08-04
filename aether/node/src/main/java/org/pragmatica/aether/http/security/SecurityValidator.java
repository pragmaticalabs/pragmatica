// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

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

    /// GRANTS ADMIN + SERVICE TO EVERY CALLER. Named `permitAll` rather than `noOp` because "no-op"
    /// reads as harmless and this is the opposite: it is an unconditional authorization grant, not an
    /// absence of checking (#573).
    ///
    /// Legitimate only where the surrounding component has ALREADY decided that no authentication is
    /// wanted — Forge's local dev websockets (`ForgeServer`, which passes `authRequired=false`) and
    /// `AppHttpServer`'s `SecurityMode.NONE` arm, which refuses auth-requiring routes at
    /// [AppHttpServer] before the validator is ever consulted.
    ///
    /// Do NOT use this where a route's own policy is the only gate — see
    /// [#denyUnlessPublicValidator] for that case. The management plane used this and consequently
    /// granted ADMIN to every caller whenever `[app-http] enabled` was false (the default).
    static SecurityValidator permitAllValidator() {
        var systemContext = SecurityContext.securityContext(Principal.principal("system",
                                                                                Principal.PrincipalType.SERVICE).unwrap(),
                                                            Set.of(Role.ADMIN, Role.SERVICE),
                                                            Map.of());

        return (_, _) -> Result.success(systemContext);
    }

    /// The safe "no credentials are configured" validator: public routes pass with an EMPTY context,
    /// everything else is refused with [SecurityError#NO_VALIDATOR_CONFIGURED] (#573).
    ///
    /// "No validator configured" must mean NO AUTHORITY, never ALL AUTHORITY. Returning a failure
    /// rather than an empty success is deliberate: [KvStoreApiKeyValidator] consults its delegate
    /// FIRST and returns immediately on success, so an unconditionally-succeeding delegate
    /// short-circuits the cluster's KV-held bootstrap admin keys entirely. Failing here lets that
    /// path run — a cluster with a `BootstrapAdminKeyRegistrar` key still authenticates normally,
    /// and a cluster with no credentials anywhere refuses role-bearing routes instead of granting
    /// them.
    static SecurityValidator denyUnlessPublicValidator() {
        return (_, policy) -> switch (policy) {
            case SecurityPolicy.Public() -> Result.success(SecurityContext.securityContext());
            default -> SecurityError.NO_VALIDATOR_CONFIGURED.result();
        };
    }
}
