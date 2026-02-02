package org.pragmatica.aether.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactTest {

    @Test
    void artifact_parsing_with_valid_coordinates() {
        Artifact.artifact("com.example:test-lib:1.0.0")
                .onFailureRun(Assertions::fail)
                .onSuccess(artifact -> {
                    assertThat(artifact.groupId().id()).isEqualTo("com.example");
                    assertThat(artifact.artifactId().id()).isEqualTo("test-lib");
                    assertThat(artifact.version().major()).isEqualTo(1);
                    assertThat(artifact.version().minor()).isEqualTo(0);
                    assertThat(artifact.version().patch()).isEqualTo(0);
                    assertThat(artifact.version().qualifier()).isEmpty();
                });
    }

    @Test
    void artifact_parsing_with_version_qualifier() {
        Artifact.artifact("org.springframework:spring-core:5.3.21-RELEASE")
                .onFailureRun(Assertions::fail).onSuccess(artifact -> {
                    assertThat(artifact.groupId().id()).isEqualTo("org.springframework");
                    assertThat(artifact.artifactId().id()).isEqualTo("spring-core");
                    assertThat(artifact.version().major()).isEqualTo(5);
                    assertThat(artifact.version().minor()).isEqualTo(3);
                    assertThat(artifact.version().patch()).isEqualTo(21);
                    assertThat(artifact.version().qualifier()).isEqualTo("RELEASE");
                });
    }

    @Test
    void artifact_parsing_with_complex_group_id() {
        Artifact.artifact("com.fasterxml.jackson.core:jackson-databind:2.13.0")
                .onFailureRun(Assertions::fail).onSuccess(artifact -> {
                    assertThat(artifact.groupId().id()).isEqualTo("com.fasterxml.jackson.core");
                    assertThat(artifact.artifactId().id()).isEqualTo("jackson-databind");
                    assertThat(artifact.version().bareVersion()).isEqualTo("2.13.0");
                });
    }

    @Test
    void artifact_parsing_rejects_invalid_format() {
        Artifact.artifact("invalid-format")
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).isNotEmpty());

        Artifact.artifact("group:artifact")
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).isNotEmpty());

        Artifact.artifact("group:artifact:version:extra")
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).isNotEmpty());

        Artifact.artifact("")
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> assertThat(cause.message()).isNotEmpty());
    }

    @Test
    void artifact_parsing_rejects_invalid_components() {
        // Invalid group ID
        Artifact.artifact("invalid group:artifact:1.0.0")
                .onSuccessRun(Assertions::fail);

        // Invalid artifact ID
        Artifact.artifact("com.example:invalid artifact:1.0.0")
                .onSuccessRun(Assertions::fail);

        // Invalid version
        Artifact.artifact("com.example:artifact:invalid.version")
                .onSuccessRun(Assertions::fail);
    }

    @Test
    void artifact_parsing_handles_simple_cases() {
        // Simple valid case that should work
        Artifact.artifact("com.example:artifact:1.0.0")
                .onFailureRun(Assertions::fail);
    }

    @Test
    void artifact_to_string_and_as_string_work_correctly() {
        Result.all(GroupId.groupId("com.example"),
                   ArtifactId.artifactId("test-lib"),
                   Version.version(2, 1, 0, Option.option("SNAPSHOT")))
              .map(Artifact::artifact)
              .map(Artifact::asString)
              .onSuccess(artifact -> assertThat(artifact).isEqualTo("com.example:test-lib:2.1.0-SNAPSHOT"))
              .onFailureRun(Assertions::fail);
    }

    @Test
    void artifact_parsing_roundtrip_consistency() {
        var originalString = "org.pragmatica:aether-slice:1.2.3";

        Artifact.artifact(originalString)
                .map(Artifact::asString)
                .onSuccess(artifact -> assertThat(artifact).isEqualTo(originalString))
                .onFailureRun(Assertions::fail);
    }
}