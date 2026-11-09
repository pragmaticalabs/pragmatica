// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.testslice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2946: `security = "optional"` in routes.toml is generated as `SecurityPolicy.optional()` (authenticate if a
/// credential is presented), and only on the route that declares it. The fixture's `getItem` route declares it; its
/// siblings declare nothing and stay `unspecified()`.
class GeneratedRouteOptionalSecurityTest {
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

        return Files.exists(candidate)
               ? candidate
               : moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Test
    void generatedRoutes_emitOptionalPolicy_forTheOptionalRoute() {
        assertThat(generated).contains(".named(\"getItem\").withSecurity(SecurityPolicy.optional())");
    }

    @Test
    void generatedRoutes_keepUnspecifiedPolicy_forRoutesDeclaringNothing() {
        assertThat(generated).contains(".named(\"getById\").withSecurity(SecurityPolicy.unspecified())");
    }
}
