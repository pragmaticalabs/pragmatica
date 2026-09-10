// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler.security;

import org.pragmatica.http.routing.security.Access;
import org.pragmatica.http.routing.security.RequestSecurityContext;
import org.pragmatica.http.routing.security.RouteSecurityPolicy;


@SuppressWarnings("JBCT-NAM-01")
public sealed interface SecurityPolicy extends RouteSecurityPolicy {
    System.Logger log = System.getLogger(SecurityPolicy.class.getName());

    @SuppressWarnings("JBCT-NAM-01")
    record Public() implements SecurityPolicy {
        private static final Public INSTANCE = new Public();

        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return Access.ALLOW;
        }
    }

    record Authenticated() implements SecurityPolicy {
        private static final Authenticated INSTANCE = new Authenticated();

        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkAuthenticated(context);
        }
    }

    record ApiKeyRequired() implements SecurityPolicy {
        private static final ApiKeyRequired INSTANCE = new ApiKeyRequired();

        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkApiKey(context);
        }
    }

    record BearerTokenRequired() implements SecurityPolicy {
        private static final BearerTokenRequired INSTANCE = new BearerTokenRequired();

        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkBearerToken(context);
        }
    }

    record RoleRequired(String roleName) implements SecurityPolicy {
        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return checkRole(context, roleName);
        }
    }

    @SuppressWarnings("unused")
    record unused() implements SecurityPolicy {
        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return Access.DENY;
        }
    }

    /// Codegen-only sentinel: no route declared a security level (routes.toml has no `[security]`
    /// section, or a slice-authored route never called `.withSecurity(...)`). Never served: resolved
    /// to a concrete policy by [org.pragmatica.aether.http.AppHttpServer#isExplicitPolicy] before a
    /// request reaches a [org.pragmatica.aether.http.security.SecurityValidator], both of which refuse
    /// `Unspecified` via an explicit case rather than a `default` arm (#772 review). `canAccess` denies
    /// to honor the [RouteSecurityPolicy] contract for any caller outside that pipeline — it has zero
    /// callers in aether's own request path today, so it is not itself a wired backstop.
    record Unspecified() implements SecurityPolicy {
        private static final Unspecified INSTANCE = new Unspecified();

        @Override
        public <T extends RequestSecurityContext> Access canAccess(T context) {
            return Access.DENY;
        }
    }

    static SecurityPolicy publicRoute() {
        return Public.INSTANCE;
    }

    static SecurityPolicy unspecified() {
        return Unspecified.INSTANCE;
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

    static SecurityPolicy fromString(String value) {
        return switch (value) {
            case "PUBLIC" -> publicRoute();
            case "AUTHENTICATED" -> authenticated();
            case "API_KEY" -> apiKeyRequired();
            case "BEARER_TOKEN" -> bearerTokenRequired();
            case "UNSPECIFIED" -> unspecified();
            default -> parseRoleOrDefault(value);
        };
    }

    /// Exhaustive over the sealed hierarchy on purpose: no `default` arm. A `default` here would
    /// silently persist a newly added policy state as `"API_KEY"` and score it `20` (#866 review F3),
    /// fabricating a wire value and a strength for a state nobody taught these switches about --
    /// the exact fail-soft shape removed from `ApiKeySecurityValidator`/`JwtSecurityValidator`.
    /// Adding a state must be a COMPILE error here, as it already is there.
    ///
    /// `unused()` is UNCONSTRUCTED, not unconstructable -- an earlier revision of this comment said
    /// the latter and it was false (#866 review G5). `record unused()` at :61 is an implicitly-public
    /// nested record of a public interface, so `new SecurityPolicy.unused()` compiles from anywhere
    /// on the module path; a record's canonical constructor must be at least as accessible as the
    /// record, and Java gives interface member types implicit `public`, so it cannot be sealed off.
    /// It simply has no construction site TODAY, and a comment asserting otherwise would be greppable
    /// as a guarantee long after someone adds the first one.
    ///
    /// All three of its arms are therefore chosen to fail CLOSED if it is ever constructed, rather
    /// than to mirror `Unspecified`:
    ///
    ///   - `canAccess()` returns `Access.DENY`. It used to declare NOTHING and inherit
    ///     [RouteSecurityPolicy]'s ALLOW default -- so the one member of this hierarchy documented
    ///     as failing closed everywhere was, on the security interface's own method, the single
    ///     implementor in the codebase that answered ALLOW (#876). That default is now removed, so
    ///     this arm is a compile requirement rather than a thing to remember.
    ///
    ///   - `strength()` returns `Integer.MAX_VALUE`, so `SecurityOverrideApplier.applyIfStronger` (the
    ///     only consumer of `strength()`) refuses EVERY override on such a route. The previous value
    ///     `- 1` was the fail-OPEN side: `unused()` is not `Unspecified`, so it skips the undeclared-
    ///     route guard entirely and `0 >= - 1` would have let an override to `public` through.
    ///
    ///     `MAX_VALUE` is asymmetric across the comparison's two positions -- maximally RESTRICTIVE as
    ///     the route's policy, maximally PERMISSIVE if it were ever the incoming override. The second
    ///     position is unreachable, checked rather than assumed: `newPolicy` has exactly one producer,
    ///     `fromBlueprintString`, which yields only `Public`/`Authenticated`/`ApiKeyRequired`/
    ///     `BearerTokenRequired`/`RoleRequired` (its `default` arm returns `roleRequired` or
    ///     `apiKeyRequired`). Nor can codegen produce one: `RouteSecurityLevel` is sealed over four
    ///     records with no `unused` member at all, and `RouteSourceGenerator.securityExpression` emits
    ///     only `publicRoute()`/`authenticated()`/`roleRequired()`/`unspecified()`.
    ///   - `asString()` returns `"UNUSED"`, which `fromString` deliberately does not recognize, so a
    ///     node reading it falls to `parseRoleOrDefault` -> `apiKeyRequired()` and logs a warning
    ///     naming the value. That is stricter than `"UNSPECIFIED"`, which would resolve to the global
    ///     policy and be served openly under `security_mode = "none"`.
    default String asString() {
        return switch (this) {
            case Public() -> "PUBLIC";
            case Authenticated() -> "AUTHENTICATED";
            case ApiKeyRequired() -> "API_KEY";
            case BearerTokenRequired() -> "BEARER_TOKEN";
            case RoleRequired(var name) -> "ROLE:" + name;
            case Unspecified() -> "UNSPECIFIED";
            case unused() -> "UNUSED";
        };
    }

    default int strength() {
        return switch (this) {
            case Public() -> 0;
            case Authenticated() -> 10;
            case ApiKeyRequired() -> 20;
            case BearerTokenRequired() -> 20;
            case RoleRequired(_) -> 30;
            case Unspecified() -> - 1;
            case unused() -> Integer.MAX_VALUE;
        };
    }

    static SecurityPolicy fromBlueprintString(String value) {
        return switch (value.toLowerCase()
                            .strip()) {
            case "public" -> publicRoute();
            case "authenticated" -> authenticated();
            case "api_key" -> apiKeyRequired();
            case "bearer_token" -> bearerTokenRequired();
            default -> parseBlueprintRoleOrDefault(value);
        };
    }

    private static <T extends RequestSecurityContext> Access checkAuthenticated(T context) {
        if (context instanceof SecurityContext sc) {
            return sc.isAuthenticated()
                   ? Access.ALLOW
                   : Access.DENY;
        }

        return Access.DENY;
    }

    private static <T extends RequestSecurityContext> Access checkApiKey(T context) {
        if (context instanceof SecurityContext sc) {
            return sc.isAuthenticated() && sc.principal()
                                             .isApiKey()
                   ? Access.ALLOW
                   : Access.DENY;
        }

        return Access.DENY;
    }

    private static <T extends RequestSecurityContext> Access checkBearerToken(T context) {
        if (context instanceof SecurityContext sc) {
            return sc.isAuthenticated() && sc.principal()
                                             .isUser()
                   ? Access.ALLOW
                   : Access.DENY;
        }

        return Access.DENY;
    }

    private static <T extends RequestSecurityContext> Access checkRole(T context, String roleName) {
        if (context instanceof SecurityContext sc) {
            return sc.isAuthenticated() && sc.hasRole(roleName)
                   ? Access.ALLOW
                   : Access.DENY;
        }

        return Access.DENY;
    }

    private static SecurityPolicy parseRoleOrDefault(String value) {
        if (value.startsWith("ROLE:")) {
            return roleRequired(value.substring(5));
        }

        log.log(System.Logger.Level.WARNING, "Unrecognized security policy ''{0}'', defaulting to API_KEY", value);

        return apiKeyRequired();
    }

    private static SecurityPolicy parseBlueprintRoleOrDefault(String value) {
        var stripped = value.strip().toLowerCase();

        if (stripped.startsWith("role:")) {
            return roleRequired(value.strip().substring(5));
        }

        log.log(System.Logger.Level.WARNING,
                "Unrecognized blueprint security policy ''{0}'', defaulting to API_KEY",
                value);

        return apiKeyRequired();
    }
}
