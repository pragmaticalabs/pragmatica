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
        void load_succeeds_withUnspecifiedDefault_whenSecuritySectionMissing() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api/v1"

                [routes]
                getUser = "GET /{id}"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.prefix()).isEqualTo("/api/v1");
                // #763: an absent [security] section is UNSPECIFIED, not PUBLIC — the route must
                // inherit the server's global policy rather than being silently exempted from it.
                assertThat(rc.securityDefault()).isEqualTo(RouteSecurityLevel.UNSPECIFIED);
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

    @Nested
    class Versioning {

        @Test
        void load_parsesApiSectionAndVersionedRoutes() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"
                requireVersionHeader = false

                [v1.routes]
                get = "GET /{id:Long}"
                create = "POST /"

                [v1]
                deprecated = true
                sunset = "2026-12-31"

                [v2.routes]
                get = "GET /{id:Long}"

                [v2]
                defaultIfMissing = true
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.isVersioned()).isTrue();
                assertThat(rc.apiPrefix()).isEqualTo("/api/orders");
                // #198 §6.4: prefix is empty for versioned slices; apiPrefix carries the base and the
                // /v{N}/ segment is composed at registration time, not baked into prefix or path.
                assertThat(rc.prefix()).isEqualTo("");
                assertThat(rc.requireVersionHeader()).isFalse();
                assertThat(rc.versions().keySet()).containsExactly(1, 2);
            });
        }

        @Test
        void load_bindsBindKeyToVersionedMethodAndUnversionedPath() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"

                [v1.routes]
                get = "GET /{id:Long}"
                create = "POST /"

                [v2.routes]
                get = "GET /{id:Long}"
                upsert = "PUT /{id:Long}"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                // D8: bind key `get` under [v1.routes] resolves to method getV1. The path stays
                // un-versioned (#198 §6.4); the version is carried in routeVersions and mounted later.
                assertThat(rc.routes()).containsKeys("getV1", "createV1", "getV2", "upsertV2");
                assertThat(rc.routes().get("getV1").pathTemplate()).isEqualTo("/{id:Long}");
                assertThat(rc.routes().get("createV1").pathTemplate()).isEqualTo("/");
                assertThat(rc.routes().get("getV2").pathTemplate()).isEqualTo("/{id:Long}");
                assertThat(rc.routes().get("upsertV2").pathTemplate()).isEqualTo("/{id:Long}");
                assertThat(rc.routeVersion("getV1")).isEqualTo(1);
                assertThat(rc.routeVersion("createV1")).isEqualTo(1);
                assertThat(rc.routeVersion("getV2")).isEqualTo(2);
                assertThat(rc.routeVersion("upsertV2")).isEqualTo(2);
                assertThat(rc.versions().get(1).bindKeyToMethod()).containsEntry("get", "getV1");
                assertThat(rc.versions().get(2).bindKeyToMethod()).containsEntry("upsert", "upsertV2");
            });
        }

        @Test
        void load_appliesExplicitMethodOverride() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"

                [v1.routes]
                get = { route = "GET /{id:Long}", method = "fetchById" }
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.routes()).containsKey("fetchById");
                assertThat(rc.routes()).doesNotContainKey("getV1");
                assertThat(rc.routes().get("fetchById").pathTemplate()).isEqualTo("/{id:Long}");
                assertThat(rc.routeVersion("fetchById")).isEqualTo(1);
                assertThat(rc.versions().get(1).bindKeyToMethod()).containsEntry("get", "fetchById");
            });
        }

        @Test
        void load_appliesVersionMetadataDefaults() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"

                [v1.routes]
                get = "GET /{id:Long}"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var v1 = rc.versions().get(1);
                assertThat(v1.deprecated()).isFalse();
                assertThat(v1.defaultIfMissing()).isFalse();
                assertThat(v1.sunset().isEmpty()).isTrue();
            });
        }

        @Test
        void load_storesVersionMetadata() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"

                [v1.routes]
                get = "GET /{id:Long}"

                [v1]
                deprecated = true
                sunset = "2026-12-31"

                [v2.routes]
                get = "GET /{id:Long}"

                [v2]
                defaultIfMissing = true
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var v1 = rc.versions().get(1);
                assertThat(v1.deprecated()).isTrue();
                assertThat(v1.sunset().or("")).isEqualTo("2026-12-31");
                assertThat(v1.defaultIfMissing()).isFalse();
                var v2 = rc.versions().get(2);
                assertThat(v2.defaultIfMissing()).isTrue();
                assertThat(v2.deprecated()).isFalse();
            });
        }

        @Test
        void load_keepsFlatRoutesUnversioned_backCompat() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api/v1/test"

                [routes]
                getById = "GET /{id:Long}"
                create = "POST /"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.isVersioned()).isFalse();
                assertThat(rc.versions()).isEmpty();
                assertThat(rc.prefix()).isEqualTo("/api/v1/test");
                assertThat(rc.routes()).containsKeys("getById", "create");
                assertThat(rc.routes().get("getById").pathTemplate()).isEqualTo("/{id:Long}");
            });
        }

        @Test
        void load_fails_whenMixingFlatAndVersionedSchema() throws IOException {
            var config = writeConfig("routes.toml", """
                prefix = "/api"

                [routes]
                getById = "GET /{id:Long}"

                [v1.routes]
                get = "GET /{id:Long}"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("mixes a flat [routes] block"));
        }

        @Test
        void load_fails_whenMultipleVersionsAreDefault() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"

                [v1.routes]
                get = "GET /{id:Long}"

                [v1]
                defaultIfMissing = true

                [v2.routes]
                get = "GET /{id:Long}"

                [v2]
                defaultIfMissing = true
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Multiple versions declare defaultIfMissing"));
        }

        @Test
        void load_fails_whenSunsetIsNotIsoDate() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"

                [v1.routes]
                get = "GET /{id:Long}"

                [v1]
                sunset = "not-a-date"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("invalid 'sunset' value"));
        }

        @Test
        void load_carriesApiPrefixAndVersionForRegistrationTimeMount() throws IOException {
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"

                [v1.routes]
                get = "GET /{id:Long}"
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            // #198 §6.4: the /v{N}/ segment is composed at registration time from apiPrefix + version,
            // not baked into the path. The loader carries the un-versioned path, the version-agnostic
            // apiPrefix, and the per-handler version so codegen can mount {apiPrefix}/v{N}/{path}.
            result.onSuccess(rc -> {
                assertThat(rc.apiPrefix()).isEqualTo("/api/orders");
                assertThat(rc.routes().get("getV1").pathTemplate()).isEqualTo("/{id:Long}");
                assertThat(rc.routeVersion("getV1")).isEqualTo(1);
                assertThat(rc.apiPrefix() + "/v" + rc.routeVersion("getV1") + rc.routes().get("getV1").pathTemplate())
                    .isEqualTo("/api/orders/v1/{id:Long}");
            });
        }

        @Test
        void load_carriesEveryVersionRegistryInput_forGeneratedRegistry() throws IOException {
            // #198 §6.4: the generated {Slice}Routes.versionRegistry() is built from exactly these
            // loader outputs — apiPrefix, requireVersionHeader, the defaultIfMissing version, and
            // per-version deprecated/sunset. This pins that the loader supplies all of them.
            var config = writeConfig("routes.toml", """
                [api]
                prefix = "/api/orders"
                requireVersionHeader = true

                [v1.routes]
                get = "GET /{id:Long}"

                [v1]
                deprecated = true
                sunset = "2026-12-31"

                [v2.routes]
                get = "GET /{id:Long}"

                [v2]
                defaultIfMissing = true
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                assertThat(rc.apiPrefix()).isEqualTo("/api/orders");
                assertThat(rc.requireVersionHeader()).isTrue();
                assertThat(rc.versions().keySet()).containsExactly(1, 2);
                var v1 = rc.versions().get(1);
                assertThat(v1.deprecated()).isTrue();
                assertThat(v1.sunset().or("")).isEqualTo("2026-12-31");
                assertThat(v1.defaultIfMissing()).isFalse();
                var v2 = rc.versions().get(2);
                assertThat(v2.deprecated()).isFalse();
                assertThat(v2.sunset().isEmpty()).isTrue();
                assertThat(v2.defaultIfMissing()).isTrue();
            });
        }
    }

    @Nested
    class ErrorMappingParsing {

        @Test
        void parseErrors_parsesBareNumericStatusKeys() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                getSeat = "GET /{id}"

                [errors]
                default = 500
                404 = ["SeatError.SeatNotFound"]
                400 = ["SeatError.InvalidSeat", "*Blank*"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var errors = rc.errors();
                assertThat(errors.defaultStatus()).isEqualTo(500);
                assertThat(errors.statusPatterns().get(404)).containsExactly("SeatError.SeatNotFound");
                assertThat(errors.statusPatterns().get(400)).containsExactly("SeatError.InvalidSeat", "*Blank*");
            });
        }

        @Test
        void parseErrors_keepsLegacyHttpPrefixedKeys() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                getSeat = "GET /{id}"

                [errors]
                default = 500
                HTTP_404 = ["*NotFound*", "*Missing*"]
                HTTP_400 = ["*Invalid*"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> {
                var errors = rc.errors();
                assertThat(errors.statusPatterns().get(404)).containsExactly("*NotFound*", "*Missing*");
                assertThat(errors.statusPatterns().get(400)).containsExactly("*Invalid*");
            });
        }

        @Test
        void parseErrors_parsesStrictFlag() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                getSeat = "GET /{id}"

                [errors]
                strict = true
                404 = ["SeatError.SeatNotFound"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> assertThat(rc.errors().strict()).isTrue());
        }

        @Test
        void parseErrors_defaultsStrictToFalse() throws IOException {
            var config = writeConfig("routes.toml", """
                [routes]
                getSeat = "GET /{id}"

                [errors]
                404 = ["SeatError.SeatNotFound"]
                """);

            var result = RouteConfigLoader.load(config);

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(rc -> assertThat(rc.errors().strict()).isFalse());
        }
    }
}
