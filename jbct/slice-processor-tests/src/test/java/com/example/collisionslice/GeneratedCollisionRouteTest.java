// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.collisionslice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression gate for the route-import simple-name collision bug in `RouteSourceGenerator`.
///
/// [CollisionSlice]'s package declares two error families ([BuyError], [CancelError]) whose nested
/// failure records both have the simple name `StoreUnavailable`. Before the fix the generator emitted
/// one single-type `import` per mapped error type unconditionally, so it produced
/// `import com.example.collisionslice.BuyError.StoreUnavailable;` AND
/// `import com.example.collisionslice.CancelError.StoreUnavailable;` — two single-type imports of the
/// same simple name, which is a compile error. The generated `CollisionSliceRoutes` therefore failed
/// to compile, and (because this module compiles generated sources with javac) so did the whole
/// module. The fix skips the colliding imports and the error switch references those types by their
/// fully-qualified name, mirroring the logic already used by `generateErrorMapperMethod`.
///
/// The strongest assertion here is implicit: if the generator regressed, this module would not
/// compile and these tests would not run at all. The explicit assertions pin the generated shape.
class GeneratedCollisionRouteTest {

    private static String generated;

    @BeforeAll
    static void readGeneratedSource() throws IOException {
        generated = Files.readString(locateGeneratedRoutes());
    }

    private static Path locateGeneratedRoutes() {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var relative = Paths.get("target", "generated-sources", "annotations",
                                 "com", "example", "collisionslice", "CollisionSliceRoutes.java");
        var candidate = moduleDir.resolve(relative);
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Test
    void collidingErrorSimpleNames_doNotProduceDuplicateSingleTypeImports() {
        assertThat(generated).doesNotContain("import com.example.collisionslice.BuyError.StoreUnavailable;");
        assertThat(generated).doesNotContain("import com.example.collisionslice.CancelError.StoreUnavailable;");
    }

    @Test
    void collidingErrorSimpleNames_areReferencedByFullyQualifiedCase() {
        assertThat(generated).contains("case com.example.collisionslice.BuyError.StoreUnavailable");
        assertThat(generated).contains("case com.example.collisionslice.CancelError.StoreUnavailable");
        assertThat(generated).contains("public ErrorMapper errorMapper()");
    }
}
