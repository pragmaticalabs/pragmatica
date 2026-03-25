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
}
