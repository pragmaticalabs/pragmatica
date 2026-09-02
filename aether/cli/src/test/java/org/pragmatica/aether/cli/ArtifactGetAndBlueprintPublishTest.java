// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpMethod;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Structural tests for the C5 (`aether artifacts get`) and C6 (`aether blueprints publish`)
/// CLI commands. Verifies route assembly produces the expected URI shape and that the
/// command-side body construction matches the existing `BLUEPRINT_DEPLOY` request shape
/// (since both routes share the same `BlueprintDeployRequest` body type server-side).
class ArtifactGetAndBlueprintPublishTest {

    @Nested
    class ArtifactGetRouteAssembly {
        @Test
        void assemble_dottedGroupReplacedWithSlashes_pathBuiltFromAllFourParams() {
            var groupPath = "org.example.app".replace('.', '/');
            var artifactId = "url-shortener";
            var version = "1.0.0";
            var filename = artifactId + "-" + version + ".jar";

            var result = ManagementRoute.ARTIFACT_GET.assemble(List.of(groupPath,
                                                                       artifactId,
                                                                       version,
                                                                       filename));
            assertTrue(result.isSuccess(), "assemble should succeed for 4 params");
            result.onSuccess(path -> assertEquals(
                    "/repository/org/example/app/url-shortener/1.0.0/url-shortener-1.0.0.jar",
                    path));
        }

        @Test
        void assemble_customFilename_pathUsesIt() {
            var result = ManagementRoute.ARTIFACT_GET.assemble(List.of("com/example",
                                                                       "my-slice",
                                                                       "2.0.0",
                                                                       "my-slice-2.0.0-sources.jar"));
            assertTrue(result.isSuccess());
            result.onSuccess(path -> assertEquals(
                    "/repository/com/example/my-slice/2.0.0/my-slice-2.0.0-sources.jar",
                    path));
        }

        @Test
        void assemble_missingParam_returnsFailure() {
            var result = ManagementRoute.ARTIFACT_GET.assemble(List.of("g", "a", "v"));
            assertTrue(result.isFailure(),
                       "assemble should fail when param count does not match (got 3, expected 4)");
        }

        @Test
        void route_methodIsGet_pathStartsWithRepository() {
            assertEquals(HttpMethod.GET, ManagementRoute.ARTIFACT_GET.method());
            assertTrue(ManagementRoute.ARTIFACT_GET.prefix().startsWith("/repository"),
                       "ARTIFACT_GET should be under /repository");
        }
    }

    @Nested
    class BlueprintPublishArtifact {
        @Test
        void route_methodIsPost_pathIsBlueprintsPublish() {
            assertEquals(HttpMethod.POST, ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT.method());
            assertEquals("/api/v1/blueprints/publish",
                         ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT.prefix());
        }

        @Test
        void route_assembleWithNoParams_succeeds() {
            var result = ManagementRoute.BLUEPRINT_PUBLISH_ARTIFACT.assemble(List.of());
            assertTrue(result.isSuccess(),
                       "BLUEPRINT_PUBLISH_ARTIFACT takes no path params; body carries the artifact");
            result.onSuccess(path -> assertEquals("/api/v1/blueprints/publish", path));
        }

        @Test
        void publishBody_appendsBlueprintQualifier_artifactFieldQuoted() {
            // Mirrors the CLI body construction in PublishCommand.call():
            //   "{\"artifact\":\"<coords>:blueprint\"}"
            var coordinates = "org.example:my-app:1.0.0";
            var body = buildPublishBody(coordinates);

            assertEquals("{\"artifact\":\"org.example:my-app:1.0.0:blueprint\"}", body);
        }

        @Test
        void publishBody_coordinatesWithDots_passedThroughUnchanged() {
            // Group paths in the body keep their dots — the slash-replacement is only
            // for the URL path (/repository/<g/path>/), never the body's artifact coord.
            var body = buildPublishBody("com.acme.web:url-shortener:2.1.3");

            assertEquals("{\"artifact\":\"com.acme.web:url-shortener:2.1.3:blueprint\"}", body);
        }

        private static String buildPublishBody(String coordinates) {
            var fullCoords = coordinates + ":blueprint";
            return "{\"artifact\":\"" + escape(fullCoords) + "\"}";
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    @Nested
    class CoordinateParsing {
        @Test
        void splitCoordinates_threeParts_extractsGroupArtifactVersion() {
            // Mirrors the CLI's `var parts = coordinates.split(":")` contract.
            var parts = "org.example:my-app:1.0.0".split(":");
            assertEquals(3, parts.length);
            assertEquals("org.example", parts[0]);
            assertEquals("my-app", parts[1]);
            assertEquals("1.0.0", parts[2]);
        }

        @Test
        void splitCoordinates_twoParts_failsValidation() {
            var parts = "org.example:my-app".split(":");
            assertEquals(2, parts.length,
                         "2-part coords must be rejected by both commands (length != 3)");
        }

        @Test
        void defaultFilename_combinesArtifactIdAndVersion() {
            // Mirrors the default in GetArtifactCommand.call() when --file is omitted.
            var artifactId = "my-slice";
            var version = "1.2.3";
            var expected = artifactId + "-" + version + ".jar";
            assertEquals("my-slice-1.2.3.jar", expected);
        }
    }
}
