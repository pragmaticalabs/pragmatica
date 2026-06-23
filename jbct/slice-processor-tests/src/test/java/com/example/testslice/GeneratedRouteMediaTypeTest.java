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
            assertThat(generated).contains(".named(\"create\").withSecurity(SecurityPolicy.publicRoute()).asJson()");
            assertThat(generated).contains(".named(\"getById\").withSecurity(SecurityPolicy.publicRoute()).asJson()");
            assertThat(generated).contains(".named(\"health\").withSecurity(SecurityPolicy.publicRoute()).asJson()");
        }

        @Test
        void jsonBodyRoutesStillEmitWithBodyTypeToken() {
            assertThat(generated).contains(".withBody(new TypeToken<com.example.testslice.CreateRequest>() {})");
            assertThat(generated).contains(".withBody(new TypeToken<com.example.testslice.UpdateRequest>() {})");
        }
    }
}
