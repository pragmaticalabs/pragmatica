package org.pragmatica.aether.http.handler.security;
/**
 * Security policy for HTTP routes.
 * <p>
 * Sealed interface defining authentication requirements per route.
 * Designed for extensibility - add new variants for JWT, mTLS, etc.
 */
public sealed interface RouteSecurityPolicy {
    /**
     * Public route - no authentication required.
     */
    record Public() implements RouteSecurityPolicy {}

    /**
     * API key required - must provide valid X-API-Key header.
     */
    record ApiKeyRequired() implements RouteSecurityPolicy {}

    // Future variants:
    // record BearerRequired(Set<String> roles) implements RouteSecurityPolicy {}
    // record MtlsRequired() implements RouteSecurityPolicy {}
    /**
     * Create public route policy (no auth required).
     */
    static RouteSecurityPolicy publicRoute() {
        return new Public();
    }

    /**
     * Create API key required policy.
     */
    static RouteSecurityPolicy apiKeyRequired() {
        return new ApiKeyRequired();
    }

    /**
     * Parse policy from string representation (for KV-Store serialization).
     */
    static RouteSecurityPolicy fromString(String value) {
        return switch (value) {
            case "PUBLIC" -> publicRoute();
            case "API_KEY" -> apiKeyRequired();
            default -> publicRoute();
        };
    }

    /**
     * Convert policy to string representation (for KV-Store serialization).
     */
    default String asString() {
        return switch (this) {
            case Public() -> "PUBLIC";
            case ApiKeyRequired() -> "API_KEY";
        };
    }
}
