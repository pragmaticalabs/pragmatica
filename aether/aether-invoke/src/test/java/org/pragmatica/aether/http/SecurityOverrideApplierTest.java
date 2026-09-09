// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRouteDefinition;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy;
import org.pragmatica.aether.slice.blueprint.SecurityOverrides;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityOverrideApplierTest {

    private static HttpRouteDefinition route(String method, String path, SecurityPolicy security) {
        return HttpRouteDefinition.httpRouteDefinition(method, path, "org.example:svc:1.0.0", "handle", security);
    }

    @Nested
    class FullPolicyTests {

        @Test
        void applyOverrides_appliesMatch_withFullPolicy() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.publicRoute()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.FULL
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void applyOverrides_allowsWeakening_withFullPolicy() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.authenticated()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "public")),
                SecurityOverridePolicy.FULL
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Public.class);
        }
    }

    @Nested
    class StrengthenOnlyPolicyTests {

        @Test
        void applyOverrides_appliesStronger_withStrengthenOnly() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.publicRoute()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.STRENGTHEN_ONLY
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void applyOverrides_rejectsWeaker_withStrengthenOnly() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.authenticated()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "public")),
                SecurityOverridePolicy.STRENGTHEN_ONLY
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void applyOverrides_appliesEqualStrength_withStrengthenOnly() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.apiKeyRequired()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "bearer_token")),
                SecurityOverridePolicy.STRENGTHEN_ONLY
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.BearerTokenRequired.class);
        }

        @Test
        void applyOverrides_rejectsOverride_onUnspecifiedRoute_withStrengthenOnly() {
            // #772 review item 3: Unspecified.strength() is -1, so comparing the raw declared
            // strength let ANY override (even "public", strength 0) pass as "stronger" on an
            // undeclared route. `public` is the floor under every global mode, so it is the one
            // override that weakens unconditionally and the one this rule refuses. Pin: an override
            // to "public" on an undeclared route under a global API_KEY/JWT mode must not succeed.
            // The two tests below pin the other half — strengthening MUST still apply (#866 F1).
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.unspecified()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "public")),
                SecurityOverridePolicy.STRENGTHEN_ONLY
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Unspecified.class);
        }

        @Test
        void applyOverrides_appliesAuthenticated_onUnspecifiedRoute_withStrengthenOnly() {
            // #866 review F1: refusing EVERY override on an undeclared route was a
            // privilege-escalation regression, not a conservative choice. The refused route stays
            // Unspecified, isExplicitPolicy filters it, and resolveEffectivePolicy falls back to the
            // global policy — so the operator's lock-down silently becomes whatever the global mode
            // happens to be. Strengthening must apply.
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.unspecified()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.STRENGTHEN_ONLY
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void applyOverrides_appliesRoleRequired_onUnspecifiedRoute_withStrengthenOnly() {
            // The scenario F1 names outright: an operator pins an admin route to role:admin under
            // the DEFAULT override policy. Dropping this override leaves the route on the shipped
            // api-key global default, where enforceRoleIfRequired checks nothing because the
            // effective policy is not RoleRequired — any valid API key reaches an admin-only route.
            var routes = List.of(route("GET", "/api/v1/admin/", SecurityPolicy.unspecified()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/admin/*", "role:admin")),
                SecurityOverridePolicy.STRENGTHEN_ONLY
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.RoleRequired.class);
            assertThat(result.getFirst().security().asString()).isEqualTo("ROLE:admin");
        }

        @Test
        void applyOverrides_refusesEveryOverride_onUnusedPlaceholderRoute_withStrengthenOnly() {
            // #866 review G5. unused() is NOT Unspecified, so it never reaches applyToUndeclaredRoute
            // and is judged purely by the strength comparison below. Its strength is Integer.MAX_VALUE
            // precisely so that comparison refuses everything: nothing is >= MAX_VALUE. With the old
            // value of -1 this same override to "public" would have been APPLIED (0 >= -1) — the
            // fail-open side of a guard that exists to prevent exactly that.
            var routes = List.of(route("GET", "/api/v1/urls/", new SecurityPolicy.unused()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "public")),
                SecurityOverridePolicy.STRENGTHEN_ONLY
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.unused.class);
        }
    }

    @Nested
    class NonePolicyTests {

        @Test
        void applyOverrides_rejectsAll_withNonePolicy() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.publicRoute()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.NONE
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Public.class);
        }
    }

    @Nested
    class EmptyOverridesTests {

        @Test
        void applyOverrides_returnsUnchanged_withEmptyOverrides() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.publicRoute()));

            var result = SecurityOverrideApplier.applyOverrides(routes, SecurityOverrides.EMPTY);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Public.class);
        }

        @Test
        void applyOverrides_returnsUnchanged_whenNoMatchingOverride() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.publicRoute()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("POST /api/v2/other/*", "authenticated")),
                SecurityOverridePolicy.FULL
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Public.class);
        }
    }

    @Nested
    class MultiRouteTests {

        @Test
        void applyOverrides_appliesSelectively_toMatchingRoutes() {
            var routes = List.of(
                route("GET", "/api/v1/urls/", SecurityPolicy.publicRoute()),
                route("POST", "/api/v1/urls/", SecurityPolicy.publicRoute()),
                route("GET", "/api/v1/health/", SecurityPolicy.publicRoute())
            );
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.FULL
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).security()).isInstanceOf(SecurityPolicy.Authenticated.class);
            assertThat(result.get(1).security()).isInstanceOf(SecurityPolicy.Public.class);
            assertThat(result.get(2).security()).isInstanceOf(SecurityPolicy.Public.class);
        }
    }

    /// #964, fail closed. `applyWithPolicy` switches on the policy, and before the sentinel existed a
    /// policy decoded from a peer running a newer `SecurityOverridePolicy` could not be represented at
    /// all. Now it can, and what it must mean is REFUSE: an unreadable policy is not authority to
    /// weaken a route's security.
    ///
    /// Asserted against the WEAKENING case specifically, because that is the one where a wrong answer
    /// is a security regression — a `FULL`-shaped fall-through here would silently downgrade an
    /// authenticated route to public on the say-so of a value this node never understood.
    @Nested
    class UnknownPolicyRefusesEveryOverride {

        @Test
        void applyOverrides_refusesWeakening_withUnknownPolicy() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.authenticated()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "public")),
                SecurityOverridePolicy.UNKNOWN
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        /// Refusal is total, not "refuse only downgrades". STRENGTHEN_ONLY would have allowed this one,
        /// so this is what separates UNKNOWN from the nearest legitimate policy rather than merely
        /// showing it is not FULL.
        @Test
        void applyOverrides_refusesStrengthening_withUnknownPolicy() {
            var routes = List.of(route("GET", "/api/v1/urls/", SecurityPolicy.publicRoute()));
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.UNKNOWN
            );

            var result = SecurityOverrideApplier.applyOverrides(routes, overrides);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().security()).isInstanceOf(SecurityPolicy.Public.class);
        }
    }
}
