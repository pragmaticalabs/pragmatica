// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.testslice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// #882: every generated `*Routes` class stamps the route-security contract it was built against,
/// so a node can refuse a JAR generated before #763 (whose no-`[security]` routes are baked in as
/// public) instead of serving it open. Pinned on the generated SOURCE, like the sibling tests, and
/// on the compiled class through the factory the ServiceLoader would hand the node.
class GeneratedRouteSecurityContractTest {
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
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Test
    void generatedRoutes_stampTheCurrentContract() {
        assertThat(generated).contains("public int routeSecurityContract()")
                             .contains("return SliceRouterFactory.ROUTE_SECURITY_CONTRACT;");
        assertThat(new TestSliceRoutes().routeSecurityContract()).isEqualTo(SliceRouterFactory.ROUTE_SECURITY_CONTRACT);
    }
}
