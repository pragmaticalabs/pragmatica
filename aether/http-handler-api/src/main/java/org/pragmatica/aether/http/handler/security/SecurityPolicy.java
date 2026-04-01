package org.pragmatica.aether.http.handler.security;

import org.pragmatica.http.routing.security.Access;
import org.pragmatica.http.routing.security.RequestSecurityContext;
import org.pragmatica.http.routing.security.RouteSecurityPolicy;

/// Aether-specific security policies implementing RouteSecurityPolicy.
///
/// Sealed interface defining authentication requirements per route.
/// Designed for extensibility - add new variants for JWT, mTLS, etc.
public sealed interface SecurityPolicy extends RouteSecurityPolicy {
    /// Public route - no authentication required.
    @SuppressWarnings({"JBCT-VO-02", "JBCT-NAM-01"}) record Public() implements SecurityPolicy {
        private static final Public INSTANCE = new Public();

        @Override public <T extends RequestSecurityContext> Access canAccess(T context) {
            return Access.ALLOW;
        }
    }

    /// Any valid credential required - runtime decides mechanism.
    @SuppressWarnings("JBCT-VO-02") record Authenticated() implements SecurityPolicy {
        private static final Authenticated INSTANCE = new Authenticated();

        @Override public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkAuthenticated(context);
        }
    }

    /// API key required - must provide valid X-API-Key header.
    @SuppressWarnings("JBCT-VO-02") record ApiKeyRequired() implements SecurityPolicy {
        private static final ApiKeyRequired INSTANCE = new ApiKeyRequired();

        @Override public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkApiKey(context);
        }
    }

    /// Bearer token (JWT) required - must provide valid Authorization: Bearer header.
    @SuppressWarnings("JBCT-VO-02") record BearerTokenRequired() implements SecurityPolicy {
        private static final BearerTokenRequired INSTANCE = new BearerTokenRequired();

        @Override public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkBearerToken(context);
        }
    }

    /// Specific role required.
    record RoleRequired(String roleName) implements SecurityPolicy {
        @Override public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkRole(context, roleName);
        }
    }

    @SuppressWarnings("unused") record unused() implements SecurityPolicy{}

    // Factory methods
    static SecurityPolicy publicRoute() {
        return Public.INSTANCE;
    }

    static SecurityPolicy authenticated() {
        return Authenticated.INSTANCE;
    }

    static SecurityPolicy apiKeyRequired() {
        return ApiKeyRequired.INSTANCE;
    }

    static SecurityPolicy bearerTokenRequired() {
        return BearerTokenRequired.INSTANCE;
    }

    static SecurityPolicy roleRequired(String roleName) {
        return new RoleRequired(roleName);
    }

    /// Parse policy from string representation (for KV-Store serialization).
    static SecurityPolicy fromString(String value) {
        return switch (value) {case "PUBLIC" -> publicRoute();case "AUTHENTICATED" -> authenticated();case "API_KEY" -> apiKeyRequired();case "BEARER_TOKEN" -> bearerTokenRequired();default -> parseRoleOrDefault(value);};
    }

    /// Convert policy to string representation (for KV-Store serialization).
    default String asString() {
        return switch (this) {case Public() -> "PUBLIC";case Authenticated() -> "AUTHENTICATED";case ApiKeyRequired() -> "API_KEY";case BearerTokenRequired() -> "BEARER_TOKEN";case RoleRequired(var name) -> "ROLE:" + name;default -> "PUBLIC";};
    }

    /// Strength level for override policy comparison.
    /// Higher values represent stricter security.
    default int strength() {
        return switch (this) {case Public() -> 0;case Authenticated() -> 10;case ApiKeyRequired() -> 20;case BearerTokenRequired() -> 20;case RoleRequired(_) -> 30;default -> 0;};
    }

    /// Parse policy from blueprint-friendly string representation.
    /// Accepts: "public", "authenticated", "api_key", "bearer_token", "role:admin".
    static SecurityPolicy fromBlueprintString(String value) {
        return switch (value.toLowerCase().strip()) {case "public" -> publicRoute();case "authenticated" -> authenticated();case "api_key" -> apiKeyRequired();case "bearer_token" -> bearerTokenRequired();default -> parseBlueprintRoleOrDefault(value);};
    }

    // --- Private helper methods ---
    private static <T extends RequestSecurityContext> Access checkAuthenticated(T context) {
        if ( context instanceof SecurityContext sc) {
        return sc.isAuthenticated()
               ? Access.ALLOW
               : Access.DENY;}
        return Access.DENY;
    }

    private static <T extends RequestSecurityContext> Access checkApiKey(T context) {
        if ( context instanceof SecurityContext sc) {
        return sc.isAuthenticated() && sc.principal().isApiKey()
               ? Access.ALLOW
               : Access.DENY;}
        return Access.DENY;
    }

    private static <T extends RequestSecurityContext> Access checkBearerToken(T context) {
        if ( context instanceof SecurityContext sc) {
        return sc.isAuthenticated() && sc.principal().isUser()
               ? Access.ALLOW
               : Access.DENY;}
        return Access.DENY;
    }

    private static <T extends RequestSecurityContext> Access checkRole(T context, String roleName) {
        if ( context instanceof SecurityContext sc) {
        return sc.isAuthenticated() && sc.hasRole(roleName)
               ? Access.ALLOW
               : Access.DENY;}
        return Access.DENY;
    }

    private static SecurityPolicy parseRoleOrDefault(String value) {
        if ( value.startsWith("ROLE:")) {
        return roleRequired(value.substring(5));}
        return publicRoute();
    }

    private static SecurityPolicy parseBlueprintRoleOrDefault(String value) {
        var stripped = value.strip().toLowerCase();
        if ( stripped.startsWith("role:")) {
        return roleRequired(value.strip().substring(5));}
        return publicRoute();
    }
}
