// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteSecurityLevelTest {

    @Nested
    class Parsing {

        @Test
        void parse_returnsPublic_forPublicString() {
            var result = RouteSecurityLevel.parse("public");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> assertThat(level).isEqualTo(RouteSecurityLevel.PUBLIC));
        }

        @Test
        void parse_returnsAuthenticated_forAuthenticatedString() {
            var result = RouteSecurityLevel.parse("authenticated");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> assertThat(level).isEqualTo(RouteSecurityLevel.AUTHENTICATED));
        }

        @Test
        void parse_returnsRole_forRoleAdmin() {
            var result = RouteSecurityLevel.parse("role:admin");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> {
                assertThat(level).isInstanceOf(RouteSecurityLevel.Role.class);
                assertThat(((RouteSecurityLevel.Role) level).roleName()).isEqualTo("admin");
            });
        }

        @Test
        void parse_returnsRole_forRoleOperator() {
            var result = RouteSecurityLevel.parse("role:operator");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> {
                assertThat(level).isInstanceOf(RouteSecurityLevel.Role.class);
                assertThat(((RouteSecurityLevel.Role) level).roleName()).isEqualTo("operator");
            });
        }

        @Test
        void parse_isCaseInsensitive() {
            var result = RouteSecurityLevel.parse("PUBLIC");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> assertThat(level).isEqualTo(RouteSecurityLevel.PUBLIC));
        }

        @Test
        void parse_returnsUnspecified_forUnspecifiedString() {
            // #772 review item 4: toConfigString() can emit "unspecified" into the generated
            // manifest (ManifestGenerator); parse() must round-trip it back rather than falling
            // through to "Unknown security level".
            var result = RouteSecurityLevel.parse("unspecified");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> assertThat(level).isEqualTo(RouteSecurityLevel.UNSPECIFIED));
        }

        @Test
        void parse_trimsPadding() {
            var result = RouteSecurityLevel.parse("  authenticated  ");

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> assertThat(level).isEqualTo(RouteSecurityLevel.AUTHENTICATED));
        }

        @Test
        void parse_fails_forEmptyString() {
            var result = RouteSecurityLevel.parse("");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Empty"));
        }

        @Test
        void parse_fails_forNullValue() {
            var result = RouteSecurityLevel.parse(null);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Empty"));
        }

        @Test
        void parse_fails_forBlankValue() {
            var result = RouteSecurityLevel.parse("   ");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Empty"));
        }

        @Test
        void parse_fails_forUnknownValue() {
            var result = RouteSecurityLevel.parse("unknown");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Unknown security level"));
        }

        @Test
        void parse_fails_forEmptyRoleName() {
            var result = RouteSecurityLevel.parse("role:");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Empty role name"));
        }
    }

    @Nested
    class StrengthOrdering {

        @Test
        void strength_publicIsLowest() {
            assertThat(RouteSecurityLevel.PUBLIC.strength()).isEqualTo(0);
        }

        @Test
        void strength_authenticatedIsMiddle() {
            assertThat(RouteSecurityLevel.AUTHENTICATED.strength()).isEqualTo(1);
        }

        @Test
        void strength_roleIsHighest() {
            assertThat(new RouteSecurityLevel.Role("admin").strength()).isEqualTo(2);
        }

        @Test
        void isAtLeastAsStrongAs_roleIsStrongerThanAuthenticated() {
            var role = new RouteSecurityLevel.Role("admin");

            assertThat(role.isAtLeastAsStrongAs(RouteSecurityLevel.AUTHENTICATED)).isTrue();
        }

        @Test
        void isAtLeastAsStrongAs_authenticatedIsStrongerThanPublic() {
            assertThat(RouteSecurityLevel.AUTHENTICATED.isAtLeastAsStrongAs(RouteSecurityLevel.PUBLIC)).isTrue();
        }

        @Test
        void isAtLeastAsStrongAs_publicIsNotStrongerThanAuthenticated() {
            assertThat(RouteSecurityLevel.PUBLIC.isAtLeastAsStrongAs(RouteSecurityLevel.AUTHENTICATED)).isFalse();
        }

        @Test
        void isAtLeastAsStrongAs_sameStrengthIsTrue() {
            assertThat(RouteSecurityLevel.AUTHENTICATED.isAtLeastAsStrongAs(RouteSecurityLevel.AUTHENTICATED)).isTrue();
        }
    }

    @Nested
    class ConfigString {

        @Test
        void toConfigString_returnsPublic() {
            assertThat(RouteSecurityLevel.PUBLIC.toConfigString()).isEqualTo("public");
        }

        @Test
        void toConfigString_returnsAuthenticated() {
            assertThat(RouteSecurityLevel.AUTHENTICATED.toConfigString()).isEqualTo("authenticated");
        }

        @Test
        void toConfigString_returnsRoleWithName() {
            assertThat(new RouteSecurityLevel.Role("admin").toConfigString()).isEqualTo("role:admin");
        }

        @Test
        void toConfigString_returnsUnspecified() {
            assertThat(RouteSecurityLevel.UNSPECIFIED.toConfigString()).isEqualTo("unspecified");
        }

        @Test
        void roundTrip_unspecified_parseOfToConfigString() {
            // Pins the #772 review item 4 fix both ways: what codegen emits, parse() accepts back.
            var result = RouteSecurityLevel.parse(RouteSecurityLevel.UNSPECIFIED.toConfigString());

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(level -> assertThat(level).isEqualTo(RouteSecurityLevel.UNSPECIFIED));
        }
    }
}
