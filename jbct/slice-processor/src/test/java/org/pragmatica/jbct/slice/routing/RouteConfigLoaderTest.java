// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

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

    @Nested
    class InlineTableMediaTypes {

        @Test
        void load_defaultsToJson_whenConsumesAndProducesAbsent() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                create = "POST /"
                getById = ["GET /{id:Long}", "public"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var create = rc.routes().get("create");
                assertThat(create.consumes()).isEqualTo(MediaType.JSON);
                assertThat(create.produces()).isEqualTo(MediaType.JSON);
                var getById = rc.routes().get("getById");
                assertThat(getById.consumes()).isEqualTo(MediaType.JSON);
                assertThat(getById.produces()).isEqualTo(MediaType.JSON);
                assertThat(rc.effectiveSecurity("getById")).isEqualTo(RouteSecurityLevel.PUBLIC);
            });
        }

        @Test
        void load_parsesInlineTable_withProducesAndConsumes() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                export = { route = "POST /export", consumes = "application/json", produces = "text/csv" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var export = rc.routes().get("export");
                assertThat(export.method()).isEqualTo("POST");
                assertThat(export.pathTemplate()).isEqualTo("/export");
                assertThat(export.consumes().category()).isEqualTo("JSON");
                assertThat(export.produces().category()).isEqualTo("TEXT");
                assertThat(export.produces().emitExpression()).isEqualTo("CommonContentType.TEXT_CSV");
                assertThat(export.produces().isJson()).isFalse();
            });
        }

        @Test
        void load_parsesInlineTable_withBinaryProduces() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                download = { route = "GET /download/{id:Long}", produces = "application/octet-stream" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var download = rc.routes().get("download");
                assertThat(download.produces().category()).isEqualTo("BINARY");
                assertThat(download.produces().emitExpression()).isEqualTo("CommonContentType.APPLICATION_OCTET_STREAM");
                assertThat(download.consumes()).isEqualTo(MediaType.JSON);
            });
        }

        @Test
        void load_parsesInlineTable_withTextConsumes() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                upload = { route = "POST /upload", consumes = "text/plain" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var upload = rc.routes().get("upload");
                assertThat(upload.consumes().category()).isEqualTo("TEXT");
                assertThat(upload.produces()).isEqualTo(MediaType.JSON);
            });
        }

        @Test
        void load_parsesInlineTable_withMultipartConsumes() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                form = { route = "POST /form", consumes = "multipart/form-data" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var form = rc.routes().get("form");
                assertThat(form.consumes().category()).isEqualTo("MULTIPART");
            });
        }

        @Test
        void load_appliesInlineSecurityOverride() throws IOException {
            var config = writeConfig("routes.toml", """
                [security]
                default = "authenticated"

                [routes]
                export = { route = "POST /export", produces = "text/csv", security = "public" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.routeSecurity()).containsKey("export");
                assertThat(rc.effectiveSecurity("export")).isEqualTo(RouteSecurityLevel.PUBLIC);
            });
        }

        @Test
        void load_inlineSecurityDefaults_whenSecurityFieldAbsent() throws IOException {
            var config = writeConfig("routes.toml", """
                [security]
                default = "authenticated"

                [routes]
                export = { route = "POST /export", produces = "text/csv" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.routeSecurity()).doesNotContainKey("export");
                assertThat(rc.effectiveSecurity("export")).isEqualTo(RouteSecurityLevel.AUTHENTICATED);
            });
        }

        @Test
        void load_fails_whenInlineTableMissingRouteField() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                broken = { produces = "text/csv" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("must have a 'route' field"));
        }

        @Test
        void load_mixesBareStringArrayAndInlineTableForms() throws IOException {
            var config = writeConfig("routes.toml", """
                [security]
                default = "authenticated"

                [routes]
                bare = "POST /bare"
                arr = ["GET /arr/{id:Long}", "public"]
                inline = { route = "POST /inline", produces = "text/csv", security = "public" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.routes()).hasSize(3);
                assertThat(rc.routes().get("bare").produces()).isEqualTo(MediaType.JSON);
                assertThat(rc.effectiveSecurity("bare")).isEqualTo(RouteSecurityLevel.AUTHENTICATED);
                assertThat(rc.effectiveSecurity("arr")).isEqualTo(RouteSecurityLevel.PUBLIC);
                assertThat(rc.routes().get("inline").produces().category()).isEqualTo("TEXT");
                assertThat(rc.effectiveSecurity("inline")).isEqualTo(RouteSecurityLevel.PUBLIC);
            });
        }
    }
}
