// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityOverridesTest {

    @Nested
    class MatchingTests {

        @Test
        void findMatch_returnsLevel_forExactMatch() {
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/", "authenticated")),
                SecurityOverridePolicy.FULL
            );

            var result = overrides.findMatch("GET", "/api/v1/urls/");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(level -> assertThat(level).isEqualTo("authenticated"));
        }

        @Test
        void findMatch_returnsLevel_forWildcardSuffix() {
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.FULL
            );

            var result = overrides.findMatch("GET", "/api/v1/urls/shortcode/");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(level -> assertThat(level).isEqualTo("authenticated"));
        }

        @Test
        void findMatch_returnsLevel_forMethodWildcard() {
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("* /api/v1/urls/*", "bearer_token")),
                SecurityOverridePolicy.FULL
            );

            var result = overrides.findMatch("POST", "/api/v1/urls/test/");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(level -> assertThat(level).isEqualTo("bearer_token"));
        }

        @Test
        void findMatch_returnsNone_forNoMatch() {
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.FULL
            );

            var result = overrides.findMatch("POST", "/api/v2/other/");

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void findMatch_returnsNone_forMethodMismatch() {
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("GET /api/v1/urls/*", "authenticated")),
                SecurityOverridePolicy.FULL
            );

            var result = overrides.findMatch("POST", "/api/v1/urls/test/");

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void findMatch_returnsLevel_forRoleOverride() {
            var overrides = SecurityOverrides.securityOverrides(
                List.of(SecurityOverrides.Entry.entry("DELETE /api/v1/admin/*", "role:admin")),
                SecurityOverridePolicy.FULL
            );

            var result = overrides.findMatch("DELETE", "/api/v1/admin/users/");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(level -> assertThat(level).isEqualTo("role:admin"));
        }
    }

    /// #1659 (v1670 audit b): overlapping patterns resolve to the MOST SPECIFIC one, whatever order they are listed
    /// in. First-listed-wins let a PUBLIC `/api/*` listed first shadow a `role:admin` `/api/admin/*` -- and the list
    /// order of a TOML table carries no security meaning.
    @Nested
    class SpecificityTests {
        private static final SecurityOverrides.Entry PARENT_PUBLIC = SecurityOverrides.Entry.entry("GET /api/*", "public");
        private static final SecurityOverrides.Entry CHILD_ADMIN = SecurityOverrides.Entry.entry("GET /api/admin/*", "role:admin");

        @Test
        void findMatch_childRoute_resolvesToTheChildPattern_parentListedFirst() {
            var overrides = SecurityOverrides.securityOverrides(List.of(PARENT_PUBLIC, CHILD_ADMIN), SecurityOverridePolicy.FULL);

            assertThat(overrides.findMatch("GET", "/api/admin/").or("none")).isEqualTo("role:admin");
        }

        @Test
        void findMatch_childRoute_resolvesToTheChildPattern_childListedFirst() {
            var overrides = SecurityOverrides.securityOverrides(List.of(CHILD_ADMIN, PARENT_PUBLIC), SecurityOverridePolicy.FULL);

            assertThat(overrides.findMatch("GET", "/api/admin/").or("none")).isEqualTo("role:admin");
        }

        /// CONTROL: the parent pattern still governs the routes the child does not cover, in either order.
        @Test
        void findMatch_parentRoute_resolvesToTheParentPattern_inEitherOrder() {
            assertThat(SecurityOverrides.securityOverrides(List.of(PARENT_PUBLIC, CHILD_ADMIN), SecurityOverridePolicy.FULL)
                                        .findMatch("GET", "/api/orders/")
                                        .or("none")).isEqualTo("public");
            assertThat(SecurityOverrides.securityOverrides(List.of(CHILD_ADMIN, PARENT_PUBLIC), SecurityOverridePolicy.FULL)
                                        .findMatch("GET", "/api/orders/")
                                        .or("none")).isEqualTo("public");
        }

        /// An exact pattern beats a wildcard over the same path, and a named method beats `*`, in either order.
        @Test
        void findMatch_exactBeatsWildcard_andANamedMethodBeatsTheWildcardMethod() {
            var wildcard = SecurityOverrides.Entry.entry("GET /x/*", "role:admin");
            var exact = SecurityOverrides.Entry.entry("GET /x/", "public");
            var anyMethod = SecurityOverrides.Entry.entry("/y/*", "public");
            var getOnly = SecurityOverrides.Entry.entry("GET /y/*", "role:admin");

            assertThat(SecurityOverrides.securityOverrides(List.of(wildcard, exact), SecurityOverridePolicy.FULL)
                                        .findMatch("GET", "/x/")
                                        .or("none")).isEqualTo("public");
            assertThat(SecurityOverrides.securityOverrides(List.of(exact, wildcard), SecurityOverridePolicy.FULL)
                                        .findMatch("GET", "/x/")
                                        .or("none")).isEqualTo("public");
            assertThat(SecurityOverrides.securityOverrides(List.of(anyMethod, getOnly), SecurityOverridePolicy.FULL)
                                        .findMatch("GET", "/y/z/")
                                        .or("none")).isEqualTo("role:admin");
            assertThat(SecurityOverrides.securityOverrides(List.of(getOnly, anyMethod), SecurityOverridePolicy.FULL)
                                        .findMatch("GET", "/y/z/")
                                        .or("none")).isEqualTo("role:admin");
        }
    }

    @Nested
    class FactoryTests {

        @Test
        void fromMap_createsOverrides_fromStringMap() {
            var map = Map.of(
                "GET /api/v1/urls/*", "authenticated",
                "POST /api/v1/admin/*", "role:admin"
            );

            var overrides = SecurityOverrides.fromMap(map, SecurityOverridePolicy.STRENGTHEN_ONLY);

            assertThat(overrides.entries()).hasSize(2);
            assertThat(overrides.policy()).isEqualTo(SecurityOverridePolicy.STRENGTHEN_ONLY);
        }

        @Test
        void empty_hasNoEntries() {
            assertThat(SecurityOverrides.EMPTY.isEmpty()).isTrue();
            assertThat(SecurityOverrides.EMPTY.entries()).isEmpty();
        }
    }

    @Nested
    class PolicyParsingTests {

        @Test
        void fromString_parsesStrengthenOnly() {
            assertThat(SecurityOverridePolicy.fromString("strengthen_only"))
                .isEqualTo(SecurityOverridePolicy.STRENGTHEN_ONLY);
        }

        @Test
        void fromString_parsesFull() {
            assertThat(SecurityOverridePolicy.fromString("full"))
                .isEqualTo(SecurityOverridePolicy.FULL);
        }

        @Test
        void fromString_parsesNone() {
            assertThat(SecurityOverridePolicy.fromString("none"))
                .isEqualTo(SecurityOverridePolicy.NONE);
        }

        @Test
        void fromString_defaultsToStrengthenOnly_forUnknown() {
            assertThat(SecurityOverridePolicy.fromString("unknown"))
                .isEqualTo(SecurityOverridePolicy.STRENGTHEN_ONLY);
        }
    }
}
