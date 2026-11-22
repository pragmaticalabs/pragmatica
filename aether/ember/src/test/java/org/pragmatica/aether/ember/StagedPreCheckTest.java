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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #3021 probe-1 seam: the pre-check locates through the staged router, and `bypassed()` skips only the refusal. A jar PLANTED
/// under the module coordinate in `maven.repo.local` must not satisfy the pre-check (the router never consults it).
///
/// #3031 fix round 1 (A2, A3): the pre-check refuses a missing, empty, non-regular or unreadable jar and one without a readable
/// manifest, keeps the locate cause in the text, and picks the remedy by coordinate kind. It does NOT check staleness, and the
/// refusal text says so (a test pins that sentence, so no message can claim "stale, therefore refused").
class StagedPreCheckTest {
    private static final Artifact MODULE = Artifact.artifact("org.example:echo:1.0.0").unwrap();
    private static final Artifact DEPENDENCY = Artifact.artifact("org.example:lib:2.0.0").unwrap();
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
        jar(staged, MODULE);

        var result = StagedPreCheck.stagedPreCheck().check(stagedType(), Set.of(MODULE), FOUND).await();

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void check_failsEmberAppNotPackaged_whenOnlyMavenLocalHoldsTheCoordinate() throws IOException {
        jar(mavenLocal, MODULE);

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

    @Test
    void check_refusesAnEmptyJar() throws IOException {
        Files.write(jarPath(staged, MODULE), new byte[0]);

        assertThat(refusalFor(MODULE)).contains("empty file").contains(jarName(MODULE));
    }

    @Test
    void check_refusesADirectoryNamedLikeTheJar() throws IOException {
        Files.createDirectories(jarPath(staged, MODULE));

        assertThat(refusalFor(MODULE)).contains("not a regular file").contains(jarName(MODULE));
    }

    @Test
    void check_refusesAFileThatIsNotAJar() throws IOException {
        Files.writeString(jarPath(staged, MODULE), "planted under " + staged);

        assertThat(refusalFor(MODULE)).as("the zip cause is kept").contains("zip");
    }

    @Test
    void check_refusesAJarWithoutAManifest() throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(jarPath(staged, MODULE)))) {
            out.putNextEntry(new ZipEntry("Echo.class"));
            out.closeEntry();
        }

        assertThat(refusalFor(MODULE)).contains("no manifest").contains(jarName(MODULE));
    }

    @Test
    void check_refusesAnUnreadableJar() throws IOException {
        jar(staged, MODULE);
        var path = jarPath(staged, MODULE);

        assertThat(path.toFile().setReadable(false)).isTrue();
        Assumptions.assumeFalse(Files.isReadable(path), "running as a user that reads unreadable files (root)");

        assertThat(refusalFor(MODULE)).contains("not readable");
    }

    @Test
    void refusal_keepsTheLocateCause_namingTheStagedPathThatWasChecked() {
        assertThat(refusalFor(MODULE)).contains(staged.toString(), jarName(MODULE));
    }

    @Test
    void refusal_neverClaimsToCheckStaleness_andSaysSo() {
        assertThat(refusalFor(MODULE)).contains("does not check staleness").doesNotContain("stale jar").doesNotContain("out of date");
    }

    /// v3031h survivor n2: the note states what the check DOES refuse as well as what it does not, so a reader learns its reach.
    @Test
    void refusal_statesWhatTheCheckRefuses_asWellAsWhatItDoesNot() {
        assertThat(refusalFor(MODULE)).contains("refuses a missing, empty, non-regular or unreadable jar, or one without a readable manifest");
    }

    @Test
    void refusal_ofAModuleCoordinate_saysToPackageTheModule() {
        assertThat(refusalFor(MODULE)).contains("mvn package -DskipTests").doesNotContain("Maven local repository");
    }

    @Test
    void refusal_ofANonModuleCoordinate_saysToInstallThatArtifact_notToPackageThisModule() {
        var message = StagedPreCheck.stagedPreCheck()
                                    .check(stagedType(), Set.of(DEPENDENCY), FOUND)
                                    .await()
                                    .fold(cause -> cause.message(), _ -> "");

        assertThat(message).contains(DEPENDENCY.asString(), mavenLocal.toString(), "Maven local repository", "mvn install", "does not check staleness")
                  .doesNotContain("mvn package -DskipTests");
    }

    @Test
    void check_succeeds_whenANonModuleCoordinateIsInMavenLocal() throws IOException {
        jar(mavenLocal, DEPENDENCY);

        var result = StagedPreCheck.stagedPreCheck().check(stagedType(), Set.of(DEPENDENCY), FOUND).await();

        assertThat(result.isSuccess()).isTrue();
    }

    private String refusalFor(Artifact coordinate) {
        var result = StagedPreCheck.stagedPreCheck().check(stagedType(), Set.of(coordinate), FOUND).await();

        assertThat(result.isFailure()).as("the pre-check must refuse " + coordinate.asString()).isTrue();
        return result.fold(cause -> cause.message(), _ -> "");
    }

    private RepositoryType.Staged stagedType() {
        return RepositoryType.Staged.staged(staged, Set.of(MODULE));
    }

    private static String jarName(Artifact coordinate) {
        return coordinate.artifactId().id() + "-" + coordinate.version().withQualifier() + ".jar";
    }

    private static Path jarPath(Path root, Artifact coordinate) throws IOException {
        var dir = root.resolve(coordinate.groupId().id().replace('.', '/'))
                      .resolve(coordinate.artifactId().id())
                      .resolve(coordinate.version().withQualifier());

        Files.createDirectories(dir);
        return dir.resolve(jarName(coordinate));
    }

    /// A real jar: a manifest entry is what the pre-check requires.
    private static void jar(Path root, Artifact coordinate) throws IOException {
        var manifest = new Manifest();

        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (var out = new JarOutputStream(Files.newOutputStream(jarPath(root, coordinate)), manifest)) {
            out.putNextEntry(new JarEntry("Echo.class"));
            out.closeEntry();
        }
    }
}
