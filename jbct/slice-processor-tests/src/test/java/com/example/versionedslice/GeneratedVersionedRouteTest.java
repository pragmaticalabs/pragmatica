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

/// Verifies the slice-processor emits #198 §7 deploy-either-way versioning into the generated
/// `VersionedSliceRoutes` source: `routes()` returns UN-mounted routes (bare path + `.versioned(N)`
/// metadata, NO baked `/v{N}/` and NO `mountInPathMode` call), each `[vN.routes]` bind key resolves
/// to a `getV{N}` / `upsertV{N}` handler (D8), the version registry is emitted, and a
/// `create(slice, jsonMapper, RouteMountMode)` factory method threads the deploy-time mount mode.
/// Path/header mounting itself is applied by the registration consumer, not baked here.
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
        void getV1_emitsUnmountedBareRouteTaggedVersionOne() {
            assertThat(generated).contains(".named(\"getV1\")");
            // Un-mounted: bare "/" path tagged .versioned(1); the /api/orders/v1/ mount is deferred.
            assertThat(generated).contains("Route.<com.example.versionedslice.VersionedSlice.GetResponse>get(\"/\")");
            assertThat(generated).contains(".versioned(1)");
        }

        @Test
        void getV1_bindsToGetV1Handler() {
            assertThat(generated).contains("delegate.getV1(");
        }
    }

    @Nested
    class V2Routes {

        @Test
        void getV2_emitsUnmountedBareRouteTaggedVersionTwo() {
            assertThat(generated).contains(".named(\"getV2\")");
            assertThat(generated).contains("Route.<com.example.versionedslice.VersionedSlice.GetResponse>get(\"/\")");
            assertThat(generated).contains(".versioned(2)");
        }

        @Test
        void getV2_bindsToGetV2Handler() {
            assertThat(generated).contains("delegate.getV2(");
        }

        @Test
        void upsertV2_emitsUnmountedBareRouteAsPut() {
            assertThat(generated).contains(".named(\"upsertV2\")");
            assertThat(generated).contains("Route.<com.example.versionedslice.VersionedSlice.UpsertResponse>put(\"/\")");
            assertThat(generated).contains("delegate.upsertV2(");
        }
    }

    @Nested
    class DeployEitherWay {

        @Test
        void routesAreNotBakedInPathMode() {
            // #198 §7: mounting moved OUT of routes() to the registration consumer.
            assertThat(generated).doesNotContain("mountInPathMode");
            assertThat(generated).doesNotContain("get(\"/api/orders/v1/\")");
            assertThat(generated).doesNotContain("get(\"/api/orders/v2/\")");
        }

        @Test
        void factoryThreadsMountModeForDeployTimeDetection() {
            assertThat(generated).contains("RouteMountMode mountMode");
            assertThat(generated).contains("SliceRouter.sliceRouter(routes, routes.errorMapper(), jsonMapper, mountMode)");
        }

        @Test
        void versionRegistryIsEmittedWithApiPrefix() {
            assertThat(generated).contains("public SliceVersionRegistry versionRegistry()");
            assertThat(generated).contains("\"/api/orders\"");
        }
    }
}
