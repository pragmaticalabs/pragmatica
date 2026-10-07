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

    /// The route's policy names a credential type this node's `security_mode` does not serve --
    /// `BEARER_TOKEN` under `api-key` mode, or `API_KEY` under `jwt` mode. Reachable only through an
    /// operator security override, since neither value can be declared in `routes.toml`
    /// (`RouteSecurityLevel` parses only public/authenticated/role/unspecified) and
    /// `globalSecurityPolicy()` can only produce the policy matching the mode.
    ///
    /// These two arms previously returned SUCCESS with an anonymous, unauthenticated context and no
    /// credential inspected at all, so an operator override to the "wrong" credential type was a
    /// total authentication bypass rather than the lock-down it reads as (#866 review G1). Denying
    /// is the honest outcome: the node cannot enforce what was asked for, so it refuses rather than
    /// serving the route wide open.
    SecurityError UNENFORCEABLE_POLICY = new MissingCredentials("Route policy requires a credential type this node's security_mode does not serve");

    record InsufficientRole(String message) implements SecurityError {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.FORBIDDEN;
        }
    }
}
