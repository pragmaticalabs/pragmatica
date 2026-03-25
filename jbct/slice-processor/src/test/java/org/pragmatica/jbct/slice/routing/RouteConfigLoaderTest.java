package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RouteConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String filename, String content) throws IOException {
        var path = tempDir.resolve(filename);
        Files.writeString(path, content);
        return path;
    }

    @Nested
    class SecuritySectionParsing {

        @Test
        void load_succeeds_withSecuritySectionAndStringRoutes() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api/v1"

                [security]
                default = "authenticated"
                override_policy = "strengthen_only"

                [routes]
                getUser = "GET /{id}"
                createUser = "POST /"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.prefix()).isEqualTo("/api/v1");
                assertThat(rc.securityDefault()).isEqualTo(RouteSecurityLevel.AUTHENTICATED);
                assertThat(rc.overridePolicy()).isEqualTo(OverridePolicy.STRENGTHEN_ONLY);
                assertThat(rc.routes()).hasSize(2);
                assertThat(rc.routeSecurity()).isEmpty();
            });
        }

        @Test
        void load_succeeds_withArrayRoutes() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api/v1/urls"

                [security]
                default = "authenticated"
                override_policy = "strengthen_only"

                [routes]
                resolve = ["GET /{shortCode}", "public"]
                shorten = "POST /"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.routes()).hasSize(2);
                assertThat(rc.routeSecurity()).containsKey("resolve");
                assertThat(rc.routeSecurity().get("resolve")).isEqualTo(RouteSecurityLevel.PUBLIC);
                assertThat(rc.routeSecurity()).doesNotContainKey("shorten");
                assertThat(rc.effectiveSecurity("resolve")).isEqualTo(RouteSecurityLevel.PUBLIC);
                assertThat(rc.effectiveSecurity("shorten")).isEqualTo(RouteSecurityLevel.AUTHENTICATED);
            });
        }

        @Test
        void load_succeeds_withRoleSecurityLevel() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api/v1"

                [security]
                default = "authenticated"
                override_policy = "none"

                [routes]
                adminReset = ["POST /admin/reset", "role:admin"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.overridePolicy()).isEqualTo(OverridePolicy.NONE);
                var security = rc.routeSecurity().get("adminReset");
                assertThat(security).isInstanceOf(RouteSecurityLevel.Role.class);
                assertThat(((RouteSecurityLevel.Role) security).roleName()).isEqualTo("admin");
            });
        }

        @Test
        void load_succeeds_withFullOverridePolicy() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api"

                [security]
                default = "public"
                override_policy = "full"

                [routes]
                health = "GET /health"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.securityDefault()).isEqualTo(RouteSecurityLevel.PUBLIC);
                assertThat(rc.overridePolicy()).isEqualTo(OverridePolicy.FULL);
            });
        }

        @Test
        void load_usesDefaults_whenSecurityFieldsOmitted() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api"

                [security]

                [routes]
                getUser = "GET /{id}"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.securityDefault()).isEqualTo(RouteSecurityLevel.AUTHENTICATED);
                assertThat(rc.overridePolicy()).isEqualTo(OverridePolicy.STRENGTHEN_ONLY);
            });
        }
    }

    @Nested
    class MissingSecuritySection {

        @Test
        void load_succeeds_withPublicDefaults_whenSecuritySectionMissing() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api/v1"

                [routes]
                getUser = "GET /{id}"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.prefix()).isEqualTo("/api/v1");
                assertThat(rc.securityDefault()).isEqualTo(RouteSecurityLevel.PUBLIC);
                assertThat(rc.overridePolicy()).isEqualTo(OverridePolicy.STRENGTHEN_ONLY);
                assertThat(rc.routes()).hasSize(1);
                assertThat(rc.routeSecurity()).isEmpty();
            });
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void load_fails_forArrayRouteWithUnknownSecurity() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api"

                [security]
                default = "authenticated"

                [routes]
                bad = ["GET /bad", "foobar"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Unknown security level"));
        }

        @Test
        void load_fails_forNonExistentFile() {
            var result = RouteConfigLoader.load(tempDir.resolve("nonexistent.toml"));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("not found"));
        }
    }

    @Nested
    class MergedLoading {

        @Test
        void loadMerged_returnsEmpty_whenNoConfigFiles() {
            var result = RouteConfigLoader.loadMerged(tempDir);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> assertThat(rc.hasRoutes()).isFalse());
        }
    }

    @Nested
    class EffectiveSecurity {

        @Test
        void effectiveSecurity_returnsOverride_whenPresent() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api"

                [security]
                default = "authenticated"
                override_policy = "strengthen_only"

                [routes]
                publicRoute = ["GET /public", "public"]
                protectedRoute = "POST /protected"
                adminRoute = ["DELETE /admin", "role:admin"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.effectiveSecurity("publicRoute")).isEqualTo(RouteSecurityLevel.PUBLIC);
                assertThat(rc.effectiveSecurity("protectedRoute")).isEqualTo(RouteSecurityLevel.AUTHENTICATED);
                assertThat(rc.effectiveSecurity("adminRoute")).isInstanceOf(RouteSecurityLevel.Role.class);
            });
        }
    }
}
