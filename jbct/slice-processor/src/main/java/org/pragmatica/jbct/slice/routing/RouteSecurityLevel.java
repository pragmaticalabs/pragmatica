// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Per-route security level declared in routes.toml [security] section.
public sealed interface RouteSecurityLevel {
    /// No authentication required.
    record Public() implements RouteSecurityLevel {}

    /// Authenticate if a credential is presented (#2946): none is served anonymously, an invalid one is refused
    /// (401), a valid one reaches the slice through its `Principal`/`SecurityContext` parameters.
    record OptionalAuth() implements RouteSecurityLevel {}

    /// Any valid credential (API key or JWT based on server mode).
    record Authenticated() implements RouteSecurityLevel {}

    /// Requires a specific role in SecurityContext.
    record Role(String roleName) implements RouteSecurityLevel {}

    /// No `[security]` section at all — distinct from an explicit `default = "public"`. Resolved to
    /// `SecurityPolicy.unspecified()` by codegen (never a concrete public/authenticated/role literal),
    /// letting the server tell "declared public" from "undeclared" apart at request time (#763).
    record Unspecified() implements RouteSecurityLevel {}

    RouteSecurityLevel PUBLIC = new Public();
    RouteSecurityLevel OPTIONAL = new OptionalAuth();
    RouteSecurityLevel AUTHENTICATED = new Authenticated();
    RouteSecurityLevel UNSPECIFIED = new Unspecified();
    Cause EMPTY_VALUE = Causes.cause("Empty security value");
    Cause EMPTY_ROLE = Causes.cause("Empty role name");
    Fn1<Cause, String> UNKNOWN_LEVEL = Causes.forOneValue("Unknown security level: %s");

    /// Parse from string: "public", "optional", "authenticated", "role:name".
    static Result<RouteSecurityLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_VALUE.result();
        }

        var trimmed = value.trim().toLowerCase();

        return switch (trimmed) {
            case "public" -> Result.success(PUBLIC);
            case "optional" -> Result.success(OPTIONAL);
            case "authenticated" -> Result.success(AUTHENTICATED);
            case "unspecified" -> Result.success(UNSPECIFIED);
            default -> parseRole(trimmed, value);
        };
    }

    /// Strength ordering: UNSPECIFIED(-1) < PUBLIC(0) < OPTIONAL(1) < AUTHENTICATED(2) < ROLE(3).
    default int strength() {
        return switch (this) {
            case Public _ -> 0;
            case OptionalAuth _ -> 1;
            case Authenticated _ -> 2;
            case Role _ -> 3;
            case Unspecified _ -> -1;
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
            case OptionalAuth _ -> "optional";
            case Authenticated _ -> "authenticated";
            case Role(var name) -> "role:" + name;
            case Unspecified _ -> "unspecified";
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
