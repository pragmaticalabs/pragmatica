package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactMapperTest {

    // === toArtifact Tests ===

    @Test
    void toArtifact_simple_class_name() {
        ArtifactMapper.toArtifact("org.example.UserService", "1.0.0")
                      .onSuccess(artifact -> {
                          assertThat(artifact.groupId().id()).isEqualTo("org.example");
                          assertThat(artifact.artifactId().id()).isEqualTo("user-service");
                          assertThat(artifact.version().withQualifier()).isEqualTo("1.0.0");
                      }).onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_multi_word_class_name() {
        ArtifactMapper.toArtifact("com.company.app.OrderProcessor", "2.0.0")
                      .onSuccess(artifact -> {
                          assertThat(artifact.groupId().id()).isEqualTo("com.company.app");
                          assertThat(artifact.artifactId().id()).isEqualTo("order-processor");
                          assertThat(artifact.version().withQualifier()).isEqualTo("2.0.0");
                      }).onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_single_word_class_name() {
        ArtifactMapper.toArtifact("org.example.Api", "1.0.0")
                      .onSuccess(artifact -> {
                          assertThat(artifact.groupId().id()).isEqualTo("org.example");
                          assertThat(artifact.artifactId().id()).isEqualTo("api");
                      }).onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_deep_package() {
        ArtifactMapper.toArtifact("org.pragmatica.aether.example.StringProcessorSlice", "0.2.0")
                      .onSuccess(artifact -> {
                          assertThat(artifact.groupId().id()).isEqualTo("org.pragmatica.aether.example");
                          assertThat(artifact.artifactId().id()).isEqualTo("string-processor-slice");
                      }).onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_with_version_qualifier() {
        ArtifactMapper.toArtifact("org.example.UserService", "1.0.0-beta")
                      .onSuccess(artifact -> {
                          assertThat(artifact.version().withQualifier()).isEqualTo("1.0.0-beta");
                      }).onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_rejects_no_package() {
        ArtifactMapper.toArtifact("UserService", "1.0.0")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid class name"));
    }

    @Test
    void toArtifact_rejects_empty_simple_name() {
        ArtifactMapper.toArtifact("org.example.", "1.0.0")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid class name"));
    }

    @Test
    void toArtifact_rejects_lowercase_simple_name() {
        ArtifactMapper.toArtifact("org.example.userService", "1.0.0")
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid class name"));
    }

    // === toClassName Tests ===

    @Test
    void toClassName_simple_artifact() {
        var artifact = Artifact.artifact("org.example:user-service:1.0.0").unwrap();
        var className = ArtifactMapper.toClassName(artifact);
        assertThat(className).isEqualTo("org.example.UserService");
    }

    @Test
    void toClassName_multi_word_artifact() {
        var artifact = Artifact.artifact("com.company.app:order-processor:2.0.0").unwrap();
        var className = ArtifactMapper.toClassName(artifact);
        assertThat(className).isEqualTo("com.company.app.OrderProcessor");
    }

    @Test
    void toClassName_single_word_artifact() {
        var artifact = Artifact.artifact("org.example:api:1.0.0").unwrap();
        var className = ArtifactMapper.toClassName(artifact);
        assertThat(className).isEqualTo("org.example.Api");
    }

    @Test
    void toClassName_deep_package() {
        var artifact = Artifact.artifact("org.pragmatica.aether.example:string-processor-slice:0.2.0").unwrap();
        var className = ArtifactMapper.toClassName(artifact);
        assertThat(className).isEqualTo("org.pragmatica.aether.example.StringProcessorSlice");
    }

    // === Roundtrip Tests ===

    @Test
    void roundtrip_className_to_artifact_to_className() {
        var originalClassName = "org.example.UserService";
        var version = "1.0.0";

        ArtifactMapper.toArtifact(originalClassName, version)
                      .map(ArtifactMapper::toClassName)
                      .onSuccess(resultClassName -> assertThat(resultClassName).isEqualTo(originalClassName))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void roundtrip_artifact_to_className_to_artifact() {
        var originalArtifact = Artifact.artifact("org.example:user-service:1.0.0").unwrap();

        var className = ArtifactMapper.toClassName(originalArtifact);
        ArtifactMapper.toArtifact(className, "1.0.0")
                      .onSuccess(resultArtifact -> {
                          assertThat(resultArtifact.groupId()).isEqualTo(originalArtifact.groupId());
                          assertThat(resultArtifact.artifactId()).isEqualTo(originalArtifact.artifactId());
                      }).onFailureRun(Assertions::fail);
    }

    @Test
    void roundtrip_complex_name() {
        var originalClassName = "com.example.service.PaymentProcessorService";
        var version = "3.2.1";

        ArtifactMapper.toArtifact(originalClassName, version)
                      .map(ArtifactMapper::toClassName)
                      .onSuccess(resultClassName -> assertThat(resultClassName).isEqualTo(originalClassName))
                      .onFailureRun(Assertions::fail);
    }

    // === Version Pattern Tests ===

    @Test
    void toArtifact_with_exact_version_pattern() {
        var pattern = VersionPattern.parse("1.2.3").unwrap();

        ArtifactMapper.toArtifact("org.example.UserService", pattern)
                      .onSuccess(artifact -> assertThat(artifact.version().withQualifier()).isEqualTo("1.2.3"))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_with_range_pattern_uses_lower_bound() {
        var pattern = VersionPattern.parse("[1.0.0,2.0.0)").unwrap();

        ArtifactMapper.toArtifact("org.example.UserService", pattern)
                      .onSuccess(artifact -> assertThat(artifact.version().withQualifier()).isEqualTo("1.0.0"))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_with_caret_pattern() {
        var pattern = VersionPattern.parse("^1.5.0").unwrap();

        ArtifactMapper.toArtifact("org.example.UserService", pattern)
                      .onSuccess(artifact -> assertThat(artifact.version().withQualifier()).isEqualTo("1.5.0"))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void toArtifact_with_tilde_pattern() {
        var pattern = VersionPattern.parse("~2.3.4").unwrap();

        ArtifactMapper.toArtifact("org.example.UserService", pattern)
                      .onSuccess(artifact -> assertThat(artifact.version().withQualifier()).isEqualTo("2.3.4"))
                      .onFailureRun(Assertions::fail);
    }

    // === Edge Cases ===

    @Test
    void kebab_case_handles_consecutive_uppercase() {
        // XMLParser → xml-parser
        ArtifactMapper.toArtifact("org.example.XMLParser", "1.0.0")
                      .onSuccess(artifact -> assertThat(artifact.artifactId().id()).isEqualTo("xml-parser"))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void kebab_case_handles_all_uppercase() {
        // API → api
        ArtifactMapper.toArtifact("org.example.API", "1.0.0")
                      .onSuccess(artifact -> assertThat(artifact.artifactId().id()).isEqualTo("api"))
                      .onFailureRun(Assertions::fail);
    }

    @Test
    void kebab_case_handles_uppercase_prefix() {
        // HTTPClient → http-client
        ArtifactMapper.toArtifact("org.example.HTTPClient", "1.0.0")
                      .onSuccess(artifact -> assertThat(artifact.artifactId().id()).isEqualTo("http-client"))
                      .onFailureRun(Assertions::fail);
    }
}
