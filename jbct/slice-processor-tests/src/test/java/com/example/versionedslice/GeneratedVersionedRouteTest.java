// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.versionedslice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the slice-processor emits #198 API path-mode versioning into the generated
/// `VersionedSliceRoutes` source: each `[vN.routes]` bind key resolves to a `getV{N}` /
/// `upsertV{N}` handler (D8) and the route mounts at `{api.prefix}/v{N}/...` (path-mode routing).
class GeneratedVersionedRouteTest {

    private static String generated;

    @BeforeAll
    static void readGeneratedSource() throws IOException {
        generated = Files.readString(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations",
                                 "com", "example", "versionedslice", "VersionedSliceRoutes.java");
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Nested
    class V1Routes {

        @Test
        void getV1_mountsUnderVersionOnePath() {
            assertThat(generated).contains(".named(\"getV1\")");
            assertThat(generated).contains("Route.<com.example.versionedslice.VersionedSlice.GetResponse>get(\"/api/orders/v1/\")");
        }

        @Test
        void getV1_bindsToGetV1Handler() {
            assertThat(generated).contains("delegate.getV1(");
        }
    }

    @Nested
    class V2Routes {

        @Test
        void getV2_mountsUnderVersionTwoPath() {
            assertThat(generated).contains(".named(\"getV2\")");
            assertThat(generated).contains("Route.<com.example.versionedslice.VersionedSlice.GetResponse>get(\"/api/orders/v2/\")");
        }

        @Test
        void getV2_bindsToGetV2Handler() {
            assertThat(generated).contains("delegate.getV2(");
        }

        @Test
        void upsertV2_mountsUnderVersionTwoPathAsPut() {
            assertThat(generated).contains(".named(\"upsertV2\")");
            assertThat(generated).contains("Route.<com.example.versionedslice.VersionedSlice.UpsertResponse>put(\"/api/orders/v2/\")");
            assertThat(generated).contains("delegate.upsertV2(");
        }
    }

    @Nested
    class VersionSeparation {

        @Test
        void v1AndV2PathsAreDistinct() {
            assertThat(generated).contains("/api/orders/v1/");
            assertThat(generated).contains("/api/orders/v2/");
        }

        @Test
        void noUnversionedFallbackPathIsEmitted() {
            // every versioned route carries a /vN/ segment; no bare /api/orders/{id} route exists
            assertThat(generated).doesNotContain("get(\"/api/orders/\")");
        }
    }
}
