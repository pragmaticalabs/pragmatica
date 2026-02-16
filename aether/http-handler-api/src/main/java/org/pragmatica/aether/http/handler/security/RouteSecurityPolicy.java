package org.pragmatica.aether.http.handler.security;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Security policy for HTTP routes.
///
/// Sealed interface defining authentication requirements per route.
/// Designed for extensibility - add new variants for JWT, mTLS, etc.
public sealed interface RouteSecurityPolicy {
    /// Public route - no authentication required.
    @SuppressWarnings({"JBCT-VO-02", "JBCT-NAM-01"})
    record Public() implements RouteSecurityPolicy {
        private static final Public INSTANCE = new Public();

        /// Factory for Public policy.
        public static Result<Public> routeSecurityPolicy() {
            return success(INSTANCE);
        }
    }

    /// API key required - must provide valid X-API-Key header.
    @SuppressWarnings("JBCT-VO-02")
    record ApiKeyRequired() implements RouteSecurityPolicy {
        private static final ApiKeyRequired INSTANCE = new ApiKeyRequired();

        /// Factory for ApiKeyRequired policy.
        public static Result<ApiKeyRequired> apiKeyRequired() {
            return success(INSTANCE);
        }
    }

    @SuppressWarnings("unused")
    record unused() implements RouteSecurityPolicy {}

    /// Create public route policy (no auth required).
    static RouteSecurityPolicy publicRoute() {
        return Public.routeSecurityPolicy()
                     .unwrap();
    }

    /// Create API key required policy.
    static RouteSecurityPolicy apiKeyRequired() {
        return ApiKeyRequired.apiKeyRequired()
                             .unwrap();
    }

    /// Parse policy from string representation (for KV-Store serialization).
    static RouteSecurityPolicy fromString(String value) {
        return switch (value) {
            case "PUBLIC" -> publicRoute();
            case "API_KEY" -> apiKeyRequired();
            default -> publicRoute();
        };
    }

    /// Convert policy to string representation (for KV-Store serialization).
    default String asString() {
        return switch (this) {
            case Public() -> "PUBLIC";
            case ApiKeyRequired() -> "API_KEY";
            default -> "PUBLIC";
        };
    }
}
