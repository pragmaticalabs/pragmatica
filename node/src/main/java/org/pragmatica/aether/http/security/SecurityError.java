package org.pragmatica.aether.http.security;

import org.pragmatica.lang.Cause;

/**
 * Security-related error types.
 * <p>
 * Sealed interface for authentication and authorization failures.
 */
public sealed interface SecurityError extends Cause {
    /**
     * Common error: missing required credentials.
     */
    SecurityError MISSING_API_KEY = new MissingCredentials("X-API-Key header required");

    /**
     * Common error: invalid credentials provided.
     */
    SecurityError INVALID_API_KEY = new InvalidCredentials("Invalid API key");

    /**
     * Missing required authentication credentials.
     */
    record MissingCredentials(String message) implements SecurityError {}

    /**
     * Invalid or expired credentials.
     */
    record InvalidCredentials(String message) implements SecurityError {}
}
