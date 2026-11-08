// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
class JwtSecurityValidator implements HttpAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(JwtSecurityValidator.class);

    private final JwtConfig config;
    private final JwksKeyStore keyStore;

    JwtSecurityValidator(JwtConfig config) {
        this.config = config;
        this.keyStore = new JwksKeyStore(config.jwksUrl(), config.cacheTtlSeconds());
    }

    JwtSecurityValidator(JwtConfig config, JwksKeyStore keyStore) {
        this.config = config;
        this.keyStore = keyStore;
    }

    @Override
    public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> close() {
        return org.pragmatica.lang.Promise.lift(org.pragmatica.lang.utils.Causes::fromThrowable,
                                                () -> {
                                                    keyStore.close();

                                                    return org.pragmatica.lang.Unit.unit();
                                                });
    }

    @Override
    public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) {
        return switch (policy) {
            case SecurityPolicy.Public() -> success(SecurityContext.securityContext());
            case SecurityPolicy.BearerTokenRequired() -> validateBearerToken(request);
            case SecurityPolicy.Authenticated() -> validateBearerToken(request);
            case SecurityPolicy.RoleRequired _ -> validateBearerToken(request);
            case SecurityPolicy.ApiKeyRequired() -> SecurityError.UNENFORCEABLE_POLICY.result();
            case SecurityPolicy.Unspecified() -> SecurityError.UNRESOLVED_POLICY.result();
            case SecurityPolicy.unused() -> SecurityError.UNRESOLVED_POLICY.result();
        };
    }

    private Result<SecurityContext> validateBearerToken(HttpRequestContext request) {
        return extractToken(request.headers()).toResult(SecurityError.MISSING_BEARER_TOKEN)
                           .flatMap(this::validateToken);
    }

    private Result<SecurityContext> validateToken(String token) {
        return JwtTokenParser.parseToken(token).flatMap(this::verifyAndBuildContext);
    }

    private Result<SecurityContext> verifyAndBuildContext(JwtTokenParser.ParsedJwt jwt) {
        return verifySignature(jwt).flatMap(verified -> validateClaims(verified.payload()))
                              .flatMap(payload -> buildSecurityContext(payload));
    }

    private Result<JwtTokenParser.ParsedJwt> verifySignature(JwtTokenParser.ParsedJwt jwt) {
        return keyStore.findKey(jwt.header().kid(),
                                jwt.header().alg())
                       .flatMap(key -> JwtSignatureVerifier.verify(jwt, key));
    }

    private Result<Map<String, Object>> validateClaims(Map<String, Object> payload) {
        return validateExpiration(payload).flatMap(this::validateIssuer)
                                 .flatMap(this::validateAudience);
    }

    private Result<Map<String, Object>> validateExpiration(Map<String, Object> payload) {
        return Option.option(payload.get("exp"))
                     .toResult(new SecurityError.TokenExpired("Missing exp claim"))
                     .flatMap(exp -> checkNotExpired(exp, payload));
    }

    private Result<Map<String, Object>> checkNotExpired(Object exp, Map<String, Object> payload) {
        var expSeconds = tolong(exp);
        var now = System.currentTimeMillis() / 1000;
        var skew = config.clockSkewSeconds();

        return expSeconds + skew > now
               ? success(payload)
               : new SecurityError.TokenExpired("Token expired").result();
    }

    private Result<Map<String, Object>> validateIssuer(Map<String, Object> payload) {
        return config.issuer()
                     .map(expected -> matchClaim(payload, "iss", expected, "Issuer"))
                     .or(success(payload));
    }

    private Result<Map<String, Object>> validateAudience(Map<String, Object> payload) {
        return config.audience()
                     .map(expected -> matchAudienceClaim(payload, expected))
                     .or(success(payload));
    }

    private static Result<Map<String, Object>> matchClaim(Map<String, Object> payload,
                                                          String claimName,
                                                          String expected,
                                                          String label) {
        var actual = Option.option(payload.get(claimName)).map(Object::toString).or("");

        return expected.equals(actual)
               ? success(payload)
               : new SecurityError.IssuerMismatch(label + " mismatch").result();
    }

    @SuppressWarnings("unchecked")
    private static Result<Map<String, Object>> matchAudienceClaim(Map<String, Object> payload, String expected) {
        return Option.option(payload.get("aud"))
                     .toResult(new SecurityError.AudienceMismatch("Missing aud claim"))
                     .flatMap(audValue -> checkAudienceValue(audValue, expected, payload));
    }

    private static Result<Map<String, Object>> checkAudienceValue(Object audValue,
                                                                  String expected,
                                                                  Map<String, Object> payload) {
        if (audValue instanceof String s) {
            return expected.equals(s)
                   ? success(payload)
                   : new SecurityError.AudienceMismatch("Audience mismatch").result();
        }

        if (audValue instanceof List<?> list) {
            var match = list.stream().anyMatch(v -> expected.equals(String.valueOf(v)));

            return match
                   ? success(payload)
                   : new SecurityError.AudienceMismatch("Audience mismatch").result();
        }

        return new SecurityError.AudienceMismatch("Unexpected aud claim type").result();
    }

    private Result<SecurityContext> buildSecurityContext(Map<String, Object> payload) {
        var subject = Option.option(payload.get("sub")).map(Object::toString).or("unknown");
        var roleClaim = config.roleClaim();
        var authRole = extractAuthorizationRole(payload, roleClaim);
        var roles = extractRoles(payload, roleClaim);
        var claims = extractStringClaims(payload);

        return SecurityContext.securityContext(subject, roles, claims).map(ctx -> SecurityContext.securityContext(ctx.principal(),
                                                                                                                  ctx.roles(),
                                                                                                                  ctx.claims(),
                                                                                                                  authRole));
    }

    private static AuthorizationRole extractAuthorizationRole(Map<String, Object> payload, String roleClaim) {
        return Option.option(payload.get(roleClaim))
                     .map(Object::toString)
                     .map(String::toUpperCase)
                     .flatMap(JwtSecurityValidator::parseAuthorizationRole)
                     .or(AuthorizationRole.VIEWER);
    }

    private static Option<AuthorizationRole> parseAuthorizationRole(String value) {
        return switch (value) {
            case "ADMIN" -> Option.some(AuthorizationRole.ADMIN);
            case "OPERATOR" -> Option.some(AuthorizationRole.OPERATOR);
            case "VIEWER" -> Option.some(AuthorizationRole.VIEWER);
            default -> Option.empty();
        };
    }

    private static Set<Role> extractRoles(Map<String, Object> payload, String roleClaim) {
        return Option.option(payload.get(roleClaim))
                     .map(Object::toString)
                     .map(JwtSecurityValidator::toRoleSet)
                     .or(Set.of());
    }

    private static Set<Role> toRoleSet(String roleValue) {
        return Set.of(roleValue.split(","))
                  .stream()
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .map(Role::role)
                  .flatMap(r -> r.stream())
                  .collect(Collectors.toSet());
    }

    private static Map<String, String> extractStringClaims(Map<String, Object> payload) {
        return payload.entrySet()
                      .stream()
                      .filter(e -> Option.option(e.getValue()).isPresent())
                      .collect(Collectors.toMap(Map.Entry::getKey,
                                                e -> String.valueOf(e.getValue())));
    }

    /// #2156: the token behind an `Authorization` value whose scheme is one of the configured `auth_schemes` (default
    /// `Bearer`), matched case-insensitively per RFC 7235 section 2.1. The exactly-named header is consulted first,
    /// then any header whose name matches `authorization` case-insensitively. A value naming any other scheme yields
    /// nothing, which the caller answers as a missing credential (401).
    private Option<String> extractToken(Map<String, List<String>> headers) {
        return Option.option(headers.get("Authorization"))
                     .flatMap(JwtSecurityValidator::firstValue)
                     .flatMap(this::tokenFor)
                     .orElse(() -> extractTokenCaseInsensitive(headers));
    }

    private Option<String> extractTokenCaseInsensitive(Map<String, List<String>> headers) {
        var value = headers.entrySet()
                           .stream()
                           .filter(e -> "authorization".equalsIgnoreCase(e.getKey()))
                           .map(Map.Entry::getValue)
                           .flatMap(values -> Option.option(values)
                                                    .flatMap(JwtSecurityValidator::firstValue)
                                                    .flatMap(this::tokenFor)
                                                    .stream())
                           .findFirst();

        return Option.from(value);
    }

    /// `<scheme> <token>`: the token when the scheme is configured, else nothing.
    private Option<String> tokenFor(String headerValue) {
        var separator = headerValue.indexOf(' ');

        return Option.some(separator)
                     .filter(index -> index > 0)
                     .filter(index -> acceptsScheme(headerValue.substring(0, index)))
                     .map(index -> headerValue.substring(index + 1)
                                              .trim());
    }

    private boolean acceptsScheme(String scheme) {
        return config.authSchemes()
                     .stream()
                     .anyMatch(scheme::equalsIgnoreCase);
    }

    /// Only the FIRST value of a header counts: a later value must never be promoted to the credential.
    private static Option<String> firstValue(List<String> values) {
        return Option.from(values.stream().findFirst());
    }

    private static long tolong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }

        return Long.parseLong(String.valueOf(value));
    }
}
