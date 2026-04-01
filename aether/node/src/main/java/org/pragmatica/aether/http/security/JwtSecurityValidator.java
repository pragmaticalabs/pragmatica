package org.pragmatica.aether.http.security;

import org.pragmatica.aether.config.JwtConfig;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;

/// Validates JWT Bearer tokens against a JWKS endpoint.
///
/// Implements the full JWT validation pipeline:
/// extract token -> decode header/payload -> fetch JWKS -> verify signature -> validate claims -> build SecurityContext
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) class JwtSecurityValidator implements SecurityValidator {
    private static final Logger log = LoggerFactory.getLogger(JwtSecurityValidator.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtConfig config;
    private final JwksKeyStore keyStore;

    JwtSecurityValidator(JwtConfig config) {
        this.config = config;
        this.keyStore = new JwksKeyStore(config.jwksUrl(), config.cacheTtlSeconds());
    }

    /// Package-private constructor for testing with a custom key store.
    JwtSecurityValidator(JwtConfig config, JwksKeyStore keyStore) {
        this.config = config;
        this.keyStore = keyStore;
    }

    @Override public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) {
        return switch (policy) {case SecurityPolicy.Public() -> success(SecurityContext.securityContext());case SecurityPolicy.BearerTokenRequired() -> validateBearerToken(request);case SecurityPolicy.Authenticated() -> validateBearerToken(request);case SecurityPolicy.RoleRequired _ -> validateBearerToken(request);case SecurityPolicy.ApiKeyRequired() -> success(SecurityContext.securityContext());default -> success(SecurityContext.securityContext());};
    }

    private Result<SecurityContext> validateBearerToken(HttpRequestContext request) {
        return extractBearerToken(request.headers()).toResult(SecurityError.MISSING_BEARER_TOKEN)
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
        return Option.option(payload.get("exp")).toResult(new SecurityError.TokenExpired("Missing exp claim"))
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
        return config.issuer().map(expected -> matchClaim(payload, "iss", expected, "Issuer"))
                            .or(success(payload));
    }

    private Result<Map<String, Object>> validateAudience(Map<String, Object> payload) {
        return config.audience().map(expected -> matchAudienceClaim(payload, expected))
                              .or(success(payload));
    }

    private static Result<Map<String, Object>> matchClaim(Map<String, Object> payload,
                                                          String claimName,
                                                          String expected,
                                                          String label) {
        var actual = Option.option(payload.get(claimName)).map(Object::toString)
                                  .or("");
        return expected.equals(actual)
               ? success(payload)
               : new SecurityError.IssuerMismatch(label + " mismatch").result();
    }

    @SuppressWarnings("unchecked")
    private static Result<Map<String, Object>> matchAudienceClaim(Map<String, Object> payload,
                                                                  String expected) {
        return Option.option(payload.get("aud")).toResult(new SecurityError.AudienceMismatch("Missing aud claim"))
                            .flatMap(audValue -> checkAudienceValue(audValue, expected, payload));
    }

    private static Result<Map<String, Object>> checkAudienceValue(Object audValue,
                                                                  String expected,
                                                                  Map<String, Object> payload) {
        if ( audValue instanceof String s) {
        return expected.equals(s)
               ? success(payload)
               : new SecurityError.AudienceMismatch("Audience mismatch").result();}
        if ( audValue instanceof List<?> list) {
            var match = list.stream().anyMatch(v -> expected.equals(String.valueOf(v)));
            return match
                   ? success(payload)
                   : new SecurityError.AudienceMismatch("Audience mismatch").result();
        }
        return new SecurityError.AudienceMismatch("Unexpected aud claim type").result();
    }

    private Result<SecurityContext> buildSecurityContext(Map<String, Object> payload) {
        var subject = Option.option(payload.get("sub")).map(Object::toString)
                                   .or("unknown");
        var roleClaim = config.roleClaim();
        var authRole = extractAuthorizationRole(payload, roleClaim);
        var roles = extractRoles(payload, roleClaim);
        var claims = extractStringClaims(payload);
        return SecurityContext.securityContext(subject, roles, claims)
        .map(ctx -> SecurityContext.securityContext(ctx.principal(), ctx.roles(), ctx.claims(), authRole));
    }

    private static AuthorizationRole extractAuthorizationRole(Map<String, Object> payload, String roleClaim) {
        return Option.option(payload.get(roleClaim)).map(Object::toString)
                            .map(String::toUpperCase)
                            .flatMap(JwtSecurityValidator::parseAuthorizationRole)
                            .or(AuthorizationRole.VIEWER);
    }

    private static Option<AuthorizationRole> parseAuthorizationRole(String value) {
        return switch (value) {case "ADMIN" -> Option.some(AuthorizationRole.ADMIN);case "OPERATOR" -> Option.some(AuthorizationRole.OPERATOR);case "VIEWER" -> Option.some(AuthorizationRole.VIEWER);default -> Option.empty();};
    }

    private static Set<Role> extractRoles(Map<String, Object> payload, String roleClaim) {
        return Option.option(payload.get(roleClaim)).map(Object::toString)
                            .map(JwtSecurityValidator::toRoleSet)
                            .or(Set.of());
    }

    private static Set<Role> toRoleSet(String roleValue) {
        return Set.of(roleValue.split(",")).stream()
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .map(Role::role)
                     .flatMap(r -> r.stream())
                     .collect(Collectors.toSet());
    }

    private static Map<String, String> extractStringClaims(Map<String, Object> payload) {
        return payload.entrySet().stream()
                               .filter(e -> Option.option(e.getValue()).isPresent())
                               .collect(Collectors.toMap(Map.Entry::getKey,
                                                         e -> String.valueOf(e.getValue())));
    }

    private static Option<String> extractBearerToken(Map<String, List<String>> headers) {
        return extractAuthHeaderCaseSensitive(headers).orElse(() -> extractAuthHeaderCaseInsensitive(headers));
    }

    private static Option<String> extractAuthHeaderCaseSensitive(Map<String, List<String>> headers) {
        return Option.option(headers.get("Authorization")).filter(values -> !values.isEmpty())
                            .map(List::getFirst)
                            .filter(v -> v.startsWith(BEARER_PREFIX))
                            .map(v -> v.substring(BEARER_PREFIX.length()).trim());
    }

    private static Option<String> extractAuthHeaderCaseInsensitive(Map<String, List<String>> headers) {
        var value = headers.entrySet().stream()
                                    .filter(e -> "authorization".equalsIgnoreCase(e.getKey()))
                                    .map(Map.Entry::getValue)
                                    .flatMap(values -> Option.option(values).filter(v -> !v.isEmpty())
                                                                    .stream())
                                    .map(List::getFirst)
                                    .filter(v -> v.regionMatches(true,
                                                                 0,
                                                                 BEARER_PREFIX,
                                                                 0,
                                                                 BEARER_PREFIX.length()))
                                    .map(v -> v.substring(BEARER_PREFIX.length()).trim())
                                    .findFirst();
        return Option.from(value);
    }

    private static long tolong(Object value) {
        if ( value instanceof Number n) {
        return n.longValue();}
        return Long.parseLong(String.valueOf(value));
    }
}
