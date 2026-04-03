package org.pragmatica.aether.http.security;

import org.pragmatica.lang.Cause;


/// Security-related error types.
///
/// Sealed interface for authentication and authorization failures.
public sealed interface SecurityError extends Cause {
    SecurityError MISSING_API_KEY = new MissingCredentials("X-API-Key header required");

    SecurityError INVALID_API_KEY = new InvalidCredentials("Invalid API key");

    SecurityError MISSING_BEARER_TOKEN = new MissingCredentials("Authorization Bearer token required");

    SecurityError MALFORMED_TOKEN = new InvalidCredentials("Malformed JWT token");

    record MissingCredentials(String message) implements SecurityError{}

    record InvalidCredentials(String message) implements SecurityError{}

    record AccessDenied(String message) implements SecurityError{}

    record TokenExpired(String message) implements SecurityError{}

    record SignatureInvalid(String message) implements SecurityError{}

    record IssuerMismatch(String message) implements SecurityError{}

    record AudienceMismatch(String message) implements SecurityError{}

    record KeyNotFound(String message) implements SecurityError{}

    record JwksFetchFailed(String message) implements SecurityError{}

    SecurityError NO_VALIDATOR_CONFIGURED = new MissingCredentials("Route requires authentication but no security mode is configured");

    record InsufficientRole(String message) implements SecurityError{}
}
