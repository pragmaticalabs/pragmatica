// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface HttpAuthenticator {
    /// Release authentication resources after accepted requests have drained.
    default Promise<Unit> close() {
        return Promise.unitPromise();
    }

    Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy);

    static HttpAuthenticator apiKeyValidator(Set<String> validKeys) {
        return new ApiKeySecurityValidator(ApiKeySecurityValidator.fromKeySet(validKeys));
    }

    static HttpAuthenticator apiKeyValidator(Map<String, ApiKeyEntry> keyEntries) {
        return new ApiKeySecurityValidator(keyEntries);
    }

    static HttpAuthenticator jwtValidator(JwtConfig jwtConfig) {
        return new JwtSecurityValidator(jwtConfig);
    }

    /// The safe "no credentials are configured" validator: public routes pass with an EMPTY context,
    /// everything else is refused with [SecurityError#NO_VALIDATOR_CONFIGURED] (#573).
    /// An empty configuration conveys no authority. This also allows Aether's separate KV-key
    /// adapter to try cluster-managed credentials after the configured authenticator refuses.
    static HttpAuthenticator denyUnlessPublicValidator() {
        return (_, policy) -> switch (policy) {
            case SecurityPolicy.Public() -> Result.success(SecurityContext.securityContext());
            default -> SecurityError.NO_VALIDATOR_CONFIGURED.result();
        };
    }
}
