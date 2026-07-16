// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.widehost;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the `Promise.all` batching that lifts a slice factory past the core arity-15 ceiling.
///
/// [WideHostSlice] injects [com.example.widedep.WideDepSlice] (16 methods), so its generated factory
/// must provision 16 slice-method handles — one over the flat `Promise.all` limit that previously
/// hard-errored. The strongest assertion is implicit: this module compiles its generated sources with
/// javac, so a malformed batched `WideHostSliceFactory` would fail the module build and these tests
/// would not run. The explicit assertions pin the batched shape (tuple parts + `.id()` + cascade)
/// and, as a regression guard on the untouched fast-path, that a &lt;=15-dependency factory stays flat.
class GeneratedWideSliceFactoryTest {

    private static String wideFactory;
    private static String flatFactory;

    @BeforeAll
    static void readGeneratedSources() throws IOException {
        wideFactory = Files.readString(locateGenerated("widehost", "WideHostSliceFactory.java"));
        flatFactory = Files.readString(locateGenerated("hostslice", "BookingSliceFactory.java"));
    }

    private static Path locateGenerated(String pkg, String fileName) {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", pkg, fileName);
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Nested
    class BatchedAssembly {
        @Test
        void sixteenHandles_batchIntoTwoTupleParts() {
            assertThat(wideFactory).contains("var part1 = Promise.all(");
            assertThat(wideFactory).contains("var part2 = Promise.all(");
            assertThat(wideFactory).contains(").id();");
            assertThat(wideFactory).contains("return Promise.all(part1, part2)");
        }

        @Test
        void outerJoinCascadesTupleMap_thenBuildsTheSlice() {
            // Direct factory return kind -> outer combinator is .map; inner tuple unwrapping is .map.
            assertThat(wideFactory).contains(".map((t1, t2) ->");
            assertThat(wideFactory).contains("t1.map((");
            assertThat(wideFactory).contains("t2.map((");
            // The innermost lambda still assembles the proxy and calls the slice factory.
            assertThat(wideFactory).contains("WideHostSlice.wideHostSlice(dep)");
        }

        @Test
        void everyHandleIsBoundAcrossTheCascade() {
            // All 16 provisioned handles must be rebound by the cascade for the proxy constructor.
            for (var i = 1; i <= 16; i++) {
                var handle = String.format("dep_m%02d", i);
                assertThat(wideFactory)
                    .as("batched cascade must rebind handle " + handle)
                    .contains(handle);
            }
        }
    }

    @Nested
    class FlatFastPathUnchanged {
        @Test
        void singleHandleFactory_staysFlat_noBatching() {
            assertThat(flatFactory).contains("return Promise.all(");
            assertThat(flatFactory).doesNotContain(".id()");
            assertThat(flatFactory).doesNotContain("var part1");
        }
    }
}
