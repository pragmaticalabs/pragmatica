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
