package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BlueprintParser using TOML format.
 *
 * <p>Note: Routing is no longer parsed in blueprints - routes are self-registered
 * by slices during activation via RouteRegistry.
 */
class BlueprintParserTest {

    @Nested
    class SuccessCases {

        @Test
        void parse_succeeds_withCompleteValidDsl() {
            var dsl = """
                    id = "org.example:my-app:1.0.0"

                    [[slices]]
                    artifact = "org.example:user-service:1.0.0"
                    instances = 2

                    [[slices]]
                    artifact = "org.example:order-service:1.0.0"
                    instances = 3

                    [[slices]]
                    artifact = "org.example:payment-service:1.0.0"
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.id().asString()).isEqualTo("org.example:my-app:1.0.0");
                               assertThat(blueprint.slices()).hasSize(3);

                               // Find slices by artifact (order is not guaranteed in TOML)
                               var userService = blueprint.slices().stream()
                                   .filter(s -> s.artifact().asString().contains("user-service"))
                                   .findFirst().orElseThrow();
                               assertThat(userService.instances()).isEqualTo(2);

                               var orderService = blueprint.slices().stream()
                                   .filter(s -> s.artifact().asString().contains("order-service"))
                                   .findFirst().orElseThrow();
                               assertThat(orderService.instances()).isEqualTo(3);

                               var paymentService = blueprint.slices().stream()
                                   .filter(s -> s.artifact().asString().contains("payment-service"))
                                   .findFirst().orElseThrow();
                               assertThat(paymentService.instances()).isEqualTo(1);
                           });
        }

        @Test
        void parse_succeeds_withMinimalDsl() {
            var dsl = """
                    id = "org.example:minimal:1.0.0"

                    [[slices]]
                    artifact = "org.example:service:1.0.0"
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.id().asString()).isEqualTo("org.example:minimal:1.0.0");
                               assertThat(blueprint.slices()).hasSize(1);
                           });
        }

        @Test
        void parse_succeeds_withCommentsAndBlankLines() {
            var dsl = """
                    # Application blueprint
                    id = "org.example:with-comments:1.0.0"

                    # First service
                    [[slices]]
                    artifact = "org.example:service-a:1.0.0"
                    instances = 2  # Two instances

                    # Second service
                    [[slices]]
                    artifact = "org.example:service-b:1.0.0"
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.slices()).hasSize(2);

                               var serviceA = blueprint.slices().stream()
                                   .filter(s -> s.artifact().asString().contains("service-a"))
                                   .findFirst().orElseThrow();
                               assertThat(serviceA.instances()).isEqualTo(2);

                               var serviceB = blueprint.slices().stream()
                                   .filter(s -> s.artifact().asString().contains("service-b"))
                                   .findFirst().orElseThrow();
                               assertThat(serviceB.instances()).isEqualTo(1);
                           });
        }

        @Test
        void parse_succeeds_withQualifiedVersions() {
            var dsl = """
                    id = "org.example:qualified:1.0.0"

                    [[slices]]
                    artifact = "org.example:service:1.0.0-SNAPSHOT"
                    instances = 2

                    [[slices]]
                    artifact = "org.example:other:2.0.0-alpha1"
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.slices()).hasSize(2);

                               var service = blueprint.slices().stream()
                                   .filter(s -> s.artifact().asString().contains("service:1.0.0-SNAPSHOT"))
                                   .findFirst().orElseThrow();
                               assertThat(service.artifact().asString())
                                       .isEqualTo("org.example:service:1.0.0-SNAPSHOT");

                               var other = blueprint.slices().stream()
                                   .filter(s -> s.artifact().asString().contains("other"))
                                   .findFirst().orElseThrow();
                               assertThat(other.artifact().asString())
                                       .isEqualTo("org.example:other:2.0.0-alpha1");
                           });
        }

        @Test
        void parseFile_succeeds_withValidFile() throws IOException {
            var tempFile = Files.createTempFile("blueprint", ".toml");
            try {
                var dsl = """
                        id = "org.example:from-file:1.0.0"

                        [[slices]]
                        artifact = "org.example:service:1.0.0"
                        """;
                Files.writeString(tempFile, dsl);

                BlueprintParser.parseFile(tempFile)
                               .onFailureRun(Assertions::fail)
                               .onSuccess(blueprint -> {
                                   assertThat(blueprint.id().asString()).isEqualTo("org.example:from-file:1.0.0");
                                   assertThat(blueprint.slices()).hasSize(1);
                               });
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        void parse_ignoresUnknownSections() {
            var dsl = """
                    id = "org.example:with-unknown:1.0.0"

                    [[slices]]
                    artifact = "org.example:service:1.0.0"

                    [metadata]
                    author = "test"
                    version = "1.0"
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.id().asString()).isEqualTo("org.example:with-unknown:1.0.0");
                               assertThat(blueprint.slices()).hasSize(1);
                               // Metadata section is ignored
                           });
        }

        @Test
        void parse_fails_withNoSlices() {
            var dsl = """
                    id = "org.example:empty:1.0.0"
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause -> assertThat(cause.message()).contains("empty"));
        }
    }

    @Nested
    class FailureCases {

        @Test
        void parse_fails_whenMissingId() {
            var dsl = """
                    [[slices]]
                    artifact = "org.example:service:1.0.0"
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Missing blueprint id")
                                     );
        }

        @Test
        void parse_fails_whenInvalidBlueprintId() {
            var dsl = """
                    id = "INVALID_ID"

                    [[slices]]
                    artifact = "org.example:service:1.0.0"
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Invalid")
                                     );
        }

        @Test
        void parse_fails_whenMissingArtifact() {
            var dsl = """
                    id = "org.example:test:1.0.0"

                    [[slices]]
                    instances = 2
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Missing artifact")
                                     );
        }

        @Test
        void parse_fails_whenInvalidArtifactFormat() {
            var dsl = """
                    id = "org.example:test:1.0.0"

                    [[slices]]
                    artifact = "not-a-valid-artifact"
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Invalid artifact")
                                     );
        }

        @Test
        void parse_fails_whenInvalidInstanceCount() {
            var dsl = """
                    id = "org.example:test:1.0.0"

                    [[slices]]
                    artifact = "org.example:service:1.0.0"
                    instances = -1
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("positive")
                                     );
        }

        @Test
        void parseFile_fails_whenFileDoesNotExist() {
            var nonExistentFile = Path.of("/nonexistent/blueprint.toml");

            BlueprintParser.parseFile(nonExistentFile)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Failed to read file")
                                     );
        }

        @Test
        void parse_fails_whenEmptyContent() {
            BlueprintParser.parse("")
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Missing blueprint id")
                                     );
        }

        @Test
        void parse_fails_whenNullContent() {
            BlueprintParser.parse(null)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Missing blueprint id")
                                     );
        }
    }
}
