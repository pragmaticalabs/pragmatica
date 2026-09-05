// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.testslice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the slice-processor emits the #339 `produces`/`consumes` media types into the
/// generated `TestSliceRoutes` source: declared media types become `.as(...)` output calls and
/// the consumes-appropriate body binding, while undeclared routes keep `.asJson()` /
/// `.withBody(TypeToken)` byte-for-byte (back-compat).
class GeneratedRouteMediaTypeTest {

    private static String generated;

    @BeforeAll
    static void readGeneratedSource() throws IOException {
        generated = Files.readString(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations",
                                 "com", "example", "testslice", "TestSliceRoutes.java");
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Nested
    class ProducesOutput {

        @Test
        void exportCsv_emitsTextCsvContentType() {
            assertThat(generated).contains(".named(\"exportCsv\")");
            assertThat(generated).contains(".as(CommonContentType.TEXT_CSV)");
            assertThat(generated).contains("Route.<java.lang.String>get(\"/api/v1/test/export/\")");
        }

        @Test
        void download_emitsOctetStreamContentType() {
            assertThat(generated).contains(".named(\"download\")");
            assertThat(generated).contains(".as(CommonContentType.APPLICATION_OCTET_STREAM)");
            assertThat(generated).contains("Route.<byte[]>get(\"/api/v1/test/download/\")");
        }
    }

    @Nested
    class ConsumesBinding {

        @Test
        void uploadText_emitsStringBodyBinding() {
            assertThat(generated).contains(".named(\"uploadText\")");
            assertThat(generated).contains(".withStringBody()");
        }

        @Test
        void uploadForm_emitsMultipartBodyBinding() {
            assertThat(generated).contains(".named(\"uploadForm\")");
            assertThat(generated).contains(".withMultipartBody()");
        }

        @Test
        void mediaTypeImportsArePresent() {
            assertThat(generated).contains("import org.pragmatica.http.CommonContentType;");
            assertThat(generated).contains("import org.pragmatica.http.routing.MultipartRequest;");
        }
    }

    @Nested
    class BackwardCompatibility {

        @Test
        void jsonRoutesStillEmitAsJson() {
            // #763: this fixture's routes.toml has no [security] section on any route, which used
            // to codegen as SecurityPolicy.publicRoute() -- the #763 bug itself, reproduced here.
            // The fix changed RouteConfigLoader.DEFAULT_SECURITY to UNSPECIFIED, so an existing
            // slice with no [security] section now inherits the server's global policy at request
            // time instead of always being public; the generated source reflects that correctly.
            // "Backward-compatible" here means the JSON-emission shape (.asJson()) is unchanged,
            // not the security literal -- pin the CURRENT correct security default, not the old one.
            assertThat(generated).contains(".named(\"create\").withSecurity(SecurityPolicy.unspecified()).asJson()");
            assertThat(generated).contains(".named(\"getById\").withSecurity(SecurityPolicy.unspecified()).asJson()");
            assertThat(generated).contains(".named(\"health\").withSecurity(SecurityPolicy.unspecified()).asJson()");
        }

        @Test
        void jsonBodyRoutesStillEmitWithBodyTypeToken() {
            assertThat(generated).contains(".withBody(new TypeToken<com.example.testslice.CreateRequest>() {})");
            assertThat(generated).contains(".withBody(new TypeToken<com.example.testslice.UpdateRequest>() {})");
        }
    }

    /// Regression gate for the routing API package move in commit `76a2a6b91`:
    /// `HttpError`/`HttpStatus`/`ContentType` moved `org.pragmatica.http.routing` →
    /// `org.pragmatica.http` (module `integrations/net/http-types`). The slice-processor MUST emit
    /// the new FQN so deployed slice JARs reference a class that resolves on the node/runtime
    /// classpath via `SliceClassLoader`. A generator regressing to the OLD FQN produces a JAR that
    /// throws `ClassNotFoundException` at route-publication → every slice 404s. The error-mapping
    /// route `create` (mapped to `TestSliceError.*`) makes the errorMapper reference `HttpError`
    /// and `HttpStatus`, so these imports are load-bearing, not dead.
    @Nested
    class RoutingApiFqn {

        @Test
        void httpErrorImport_usesNewHttpPackage_notRoutingPackage() {
            assertThat(generated).contains("import org.pragmatica.http.HttpError;");
            assertThat(generated).doesNotContain("import org.pragmatica.http.routing.HttpError;");
            assertThat(generated).doesNotContain("org.pragmatica.http.routing.HttpError");
        }

        @Test
        void httpStatusImport_usesNewHttpPackage_notRoutingPackage() {
            assertThat(generated).contains("import org.pragmatica.http.HttpStatus;");
            assertThat(generated).doesNotContain("import org.pragmatica.http.routing.HttpStatus;");
            assertThat(generated).doesNotContain("org.pragmatica.http.routing.HttpStatus");
        }

        @Test
        void errorMapperBody_referencesHttpErrorAndHttpStatus_makingImportsLoadBearing() {
            assertThat(generated).contains("public ErrorMapper errorMapper()");
            assertThat(generated).contains("HttpError.httpError(HttpStatus.");
        }

        @Test
        void noRoutingApiClass_isReferencedViaOldPackage() {
            assertThat(generated).doesNotContain("org.pragmatica.http.routing.ContentType");
            assertThat(generated).doesNotContain("org.pragmatica.http.routing.CommonContentType");
            assertThat(generated).doesNotContain("org.pragmatica.http.routing.ContentCategory");
        }
    }
}
