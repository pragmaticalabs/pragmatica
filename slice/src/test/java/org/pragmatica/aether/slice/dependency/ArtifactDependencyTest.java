package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactDependencyTest {

    @Test
    void parse_exact_version() {
        ArtifactDependency.parse("org.example:order-domain:1.0.0")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(dep -> {
                              assertThat(dep.groupId()).isEqualTo("org.example");
                              assertThat(dep.artifactId()).isEqualTo("order-domain");
                              assertThat(dep.versionPattern()).isInstanceOf(VersionPattern.Exact.class);
                          });
    }

    @Test
    void parse_caret_version() {
        ArtifactDependency.parse("org.pragmatica-lite:core:^0.8.0")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(dep -> {
                              assertThat(dep.groupId()).isEqualTo("org.pragmatica-lite");
                              assertThat(dep.artifactId()).isEqualTo("core");
                              assertThat(dep.versionPattern()).isInstanceOf(VersionPattern.Caret.class);
                          });
    }

    @Test
    void parse_tilde_version() {
        ArtifactDependency.parse("com.fasterxml.jackson.core:jackson-databind:~2.15.0")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(dep -> {
                              assertThat(dep.groupId()).isEqualTo("com.fasterxml.jackson.core");
                              assertThat(dep.artifactId()).isEqualTo("jackson-databind");
                              assertThat(dep.versionPattern()).isInstanceOf(VersionPattern.Tilde.class);
                          });
    }

    @Test
    void parse_range_version() {
        ArtifactDependency.parse("org.example:my-lib:[1.0.0,2.0.0)")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(dep -> {
                              assertThat(dep.groupId()).isEqualTo("org.example");
                              assertThat(dep.artifactId()).isEqualTo("my-lib");
                              assertThat(dep.versionPattern()).isInstanceOf(VersionPattern.Range.class);
                          });
    }

    @Test
    void parse_comparison_version() {
        ArtifactDependency.parse("org.example:service:>=1.5.0")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(dep -> {
                              assertThat(dep.groupId()).isEqualTo("org.example");
                              assertThat(dep.artifactId()).isEqualTo("service");
                              assertThat(dep.versionPattern()).isInstanceOf(VersionPattern.Comparison.class);
                          });
    }

    @Test
    void parse_empty_line_returns_failure() {
        ArtifactDependency.parse("")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("empty"));

        ArtifactDependency.parse("   ")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("empty"));
    }

    @Test
    void parse_comment_returns_failure() {
        ArtifactDependency.parse("# This is a comment")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("comment"));
    }

    @Test
    void parse_section_header_returns_failure() {
        ArtifactDependency.parse("[shared]")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("section header"));
    }

    @Test
    void parse_invalid_format_returns_failure() {
        // Missing version
        ArtifactDependency.parse("org.example:order-domain")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));

        // Only one part
        ArtifactDependency.parse("just-a-name")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));
    }

    @Test
    void parse_empty_group_id_returns_failure() {
        ArtifactDependency.parse(":artifact:1.0.0")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));
    }

    @Test
    void parse_empty_artifact_id_returns_failure() {
        ArtifactDependency.parse("org.example::1.0.0")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("Empty artifact ID"));
    }

    @Test
    void parse_empty_version_returns_failure() {
        ArtifactDependency.parse("org.example:artifact:")
                          .onSuccessRun(Assertions::fail)
                          .onFailure(cause -> assertThat(cause.message()).contains("Empty version"));
    }

    @Test
    void asString_roundtrip() {
        var original = "org.example:order-domain:^1.0.0";
        ArtifactDependency.parse(original)
                          .map(ArtifactDependency::asString)
                          .onFailureRun(Assertions::fail)
                          .onSuccess(result -> assertThat(result).isEqualTo(original));
    }

    @Test
    void artifactKey_returns_groupId_artifactId() {
        ArtifactDependency.parse("org.example:order-domain:1.0.0")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(dep -> assertThat(dep.artifactKey()).isEqualTo("org.example:order-domain"));
    }

    @Test
    void parse_handles_whitespace() {
        ArtifactDependency.parse("  org.example : order-domain : 1.0.0  ")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(dep -> {
                              assertThat(dep.groupId()).isEqualTo("org.example");
                              assertThat(dep.artifactId()).isEqualTo("order-domain");
                          });
    }
}
