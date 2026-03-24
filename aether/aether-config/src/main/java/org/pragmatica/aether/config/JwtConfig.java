package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// JWT authentication configuration for app HTTP endpoints.
///
/// @param jwksUrl   JWKS endpoint URL for public key fetching
/// @param issuer    expected issuer claim (empty = skip validation)
/// @param audience  expected audience claim (empty = skip validation)
/// @param roleClaim JWT claim name for role extraction (default: "role")
/// @param cacheTtlSeconds JWKS cache TTL in seconds (default: 3600 = 1 hour)
/// @param clockSkewSeconds clock skew tolerance in seconds for token expiration (default: 30)
public record JwtConfig(String jwksUrl,
                        Option<String> issuer,
                        Option<String> audience,
                        String roleClaim,
                        long cacheTtlSeconds,
                        long clockSkewSeconds) {
    public static final String DEFAULT_ROLE_CLAIM = "role";
    public static final long DEFAULT_CACHE_TTL_SECONDS = 3600;
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 30;

    /// Factory method following JBCT naming convention.
    public static Result<JwtConfig> jwtConfig(String jwksUrl,
                                              Option<String> issuer,
                                              Option<String> audience,
                                              String roleClaim,
                                              long cacheTtlSeconds) {
        return jwtConfig(jwksUrl, issuer, audience, roleClaim, cacheTtlSeconds, DEFAULT_CLOCK_SKEW_SECONDS);
    }

    /// Factory method with clock skew.
    public static Result<JwtConfig> jwtConfig(String jwksUrl,
                                              Option<String> issuer,
                                              Option<String> audience,
                                              String roleClaim,
                                              long cacheTtlSeconds,
                                              long clockSkewSeconds) {
        return success(new JwtConfig(jwksUrl,
                                     issuer,
                                     audience,
                                     roleClaim.isBlank()
                                     ? DEFAULT_ROLE_CLAIM
                                     : roleClaim,
                                     cacheTtlSeconds <= 0
                                     ? DEFAULT_CACHE_TTL_SECONDS
                                     : cacheTtlSeconds,
                                     clockSkewSeconds < 0
                                     ? DEFAULT_CLOCK_SKEW_SECONDS
                                     : clockSkewSeconds));
    }

    /// Convenience factory with defaults.
    public static Result<JwtConfig> jwtConfig(String jwksUrl) {
        return jwtConfig(jwksUrl,
                         Option.empty(),
                         Option.empty(),
                         DEFAULT_ROLE_CLAIM,
                         DEFAULT_CACHE_TTL_SECONDS,
                         DEFAULT_CLOCK_SKEW_SECONDS);
    }

    /// Convenience factory with issuer and audience.
    public static Result<JwtConfig> jwtConfig(String jwksUrl, String issuer, String audience) {
        return jwtConfig(jwksUrl,
                         option(issuer),
                         option(audience),
                         DEFAULT_ROLE_CLAIM,
                         DEFAULT_CACHE_TTL_SECONDS,
                         DEFAULT_CLOCK_SKEW_SECONDS);
    }
}
