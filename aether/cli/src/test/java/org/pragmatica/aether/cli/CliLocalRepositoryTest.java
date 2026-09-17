// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins that `artifacts push` resolves jars through the SAME Maven local-repository
/// resolution the rest of the runtime uses (`MavenLocalRepoLocator`), rather than a
/// hard-coded `${user.home}/.m2/repository`.
///
/// Why this exists: the CLI previously hard-coded the default repository with no
/// override, while builds in this workspace are directed elsewhere by
/// `-Dmaven.repo.local`. The two halves could not meet, so the deploy harness pushed
/// whatever stale fixtures happened to sit in the default repository — and nothing
/// in the tree detected it. These tests fail if that override is ever reintroduced.
class CliLocalRepositoryTest {
    private static final String PROPERTY = "maven.repo.local";

    private final String saved = System.getProperty(PROPERTY);

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, saved);
        }
    }

    @Test
    void findBlueprintJar_honoursMavenRepoLocal() {
        var repo = Path.of(System.getProperty("java.io.tmpdir"), "cli-local-repo-test-blueprint");

        System.setProperty(PROPERTY, repo.toString());

        var path = AetherCli.ArtifactCommand.PushArtifactCommand.findBlueprintJar("org.example", "demo", "1.2.3");

        assertEquals(repo.resolve("org/example/demo/1.2.3/demo-1.2.3-blueprint.jar"), path);
    }

    @Test
    void findSliceJar_honoursMavenRepoLocal() {
        var repo = Path.of(System.getProperty("java.io.tmpdir"), "cli-local-repo-test-slice");

        System.setProperty(PROPERTY, repo.toString());

        var path = AetherCli.ArtifactCommand.PushArtifactCommand.findSliceJar("org.example", "demo", "1.2.3");

        assertEquals(repo.resolve("org/example/demo/1.2.3/demo-1.2.3.jar"), path);
    }

    /// Negative control: without the override the CLI must NOT silently keep using a
    /// path from a previous call, and must land under the user's default repository.
    /// Without this, both tests above would still pass if the resolver were replaced
    /// by something that echoed whatever was last set.
    @Test
    void withoutOverride_fallsBackToUserHomeRepository() {
        System.clearProperty(PROPERTY);

        var path = AetherCli.ArtifactCommand.PushArtifactCommand.findBlueprintJar("org.example", "demo", "1.2.3");
        var overridden = Path.of(System.getProperty("java.io.tmpdir"), "cli-local-repo-test-blueprint")
                             .resolve("org/example/demo/1.2.3/demo-1.2.3-blueprint.jar");

        assertNotEquals(overridden, path);
        assertTrue(path.endsWith(Path.of("org/example/demo/1.2.3/demo-1.2.3-blueprint.jar")),
                   "expected coordinate layout under the default repository, got: " + path);
    }
}
