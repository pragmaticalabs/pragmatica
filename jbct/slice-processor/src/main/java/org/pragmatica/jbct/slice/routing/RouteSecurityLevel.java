package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

/// Per-route security level declared in routes.toml [security] section.
public sealed interface RouteSecurityLevel {

    /// No authentication required.
    record Public() implements RouteSecurityLevel {}

    /// Any valid credential (API key or JWT based on server mode).
    record Authenticated() implements RouteSecurityLevel {}

    /// Requires a specific role in SecurityContext.
    record Role(String roleName) implements RouteSecurityLevel {}

    RouteSecurityLevel PUBLIC = new Public();
    RouteSecurityLevel AUTHENTICATED = new Authenticated();

    Cause EMPTY_VALUE = Causes.cause("Empty security value");
    Cause EMPTY_ROLE = Causes.cause("Empty role name");
    Fn1<Cause, String> UNKNOWN_LEVEL = Causes.forOneValue("Unknown security level: %s");

    /// Parse from string: "public", "authenticated", "role:name".
    static Result<RouteSecurityLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_VALUE.result();
        }

        var trimmed = value.trim().toLowerCase();

        return switch (trimmed) {
            case "public" -> Result.success(PUBLIC);
            case "authenticated" -> Result.success(AUTHENTICATED);
            default -> parseRole(trimmed, value);
        };
    }

    /// Strength ordering: PUBLIC(0) < AUTHENTICATED(1) < ROLE(2).
    default int strength() {
        return switch (this) {
            case Public _ -> 0;
            case Authenticated _ -> 1;
            case Role _ -> 2;
        };
    }

    /// Check if this level is at least as strong as another.
    default boolean isAtLeastAsStrongAs(RouteSecurityLevel other) {
        return strength() >= other.strength();
    }

    /// Convert to string for code generation.
    default String toConfigString() {
        return switch (this) {
            case Public _ -> "public";
            case Authenticated _ -> "authenticated";
            case Role(var name) -> "role:" + name;
        };
    }

    private static Result<RouteSecurityLevel> parseRole(String trimmed, String original) {
        if (!trimmed.startsWith("role:")) {
            return UNKNOWN_LEVEL.apply(original).result();
        }

        var role = trimmed.substring(5).trim();

        return role.isEmpty()
               ? EMPTY_ROLE.result()
               : Result.success(new Role(role));
    }
}
