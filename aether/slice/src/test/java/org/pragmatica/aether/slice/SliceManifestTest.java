package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class SliceManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void read_validManifest_returnsSliceInfo() throws IOException {
        // Given: a JAR with valid slice manifest
        var jarFile = createJarWithManifest(
                "org.example:test-slice:1.0.0",
                "org.example.TestSlice"
                                           );

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(info -> {
                         // Then: manifest info is extracted correctly
                         assertThat(info.artifact().asString()).isEqualTo("org.example:test-slice:1.0.0");
                         assertThat(info.sliceClassName()).isEqualTo("org.example.TestSlice");
                         assertThat(info.envelopeVersion()).isEqualTo(Option.none());
                     });
    }

    @Test
    void read_missingSliceArtifact_returnsError() throws IOException {
        // Given: a JAR without Slice-Artifact attribute
        var jarFile = createJarWithPartialManifest(null, "org.example.TestSlice", null);

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> {
                         // Then: error indicates missing attribute
                         assertThat(cause.message()).contains("Slice-Artifact");
                     });
    }

    @Test
    void read_missingSliceClass_returnsError() throws IOException {
        // Given: a JAR without Slice-Class attribute
        var jarFile = createJarWithPartialManifest("org.example:test-slice:1.0.0", null, null);

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> {
                         // Then: error indicates missing attribute
                         assertThat(cause.message()).contains("Slice-Class");
                     });
    }

    @Test
    void read_invalidArtifactFormat_returnsError() throws IOException {
        // Given: a JAR with invalid artifact format
        var jarFile = createJarWithManifest("invalid-artifact", "org.example.TestSlice");

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> {
                         // Then: error indicates parsing failure
                         assertThat(cause.message()).isNotEmpty();
                     });
    }

    @Test
    void read_artifactWithQualifier_parsesCorrectly() throws IOException {
        // Given: a JAR with qualified version
        var jarFile = createJarWithManifest(
                "org.example:test-slice:1.0.0-SNAPSHOT",
                "org.example.TestSlice"
                                           );

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(info -> {
                         // Then: qualifier is preserved
                         assertThat(info.artifact().version().qualifier()).isEqualTo("SNAPSHOT");
                         assertThat(info.artifact().version().withQualifier()).isEqualTo("1.0.0-SNAPSHOT");
                     });
    }

    @Test
    void read_deepGroupId_parsesCorrectly() throws IOException {
        // Given: a JAR with deep group ID
        var jarFile = createJarWithManifest(
                "org.pragmatica.aether.example:string-processor:0.2.0",
                "org.pragmatica.aether.example.StringProcessor"
                                           );

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(info -> {
                         // Then: deep group ID is preserved
                         assertThat(info.artifact().groupId().id()).isEqualTo("org.pragmatica.aether.example");
                         assertThat(info.artifact().artifactId().id()).isEqualTo("string-processor");
                     });
    }

    @Test
    void read_nonExistentJar_returnsError() {
        // Given: a non-existent JAR path
        var nonExistent = tempDir.resolve("non-existent.jar");

        // When: reading the manifest
        try {
            SliceManifest.read(nonExistent.toUri().toURL())
                         .onSuccessRun(Assertions::fail)
                         .onFailure(cause -> {
                             // Then: error indicates file issue
                             assertThat(cause.message()).isNotEmpty();
                         });
        } catch (Exception e) {
            // Expected - file doesn't exist
        }
    }

    @Test
    void read_envelopeVersionPresent_extractsVersion() throws IOException {
        // Given: a JAR with Envelope-Version in manifest
        var jarFile = createJarWithPartialManifest(
                "org.example:test-slice:1.0.0",
                "org.example.TestSlice",
                "1"
                                                  );

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(info -> {
                         // Then: envelope version is extracted
                         assertThat(info.envelopeVersion()).isEqualTo(Option.some("1"));
                     });
    }

    @Test
    void read_envelopeVersionMissing_returnsNone() throws IOException {
        // Given: a JAR without Envelope-Version attribute
        var jarFile = createJarWithPartialManifest(
                "org.example:test-slice:1.0.0",
                "org.example.TestSlice",
                null
                                                  );

        // When: reading the manifest
        SliceManifest.read(jarFile.toUri().toURL())
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(info -> {
                         // Then: envelope version is none
                         assertThat(info.envelopeVersion()).isEqualTo(Option.none());
                     });
    }

    @Nested
    class EnvelopeCompatibilityTests {

        @Test
        void checkEnvelopeCompatibility_supportedVersion_succeeds() {
            SliceManifest.checkEnvelopeCompatibility(Option.some("1"))
                         .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void checkEnvelopeCompatibility_missingVersion_succeeds() {
            SliceManifest.checkEnvelopeCompatibility(Option.none())
                         .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void checkEnvelopeCompatibility_devVersion_succeeds() {
            SliceManifest.checkEnvelopeCompatibility(Option.some("dev"))
                         .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void checkEnvelopeCompatibility_unsupportedVersion_fails() {
            SliceManifest.checkEnvelopeCompatibility(Option.some("99"))
                         .onSuccessRun(Assertions::fail)
                         .onFailure(cause -> assertThat(cause.message()).contains("99")
                                                                        .contains("not supported"));
        }
    }

    private Path createJarWithManifest(String artifact, String sliceClass) throws IOException {
        return createJarWithPartialManifest(artifact, sliceClass, null);
    }

    private Path createJarWithPartialManifest(String artifact, String sliceClass, String envelopeVersion) throws IOException {
        var manifest = new Manifest();
        var mainAttrs = manifest.getMainAttributes();
        mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");

        if (artifact != null) {
            mainAttrs.putValue(SliceManifest.SLICE_ARTIFACT_ATTR, artifact);
        }
        if (sliceClass != null) {
            mainAttrs.putValue(SliceManifest.SLICE_CLASS_ATTR, sliceClass);
        }
        if (envelopeVersion != null) {
            mainAttrs.putValue(SliceManifest.ENVELOPE_VERSION_ATTR, envelopeVersion);
        }

        var jarPath = tempDir.resolve("test-slice-" + System.nanoTime() + ".jar");

        try (var jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()), manifest)) {
            // Empty JAR with just manifest
        }

        return jarPath;
    }
}
