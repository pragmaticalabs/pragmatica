// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.repository.CompositeRepository;
import org.pragmatica.aether.slice.repository.Location;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/// #3021 step 1: the `Staged` entry is the router that makes a module's own coordinates fail closed. The Maven local
/// repository (`maven.repo.local`) holds a PLANTED jar under the module coordinate in every test that matters, so a
/// coordinate that leaked to it would be located and the test would go red.
class RepositoryFactoryStagedTest {
    private static final Artifact MODULE = coordinate("org.example:echo:1.0.0");
    private static final Artifact FOREIGN = coordinate("org.example:other:2.0.0");

    @TempDir
    Path staged;
    @TempDir
    Path mavenLocal;

    private String savedRepoLocal;

    @BeforeEach
    void pointMavenLocalRepositoryAtTheFixture() {
        savedRepoLocal = System.getProperty("maven.repo.local");
        System.setProperty("maven.repo.local", mavenLocal.toString());
    }

    @AfterEach
    void restoreMavenLocalRepository() {
        if (savedRepoLocal == null) {
            System.clearProperty("maven.repo.local");
        } else {
            System.setProperty("maven.repo.local", savedRepoLocal);
        }
    }

    @Test
    void moduleCoordinate_isLocatedFromTheStagedRoot() throws IOException {
        jar(staged, MODULE, "");
        jar(mavenLocal, MODULE, "");

        var located = nodeRepositories().locate(MODULE).await();

        assertThat(located.isSuccess()).isTrue();
        assertThat(urlOf(located)).startsWith(staged.toUri().toString());
    }

    @Test
    void moduleCoordinate_withTheStagedFileRemoved_failsArtifactNotFound_althoughMavenLocalHoldsAJarUnderTheSameCoordinate() throws IOException {
        jar(mavenLocal, MODULE, "");

        var located = nodeRepositories().locate(MODULE).await();

        assertThat(located.isFailure()).as("a module coordinate must never be served from the Maven local repository").isTrue();
        located.onFailure(cause -> assertThat(cause).isInstanceOf(SliceLoadingFailure.Intermittent.ArtifactNotFound.class));
    }

    @Test
    void moduleCoordinate_classifierForm_alsoFailsClosed() throws IOException {
        jar(mavenLocal, MODULE, "-sources");

        var located = nodeRepositories().locate(MODULE, "sources").await();

        assertThat(located.isFailure()).as("the classifier form must route like the plain form").isTrue();
    }

    @Test
    void moduleCoordinate_classifierForm_isLocatedFromTheStagedRoot() throws IOException {
        jar(staged, MODULE, "-sources");

        var located = nodeRepositories().locate(MODULE, "sources").await();

        assertThat(located.isSuccess()).isTrue();
        assertThat(urlOf(located)).startsWith(staged.toUri().toString());
    }

    @Test
    void otherCoordinate_isLocatedFromTheMavenLocalRepository() throws IOException {
        jar(mavenLocal, FOREIGN, "");

        var located = nodeRepositories().locate(FOREIGN).await();

        assertThat(located.isSuccess()).isTrue();
        assertThat(urlOf(located)).startsWith(mavenLocal.toUri().toString());
    }

    @Test
    void otherCoordinate_isNeverLookedUpUnderTheStagedRoot() throws IOException {
        jar(staged, FOREIGN, "");

        var located = nodeRepositories().locate(FOREIGN).await();

        assertThat(located.isFailure()).isTrue();
    }

    private Repository nodeRepositories() {
        var factory = RepositoryFactory.repositoryFactory(mock(ArtifactStore.class));

        return CompositeRepository.compositeRepository(List.of(factory.create(RepositoryType.Staged.staged(staged, Set.of(MODULE)))));
    }

    private static String urlOf(Result<Location> located) {
        return located.fold(cause -> "failure: " + cause.message(),
                            location -> location.url().toString());
    }

    private static Artifact coordinate(String text) {
        return Artifact.artifact(text).unwrap();
    }

    private static void jar(Path root, Artifact artifact, String classifier) throws IOException {
        var version = artifact.version().withQualifier();
        var id = artifact.artifactId().id();
        var dir = root.resolve(artifact.groupId().id().replace('.', '/')).resolve(id).resolve(version);

        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + "-" + version + classifier + ".jar"), "planted under " + root);
    }
}
