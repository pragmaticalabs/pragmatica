// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #3021 probe-1 seam: the pre-check locates through the staged router, and `bypassed()` skips only the refusal. A jar PLANTED
/// under the module coordinate in `maven.repo.local` must not satisfy the pre-check (the router never consults it).
class StagedPreCheckTest {
    private static final Artifact MODULE = Artifact.artifact("org.example:echo:1.0.0").unwrap();
    private static final List<String> FOUND = List.of("org.example:other:1.0.0");

    @TempDir
    Path staged;

    @TempDir
    Path mavenLocal;

    private String savedMavenLocal;

    @BeforeEach
    void pointMavenLocalRepositoryAtTheTempDir() {
        savedMavenLocal = System.getProperty("maven.repo.local");
        System.setProperty("maven.repo.local", mavenLocal.toString());
    }

    @AfterEach
    void restoreMavenLocalRepository() {
        if (savedMavenLocal == null) {
            System.clearProperty("maven.repo.local");
        } else {
            System.setProperty("maven.repo.local", savedMavenLocal);
        }
    }

    @Test
    void check_succeeds_whenTheCoordinateIsUnderTheStagedRoot() throws IOException {
        jar(staged);

        var result = StagedPreCheck.stagedPreCheck().check(stagedType(), Set.of(MODULE), FOUND).await();

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void check_failsEmberAppNotPackaged_whenOnlyMavenLocalHoldsTheCoordinate() throws IOException {
        jar(mavenLocal);

        var result = StagedPreCheck.stagedPreCheck().check(stagedType(), Set.of(MODULE), FOUND).await();

        assertThat(result.isFailure()).as("a planted jar in maven.repo.local must not satisfy the pre-check").isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(EmberAppNotPackaged.class,
                                                                           missing -> assertThat(missing.coordinate()).isEqualTo(MODULE.asString())
                                                                                                                      .describedAs("names the coordinate")))
              .onFailure(cause -> assertThat(cause.message()).contains(MODULE.asString(), FOUND.getFirst(), "mvn package"));
    }

    @Test
    void bypassed_accepts_whereTheRealCheckRefuses() {
        var type = stagedType();

        assertThat(StagedPreCheck.stagedPreCheck().check(type, Set.of(MODULE), FOUND).await().isFailure()).as("CONTROL").isTrue();
        assertThat(StagedPreCheck.bypassed().check(type, Set.of(MODULE), FOUND).await().isSuccess()).isTrue();
    }

    private RepositoryType.Staged stagedType() {
        return RepositoryType.Staged.staged(staged, Set.of(MODULE));
    }

    private static void jar(Path root) throws IOException {
        var version = MODULE.version().withQualifier();
        var id = MODULE.artifactId().id();
        var dir = root.resolve(MODULE.groupId().id().replace('.', '/')).resolve(id).resolve(version);

        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + "-" + version + ".jar"), "planted under " + root);
    }
}
