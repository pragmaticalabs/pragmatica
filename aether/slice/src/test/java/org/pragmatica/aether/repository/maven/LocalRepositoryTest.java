package org.pragmatica.aether.repository.maven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.repository.maven.LocalRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRepositoryTest {

    @TempDir
    Path tempRepo;

    private LocalRepository repository;

    @BeforeEach
    void setUp() {
        repository = LocalRepository.localRepository(tempRepo);
    }

    @Test
    void locate_existingArtifact_returnsLocation() throws IOException {
        // Given: a JAR exists in the expected location
        var artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        var jarPath = createJarFile(artifact);

        // When: locating the artifact
        repository.locate(artifact)
                  .await()
                  .onFailure(cause -> Assertions.fail(cause.message()))
                  .onSuccess(location -> {
                      // Then: location points to the JAR
                      assertThat(location.artifact()).isEqualTo(artifact);
                      assertThat(location.url().getPath()).endsWith("test-slice-1.0.0.jar");
                  });
    }

    @Test
    void locate_nonExistentArtifact_returnsFailure() {
        // Given: artifact does not exist
        var artifact = Artifact.artifact("org.example:missing:1.0.0").unwrap();

        // When: locating the artifact
        repository.locate(artifact)
                  .await()
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> {
                      // Then: error message indicates artifact not found
                      assertThat(cause.message()).contains("Artifact not found");
                      assertThat(cause.message()).contains("org.example:missing:1.0.0");
                  });
    }

    @Test
    void locate_artifactWithQualifier_resolvesCorrectPath() throws IOException {
        // Given: a snapshot JAR exists
        var artifact = Artifact.artifact("org.example:test-slice:1.0.0-SNAPSHOT").unwrap();
        var jarPath = createJarFile(artifact);

        // When: locating the artifact
        repository.locate(artifact)
                  .await()
                  .onFailure(cause -> Assertions.fail(cause.message()))
                  .onSuccess(location -> {
                      // Then: location includes qualifier in filename
                      assertThat(location.url().getPath()).endsWith("test-slice-1.0.0-SNAPSHOT.jar");
                  });
    }

    @Test
    void locate_artifactWithDeepGroupId_resolvesCorrectPath() throws IOException {
        // Given: artifact with deep group ID
        var artifact = Artifact.artifact("org.pragmatica.aether.slice:example-slice:0.2.0").unwrap();
        var jarPath = createJarFile(artifact);

        // When: locating the artifact
        repository.locate(artifact)
                  .await()
                  .onFailure(cause -> Assertions.fail(cause.message()))
                  .onSuccess(location -> {
                      // Then: group ID is converted to path correctly
                      var urlPath = location.url().getPath();
                      assertThat(urlPath).contains("org/pragmatica/aether/slice");
                      assertThat(urlPath).endsWith("example-slice-0.2.0.jar");
                  });
    }

    @Test
    void defaultLocalRepository_detectsRepository() {
        // When: creating default repository
        var defaultRepo = LocalRepository.localRepository();

        // Then: it should be created successfully (we can't test content without real artifacts)
        assertThat(defaultRepo).isNotNull();
    }

    private Path createJarFile(Artifact artifact) throws IOException {
        var version = artifact.version();
        var artifactId = artifact.artifactId().id();
        var groupPath = artifact.groupId().id().replace('.', '/');

        var jarDir = tempRepo
                .resolve(groupPath)
                .resolve(artifactId)
                .resolve(version.withQualifier());

        Files.createDirectories(jarDir);

        var jarPath = jarDir.resolve(artifactId + "-" + version.withQualifier() + ".jar");
        Files.write(jarPath, new byte[0]); // Empty JAR file for testing

        return jarPath;
    }
}
