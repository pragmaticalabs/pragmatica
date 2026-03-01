package org.pragmatica.aether.repository.maven;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.repository.maven.RemoteRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteRepositoryTest {

    @TempDir
    Path tempRepo;

    @BeforeEach
    void setUp() {
        System.setProperty("maven.repo.local", tempRepo.toString());
    }

    @Test
    void locate_cachedArtifact_returnsCacheHit() throws IOException {
        // Given: a JAR already exists in the local cache
        var artifact = Artifact.artifact("org.example:test-lib:1.0.0").unwrap();
        createCachedJar(artifact);

        // When: locating via remote repository (won't actually download since cached)
        var repository = RemoteRepository.remoteRepository("test", "https://localhost:19999/repo/");
        repository.locate(artifact)
                  .await()
                  .onFailure(cause -> Assertions.fail(cause.message()))
                  .onSuccess(location -> {
                      assertThat(location.artifact()).isEqualTo(artifact);
                      assertThat(location.url().getPath()).endsWith("test-lib-1.0.0.jar");
                  });
    }

    @Test
    void locate_nonCachedArtifact_failsWhenServerUnreachable() {
        // Given: no cached artifact and unreachable server
        var artifact = Artifact.artifact("org.example:missing:1.0.0").unwrap();

        // When: locating via remote repository pointing to unreachable server
        var repository = RemoteRepository.remoteRepository("test", "https://localhost:19999/repo/");
        repository.locate(artifact)
                  .await()
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause.message()).contains("Download failed"));
    }

    @Test
    void locate_cachedArtifactWithQualifier_resolvesCorrectPath() throws IOException {
        // Given: a snapshot JAR exists in the local cache
        var artifact = Artifact.artifact("org.example:test-lib:1.0.0-SNAPSHOT").unwrap();
        createCachedJar(artifact);

        var repository = RemoteRepository.remoteRepository("test", "https://localhost:19999/repo/");
        repository.locate(artifact)
                  .await()
                  .onFailure(cause -> Assertions.fail(cause.message()))
                  .onSuccess(location -> {
                      assertThat(location.url().getPath()).endsWith("test-lib-1.0.0-SNAPSHOT.jar");
                  });
    }

    @Test
    void remoteRepository_normalizesTrailingSlash() throws IOException {
        // Given: a cached artifact
        var artifact = Artifact.artifact("org.example:test-lib:1.0.0").unwrap();
        createCachedJar(artifact);

        // When: base URL has no trailing slash
        var repository = RemoteRepository.remoteRepository("test", "https://localhost:19999/repo");
        repository.locate(artifact)
                  .await()
                  .onFailure(cause -> Assertions.fail(cause.message()))
                  .onSuccess(location -> assertThat(location.artifact()).isEqualTo(artifact));
    }

    private void createCachedJar(Artifact artifact) throws IOException {
        var version = artifact.version();
        var artifactId = artifact.artifactId().id();
        var groupPath = artifact.groupId().id().replace('.', '/');

        var jarDir = tempRepo.resolve(groupPath)
                             .resolve(artifactId)
                             .resolve(version.withQualifier());

        Files.createDirectories(jarDir);
        var jarPath = jarDir.resolve(artifactId + "-" + version.withQualifier() + ".jar");
        Files.write(jarPath, new byte[]{0x50, 0x4B, 0x03, 0x04}); // ZIP magic bytes
    }
}
