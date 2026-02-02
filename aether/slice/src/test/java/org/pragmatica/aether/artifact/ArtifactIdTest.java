package org.pragmatica.aether.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactIdTest {

    @Test
    void artifactId_with_valid_lowercase_succeeds() {
        ArtifactId.artifactId("spring-boot")
                  .onSuccess(artifactId -> {
                      assertThat(artifactId.id()).isEqualTo("spring-boot");
                      assertThat(artifactId.toString()).isEqualTo("spring-boot");
                  })
                  .onFailureRun(Assertions::fail);
    }

    @Test
    void artifactId_with_valid_numbers_succeeds() {
        ArtifactId.artifactId("test123")
                  .onSuccess(artifactId -> assertThat(artifactId.id()).isEqualTo("test123"))
                  .onFailureRun(Assertions::fail);
    }

    @Test
    void artifactId_with_valid_mixed_alphanumeric_succeeds() {
        ArtifactId.artifactId("web-service-v2")
                  .onSuccess(artifactId -> assertThat(artifactId.id()).isEqualTo("web-service-v2"))
                  .onFailureRun(Assertions::fail);
    }

    @Test
    void artifactId_with_single_word_succeeds() {
        ArtifactId.artifactId("core")
                  .onSuccess(artifactId -> assertThat(artifactId.id()).isEqualTo("core"))
                  .onFailureRun(Assertions::fail);
    }

    @Test
    void artifactId_with_numbers_only_succeeds() {
        ArtifactId.artifactId("123")
                  .onSuccess(artifactId -> assertThat(artifactId.id()).isEqualTo("123"))
                  .onFailureRun(Assertions::fail);
    }

    @Test
    void artifactId_with_multiple_hyphens_succeeds() {
        ArtifactId.artifactId("my-web-service-api")
                  .onSuccess(artifactId -> assertThat(artifactId.id()).isEqualTo("my-web-service-api"))
                  .onFailureRun(Assertions::fail);
    }

    @Test
    void artifactId_with_uppercase_letters_fails() {
        ArtifactId.artifactId("Spring-Boot")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_underscore_fails() {
        ArtifactId.artifactId("spring_boot")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_dot_fails() {
        ArtifactId.artifactId("spring.boot")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_leading_hyphen_fails() {
        ArtifactId.artifactId("-spring-boot")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_trailing_hyphen_fails() {
        ArtifactId.artifactId("spring-boot-")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_double_hyphen_fails() {
        ArtifactId.artifactId("spring--boot")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_spaces_fails() {
        ArtifactId.artifactId("spring boot")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_special_characters_fails() {
        ArtifactId.artifactId("spring@boot")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_with_empty_string_fails() {
        ArtifactId.artifactId("")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void artifactId_equality_works() {
        ArtifactId.artifactId("test-lib")
                  .onSuccess(artifactId1 ->
                                     ArtifactId.artifactId("test-lib")
                                               .onSuccess(artifactId2 -> {
                                                   assertThat(artifactId1).isEqualTo(artifactId2);
                                                   assertThat(artifactId1.hashCode()).isEqualTo(artifactId2.hashCode());
                                               })
                                               .onFailureRun(Assertions::fail))
                  .onFailureRun(Assertions::fail);
    }

    @Test
    void artifactId_inequality_works() {
        ArtifactId.artifactId("test-lib")
                  .onSuccess(artifactId1 ->
                                     ArtifactId.artifactId("other-lib")
                                               .onSuccess(artifactId2 -> assertThat(artifactId1).isNotEqualTo(
                                                       artifactId2))
                                               .onFailureRun(Assertions::fail))
                  .onFailureRun(Assertions::fail);
    }
}