// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.testslice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.jbct.slice.routing.RouteSourceGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/// #882: every generated `*Routes` class stamps the route-security contract it was built against,
/// so a node can refuse a JAR generated before #763 (whose no-`[security]` routes are baked in as
/// public) instead of serving it open. The stamp must be the GENERATOR's number, frozen into the
/// class as a literal: a symbolic `SliceRouterFactory.ROUTE_SECURITY_CONTRACT` would be resolved by
/// javac against whatever adapter the slice compiles against, so an old generator on a new adapter
/// would stamp a contract it does not implement. This module is the only one that sees both the
/// generator and the adapter, so the equality between the two constants is pinned here.
class GeneratedRouteSecurityContractTest {
    private static final String ADAPTER_CONSTANT_NAME = "ROUTE_SECURITY_CONTRACT";
    private static String generated;
    private static byte[] compiled;

    @BeforeAll
    static void readGeneratedSourceAndClass() throws IOException {
        generated = Files.readString(locateGeneratedRoutes());
        try (var in = TestSliceRoutes.class.getResourceAsStream("TestSliceRoutes.class")) {
            assertThat(in).as("compiled TestSliceRoutes.class on the test classpath").isNotNull();
            compiled = in.readAllBytes();
        }
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

    /// The generator's own number and the adapter's requirement are two independent constants in
    /// two modules that cannot see each other; a bump on either side without the other reddens here.
    @Test
    void theGeneratorAndTheAdapter_agreeOnTheCurrentContract() {
        assertThat(RouteSourceGenerator.ROUTE_SECURITY_CONTRACT).isEqualTo(SliceRouterFactory.ROUTE_SECURITY_CONTRACT);
    }

    /// The emitted override returns the generator's literal — in the source text and in the class
    /// the ServiceLoader would hand the node.
    @Test
    void generatedRoutes_stampTheGeneratorsContract() {
        assertThat(generated).contains("public int routeSecurityContract()")
                             .contains("return " + RouteSourceGenerator.ROUTE_SECURITY_CONTRACT + ";");
        assertThat(new TestSliceRoutes().routeSecurityContract()).isEqualTo(RouteSourceGenerator.ROUTE_SECURITY_CONTRACT);
    }

    /// The stamp is frozen into the JAR: the compiled class must not read the adapter constant at
    /// runtime, or every stamped JAR would answer the NODE's current value and the refusal would be
    /// vacuous for every future bump. Instrument: a `getstatic` needs a Fieldref → NameAndType →
    /// Utf8 constant-pool entry naming the field, so the class bytes must not contain the field's
    /// name at all. Positive control on the same bytes: the override's own method name IS present.
    @Test
    void generatedRoutes_freezeTheStamp_noRuntimeReadOfTheAdapterConstant() {
        var classBytes = new String(compiled, StandardCharsets.ISO_8859_1);

        assertThat(classBytes).as("positive control: the instrument can see constant-pool names in these bytes")
                              .contains("routeSecurityContract");
        assertThat(generated).as("the generated source must not reference the adapter constant symbolically")
                             .doesNotContain(ADAPTER_CONSTANT_NAME);
        assertThat(classBytes).as("no Fieldref to the adapter constant in the compiled class (would be a getstatic)")
                              .doesNotContain(ADAPTER_CONSTANT_NAME);
    }
}
