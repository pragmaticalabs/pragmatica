// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;


public sealed interface SecurityError extends Cause, HttpStatusAware {
    /// Default 401 — most security failures are authentication-related. Authorization
    /// failures (AccessDenied, InsufficientRole) override to 403; server-side JWKS
    /// fetch failures override to 500.
    @Override
    default HttpStatus httpStatus() {
        return HttpStatus.UNAUTHORIZED;
    }

    SecurityError MISSING_API_KEY = new MissingCredentials("X-API-Key header required");
    SecurityError INVALID_API_KEY = new InvalidCredentials("Invalid API key");
    SecurityError MISSING_BEARER_TOKEN = new MissingCredentials("Authorization Bearer token required");
    SecurityError MALFORMED_TOKEN = new InvalidCredentials("Malformed JWT token");

    record MissingCredentials(String message) implements SecurityError {}

    record InvalidCredentials(String message) implements SecurityError {}

    record AccessDenied(String message) implements SecurityError {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.FORBIDDEN;
        }
    }

    record TokenExpired(String message) implements SecurityError {}

    record SignatureInvalid(String message) implements SecurityError {}

    record IssuerMismatch(String message) implements SecurityError {}

    record AudienceMismatch(String message) implements SecurityError {}

    record KeyNotFound(String message) implements SecurityError {}

    record JwksFetchFailed(String message) implements SecurityError {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    SecurityError NO_VALIDATOR_CONFIGURED = new MissingCredentials("Route requires authentication but no security mode is configured");

    /// Reached only if [org.pragmatica.aether.http.handler.security.SecurityPolicy.Unspecified]
    /// (or the dead placeholder permitted type used to force switch exhaustiveness) somehow reaches
    /// a [SecurityValidator] directly, bypassing the resolution `AppHttpServer#isExplicitPolicy`
    /// performs before dispatch. Should never happen in production; denies rather than grants as
    /// defense in depth (#763/#772 review).
    SecurityError UNRESOLVED_POLICY = new MissingCredentials("Security policy was not resolved to a concrete value before reaching the validator");

    record InsufficientRole(String message) implements SecurityError {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.FORBIDDEN;
        }
    }
}
