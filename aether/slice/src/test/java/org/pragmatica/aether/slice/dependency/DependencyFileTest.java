package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyFileTest {

    @Test
    void parse_full_file_with_all_sections() {
        var content = """
                # Comment line

                [shared]
                org.pragmatica-lite:core:^0.8.0
                org.example:order-domain:^1.0.0

                [infra]
                org.pragmatica-lite.aether:infra-cache:^0.7.0

                [slices]
                org.example:notification-service:^1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).hasSize(2);
                          assertThat(file.infra()).hasSize(1);
                          assertThat(file.slices()).hasSize(1);

                          assertThat(file.hasSharedDependencies()).isTrue();
                          assertThat(file.hasInfraDependencies()).isTrue();
                          assertThat(file.hasSliceDependencies()).isTrue();
                      });
    }

    @Test
    void parse_file_with_shared_and_slices_sections() {
        var content = """
                # Comment line

                [shared]
                org.pragmatica-lite:core:^0.8.0
                org.example:order-domain:^1.0.0

                [slices]
                org.example:inventory-service:^1.0.0
                org.example:pricing-service:^1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).hasSize(2);
                          assertThat(file.slices()).hasSize(2);

                          assertThat(file.shared().get(0).groupId()).isEqualTo("org.pragmatica-lite");
                          assertThat(file.shared().get(0).artifactId()).isEqualTo("core");

                          assertThat(file.slices().get(0).groupId()).isEqualTo("org.example");
                          assertThat(file.slices().get(0).artifactId()).isEqualTo("inventory-service");
                      });
    }

    @Test
    void parse_only_shared_section() {
        var content = """
                [shared]
                org.pragmatica-lite:core:^0.8.0
                org.example:order-domain:1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).hasSize(2);
                          assertThat(file.slices()).isEmpty();
                          assertThat(file.hasSharedDependencies()).isTrue();
                          assertThat(file.hasSliceDependencies()).isFalse();
                      });
    }

    @Test
    void parse_only_slices_section() {
        var content = """
                [slices]
                org.example:inventory-service:^1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).isEmpty();
                          assertThat(file.slices()).hasSize(1);
                          assertThat(file.hasSharedDependencies()).isFalse();
                          assertThat(file.hasSliceDependencies()).isTrue();
                      });
    }

    @Test
    void parse_backward_compatible_no_sections() {
        var content = """
                # Dependencies without section headers (legacy format)
                org.example:inventory-service:1.0.0
                org.example:pricing-service:1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          // Lines without section are treated as slice dependencies
                          assertThat(file.shared()).isEmpty();
                          assertThat(file.slices()).hasSize(2);
                      });
    }

    @Test
    void parse_empty_file() {
        var content = "";

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.isEmpty()).isTrue();
                          assertThat(file.shared()).isEmpty();
                          assertThat(file.infra()).isEmpty();
                          assertThat(file.slices()).isEmpty();
                      });
    }

    @Test
    void parse_comments_only() {
        var content = """
                # This is a comment
                # Another comment
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> assertThat(file.isEmpty()).isTrue());
    }

    @Test
    void parse_unknown_section_returns_failure() {
        var content = """
                [unknown]
                org.example:something:1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Unknown section"));
    }

    @Test
    void parse_invalid_dependency_returns_failure() {
        var content = """
                [shared]
                invalid-format-no-version
                """;

        DependencyFile.dependencyFile(content)
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));
    }

    @Test
    void parse_mixed_valid_and_empty_lines() {
        var content = """
                [shared]

                org.example:lib1:1.0.0

                # comment
                org.example:lib2:2.0.0

                [slices]

                org.example:slice1:1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).hasSize(2);
                          assertThat(file.slices()).hasSize(1);
                      });
    }

    @Test
    void parse_sections_can_appear_in_any_order() {
        var content = """
                [slices]
                org.example:slice1:1.0.0

                [shared]
                org.example:lib1:1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).hasSize(1);
                          assertThat(file.slices()).hasSize(1);
                      });
    }

    @Test
    void parse_infra_section() {
        var content = """
                [infra]
                org.pragmatica-lite.aether:infra-cache:^0.7.0
                org.pragmatica-lite.aether:infra-database:^0.7.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).isEmpty();
                          assertThat(file.infra()).hasSize(2);
                          assertThat(file.slices()).isEmpty();
                          assertThat(file.hasInfraDependencies()).isTrue();

                          assertThat(file.infra().get(0).groupId()).isEqualTo("org.pragmatica-lite.aether");
                          assertThat(file.infra().get(0).artifactId()).isEqualTo("infra-cache");
                          assertThat(file.infra().get(1).artifactId()).isEqualTo("infra-database");
                      });
    }

    @Test
    void parse_full_file_with_infra_section() {
        var content = """
                [shared]
                org.pragmatica-lite:core:^0.8.0

                [infra]
                org.pragmatica-lite.aether:infra-cache:^0.7.0

                [slices]
                org.example:notification-service:^1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).hasSize(1);
                          assertThat(file.infra()).hasSize(1);
                          assertThat(file.slices()).hasSize(1);
                          assertThat(file.isEmpty()).isFalse();
                          assertThat(file.hasSharedDependencies()).isTrue();
                          assertThat(file.hasInfraDependencies()).isTrue();
                          assertThat(file.hasSliceDependencies()).isTrue();
                      });
    }

    @Test
    void parse_with_version_patterns() {
        var content = """
                [shared]
                org.example:exact:1.0.0
                org.example:caret:^1.0.0
                org.example:tilde:~1.0.0
                org.example:range:[1.0.0,2.0.0)
                org.example:comparison:>=1.0.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> {
                          assertThat(file.shared()).hasSize(5);
                          assertThat(file.shared().get(0).versionPattern()).isInstanceOf(VersionPattern.Exact.class);
                          assertThat(file.shared().get(1).versionPattern()).isInstanceOf(VersionPattern.Caret.class);
                          assertThat(file.shared().get(2).versionPattern()).isInstanceOf(VersionPattern.Tilde.class);
                          assertThat(file.shared().get(3).versionPattern()).isInstanceOf(VersionPattern.Range.class);
                          assertThat(file.shared().get(4).versionPattern()).isInstanceOf(VersionPattern.Comparison.class);
                      });
    }

    @Test
    void parse_rejects_slice_api_in_shared_section() {
        var content = """
                [shared]
                org.pragmatica-lite.aether:slice-api:^0.7.0
                """;

        DependencyFile.dependencyFile(content)
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> {
                          assertThat(cause.message()).contains("Slice incorrectly packaged");
                          assertThat(cause.message()).contains("[shared]");
                      });
    }

    @Test
    void parse_rejects_infra_api_in_infra_section() {
        var content = """
                [infra]
                org.pragmatica-lite.aether:infra-api:^0.7.0
                """;

        DependencyFile.dependencyFile(content)
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> {
                          assertThat(cause.message()).contains("Slice incorrectly packaged");
                          assertThat(cause.message()).contains("[infra]");
                          assertThat(cause.message()).contains("infra-api");
                      });
    }

    @Test
    void parse_rejects_infra_api_in_shared_section() {
        var content = """
                [shared]
                org.pragmatica-lite.aether:infra-api:^0.7.0
                """;

        DependencyFile.dependencyFile(content)
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> {
                          assertThat(cause.message()).contains("Slice incorrectly packaged");
                          assertThat(cause.message()).contains("[shared]");
                      });
    }

    @Test
    void parse_rejects_slice_annotations_in_shared_section() {
        var content = """
                [shared]
                org.pragmatica-lite.aether:slice-annotations:^0.7.0
                """;

        DependencyFile.dependencyFile(content)
                      .onSuccessRun(Assertions::fail)
                      .onFailure(cause -> {
                          assertThat(cause.message()).contains("Slice incorrectly packaged");
                          assertThat(cause.message()).contains("[shared]");
                          assertThat(cause.message()).contains("slice-annotations");
                      });
    }

    @Test
    void parse_allows_other_aether_dependencies() {
        var content = """
                [infra]
                org.pragmatica-lite.aether:infra-cache:^0.7.0
                org.pragmatica-lite.aether:infra-database:^0.7.0
                """;

        DependencyFile.dependencyFile(content)
                      .onFailureRun(Assertions::fail)
                      .onSuccess(file -> assertThat(file.infra()).hasSize(2));
    }
}
