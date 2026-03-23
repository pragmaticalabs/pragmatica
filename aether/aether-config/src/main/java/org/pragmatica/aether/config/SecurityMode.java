package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;

/// Security mode for app HTTP endpoints.
///
/// Controls how incoming requests are authenticated:
/// - NONE: no authentication (default, backward compatible)
/// - API_KEY: reuse management API key authentication via X-API-Key header
/// - JWT: reserved for future token-based authentication (Layer 4)
public enum SecurityMode {
    NONE,
    API_KEY,
    JWT;

    /// Parse security mode from TOML string value.
    ///
    /// @param value the string value (e.g. "none", "api-key", "jwt")
    /// @return Option containing the parsed SecurityMode, or empty for unrecognized values
    public static Option<SecurityMode> securityMode(String value) {
        return Option.option(value)
                     .map(String::trim)
                     .map(String::toLowerCase)
                     .flatMap(SecurityMode::fromNormalized);
    }

    private static Option<SecurityMode> fromNormalized(String normalized) {
        return switch (normalized) {
            case "none" -> Option.some(NONE);
            case "api-key", "api_key", "apikey" -> Option.some(API_KEY);
            case "jwt" -> Option.some(JWT);
            default -> Option.empty();
        };
    }
}
