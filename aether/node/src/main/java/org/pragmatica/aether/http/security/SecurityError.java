package org.pragmatica.aether.http.security;

import org.pragmatica.lang.Cause;

/// Security-related error types.
///
/// Sealed interface for authentication and authorization failures.
public sealed interface SecurityError extends Cause {
    /// Common error: missing required credentials.
    SecurityError MISSING_API_KEY = new MissingCredentials("X-API-Key header required");

    /// Common error: invalid credentials provided.
    SecurityError INVALID_API_KEY = new InvalidCredentials("Invalid API key");

    /// Common error: missing Authorization Bearer header.
    SecurityError MISSING_BEARER_TOKEN = new MissingCredentials("Authorization Bearer token required");

    /// Common error: malformed JWT token structure.
    SecurityError MALFORMED_TOKEN = new InvalidCredentials("Malformed JWT token");

    /// Missing required authentication credentials.
    record MissingCredentials(String message) implements SecurityError {}

    /// Invalid or expired credentials.
    record InvalidCredentials(String message) implements SecurityError {}

    /// Authenticated principal lacks sufficient authorization level.
    record AccessDenied(String message) implements SecurityError {}

    /// JWT token has expired.
    record TokenExpired(String message) implements SecurityError {}

    /// JWT signature validation failed.
    record SignatureInvalid(String message) implements SecurityError {}

    /// JWT issuer does not match expected value.
    record IssuerMismatch(String message) implements SecurityError {}

    /// JWT audience does not match expected value.
    record AudienceMismatch(String message) implements SecurityError {}

    /// JWKS key not found for the given key ID.
    record KeyNotFound(String message) implements SecurityError {}

    /// Failed to fetch or parse JWKS from remote endpoint.
    record JwksFetchFailed(String message) implements SecurityError {}

    /// Route requires authentication but no security mode is configured.
    SecurityError NO_VALIDATOR_CONFIGURED = new MissingCredentials("Route requires authentication but no security mode is configured");

    /// Authenticated principal lacks the required role.
    record InsufficientRole(String message) implements SecurityError {}
}
