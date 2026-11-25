// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Principal.PrincipalType;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Result;


public interface SecurityValidator {
    /// The validated context with the ADMIN grant minted at the credential match, none otherwise. The grant is an
    /// argument from here on: the management plane passes it to the route that acts on it, and it is never stored.
    Result<Authenticated> validate(HttpRequestContext request, SecurityPolicy policy);

    /// The validated context alone, for callers that never act on an ADMIN grant (the app-HTTP and WebSocket planes).
    /// The grant is dropped here, so those planes cannot pass one on.
    default Result<SecurityContext> context(HttpRequestContext request, SecurityPolicy policy) {
        return validate(request, policy).map(Authenticated::context);
    }

    static SecurityValidator apiKeyValidator(Set<String> validKeys) {
        return HttpAuthenticator.apiKeyValidator(validKeys)::authenticate;
    }

    static SecurityValidator apiKeyValidator(Map<String, ApiKeyEntry> keyEntries) {
        return HttpAuthenticator.apiKeyValidator(keyEntries)::authenticate;
    }

    static SecurityValidator kvStoreAwareValidator(SecurityValidator configValidator,
                                                   Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        return new KvStoreApiKeyValidator(configValidator, kvStoreSupplier);
    }

    static SecurityValidator jwtValidator(JwtConfig jwtConfig) {
        return HttpAuthenticator.jwtValidator(jwtConfig)::authenticate;
    }

    /// The Management API's validator and whether its security is enabled, both read from the configured mode ONCE
    /// (#962: `[management] security_mode`, inheriting `[app-http] security_mode` when absent).
    ///
    /// Every [AdminGrant] a management route receives comes from `validate` on the validator returned here, in every
    /// mode. Enabled, it is the credential-matching validator (config keys, then the KV-held keys). Not enabled, it is
    /// the OFF validator: it matches no credential, serves every caller, and mints a grant for the fixed `security-off`
    /// principal, live only inside the request scope the management server opens around the call.
    ///
    /// Main code that calls this over an insecure config inside a request scope holds a live grant on any node; in-process
    /// code is trusted (the accepted residual of the grant design).
    static ManagementSecurity managementValidator(AppHttpConfig config,
                                                  Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        return config.managementSecurityEnabled()
               ? ManagementSecurity.managementSecurity(true,
                                                       kvStoreAwareValidator(apiKeyValidator(config.apiKeys()),
                                                                             kvStoreSupplier))
               : ManagementSecurity.managementSecurity(false, SecurityValidator::offAuthenticated);
    }

    /// The Management API's validator with the flag the server gates on, built together from one mode read.
    record ManagementSecurity(boolean enabled, SecurityValidator validator) {
        public static ManagementSecurity managementSecurity(boolean enabled, SecurityValidator validator) {
            return new ManagementSecurity(enabled, validator);
        }
    }

    /// The OFF validator's answer: the EMPTY context (an anonymous caller, nothing for a route to personalise to) and a
    /// grant minted for the `security-off` principal. `mint` captures the scope bound on this thread per call, so no
    /// grant outlives its request and none is shared between requests.
    private static Result<Authenticated> offAuthenticated(HttpRequestContext request, SecurityPolicy policy) {
        var off = SecurityContext.securityContext(Principal.principal("security-off", PrincipalType.SERVICE).unwrap(),
                                                  Set.of(Role.ADMIN),
                                                  Map.of());

        return Result.success(Authenticated.authenticated(SecurityContext.securityContext(), AdminGrant.mint(off)));
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
    /// It mints NO [AdminGrant]: the ADMIN role it hands out matched no credential, and a grant is proof that one did.
    ///
    /// Do NOT use this where a route's own policy is the only gate — see
    /// [#denyUnlessPublicValidator] for that case. The management plane used this and consequently
    /// granted ADMIN to every caller whenever `[app-http] enabled` was false (the default).
    static SecurityValidator permitAllValidator() {
        var systemContext = SecurityContext.securityContext(Principal.principal("system",
                                                                                Principal.PrincipalType.SERVICE).unwrap(),
                                                            Set.of(Role.ADMIN, Role.SERVICE),
                                                            Map.of());

        return (_, policy) -> Result.success(Authenticated.authenticated(permitAllContext(policy, systemContext)));
    }

    /// #2946: an optional-authentication route is served an EMPTY context under `security_mode = "none"` -- the slice
    /// must not mistake the unconditional system grant for a signed-in caller to personalise for.
    private static SecurityContext permitAllContext(SecurityPolicy policy, SecurityContext systemContext) {
        return switch (policy) {
            case SecurityPolicy.OptionalAuth() -> SecurityContext.securityContext();
            default -> systemContext;
        };
    }

    /// The safe "no credentials are configured" validator: public routes pass with an EMPTY context, as do
    /// optional-authentication routes (#2946: there is no credential store to check a presented credential against, and
    /// the anonymous context grants nothing), everything else is refused with [SecurityError#NO_VALIDATOR_CONFIGURED] (#573).
    ///
    /// Installed by the app-HTTP plane's `SecurityMode.NONE` arm. The management plane does NOT use it: under
    /// `security_mode = "none"` that plane uses the OFF validator of [#managementValidator] and is fully open (#1121).
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
            case SecurityPolicy.Public(), SecurityPolicy.OptionalAuth() -> Result.success(Authenticated.authenticated(SecurityContext.securityContext()));
            default -> SecurityError.NO_VALIDATOR_CONFIGURED.result();
        };
    }
}
