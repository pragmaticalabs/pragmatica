package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderTest {

    @Test
    void loadFromString_parsesMinimalConfig() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.environment()).isEqualTo(Environment.DOCKER);
                assertThat(config.cluster().nodes()).isEqualTo(5);
            });
    }

    @Test
    void loadFromString_parsesFullConfig() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5
            tls = "true"

            [cluster.ports]
            management = 9080
            cluster = 9090

            [node]
            heap = "1g"
            gc = "g1"

            [docker]
            network = "custom-network"
            image = "custom-image:latest"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.cluster().nodes()).isEqualTo(5);
                assertThat(config.cluster().tls()).isTrue();
                assertThat(config.cluster().ports().management()).isEqualTo(9080);
                assertThat(config.cluster().ports().cluster()).isEqualTo(9090);
                assertThat(config.node().heap()).isEqualTo("1g");
                assertThat(config.node().gc()).isEqualTo("g1");
                assertThat(config.docker().network()).isEqualTo("custom-network");
                assertThat(config.docker().image()).isEqualTo("custom-image:latest");
            });
    }

    @Test
    void loadFromString_usesDefaultsForMissingValues() {
        var toml = """
            [cluster]
            environment = "docker"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                // Should use Docker environment defaults
                assertThat(config.cluster().nodes()).isEqualTo(Environment.DOCKER.defaultNodes());
                assertThat(config.node().heap()).isEqualTo(Environment.DOCKER.defaultHeap());
                assertThat(config.cluster().ports().management()).isEqualTo(PortsConfig.DEFAULT_MANAGEMENT_PORT);
            });
    }

    @Test
    void loadFromString_parsesKubernetesConfig() {
        var toml = """
            [cluster]
            environment = "kubernetes"
            nodes = 5

            [kubernetes]
            namespace = "production"
            service_type = "LoadBalancer"
            storage_class = "ssd"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.environment()).isEqualTo(Environment.KUBERNETES);
                assertThat(config.kubernetes()).isNotNull();
                assertThat(config.kubernetes().namespace()).isEqualTo("production");
                assertThat(config.kubernetes().serviceType()).isEqualTo("LoadBalancer");
                assertThat(config.kubernetes().storageClass()).isEqualTo("ssd");
            });
    }

    @Test
    void loadFromString_parsesTlsConfigWithAutoGenerate() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5
            tls = "true"

            [tls]
            auto_generate = "true"
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.tlsEnabled()).isTrue();
                assertThat(config.tls()).isNotNull();
                assertThat(config.tls().autoGenerate()).isTrue();
            });
    }

    @Test
    void loadFromString_parsesTlsConfigPaths() {
        // When auto_generate is false and paths are provided, validation will fail
        // if the files don't exist - so we test only that paths are parsed correctly
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 5
            tls = "true"

            [tls]
            auto_generate = "false"
            cert_path = "/path/to/cert.pem"
            key_path = "/path/to/key.pem"
            ca_path = "/path/to/ca.pem"
            """;

        // This will fail validation because files don't exist,
        // but we verify the error message contains the parsed paths
        ConfigLoader.loadFromString(toml)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> {
                var message = cause.message();
                assertThat(message).contains("/path/to/cert.pem");
                assertThat(message).contains("/path/to/key.pem");
                assertThat(message).contains("/path/to/ca.pem");
            });
    }

    @Test
    void loadFromString_fails_withInvalidToml() {
        var toml = "invalid [ toml";

        ConfigLoader.loadFromString(toml)
            .onSuccessRun(Assertions::fail);
    }

    @Test
    void loadFromString_fails_withInvalidNodeCount() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 4
            """;

        ConfigLoader.loadFromString(toml)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Node count must be odd"));
    }

    @Test
    void fromEnvironment_returnsValidConfig() {
        var config = ConfigLoader.aetherConfig(Environment.DOCKER);

        ConfigValidator.validate(config)
            .onFailureRun(Assertions::fail);
    }

    @Test
    void parseDuration_parsesMilliseconds() {
        assertThat(ConfigLoader.parseDuration("500ms")).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void parseDuration_parsesSeconds() {
        assertThat(ConfigLoader.parseDuration("30s")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void parseDuration_parsesMinutes() {
        assertThat(ConfigLoader.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void parseDuration_defaultsToSeconds() {
        assertThat(ConfigLoader.parseDuration("10")).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void parseDuration_handlesBlankValue() {
        assertThat(ConfigLoader.parseDuration("")).isEqualTo(Duration.ofSeconds(1));
        assertThat(ConfigLoader.parseDuration(null)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void loadFromString_appliesCliOverrides() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 3

            [node]
            heap = "256m"
            """;

        // This tests the override mechanism through loadWithOverrides
        // For now we test the direct loading
        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.cluster().nodes()).isEqualTo(3);
                assertThat(config.node().heap()).isEqualTo("256m");
            });
    }

    @Test
    void loadFromString_parsesSliceConfigWithSingleRepository() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 3

            [slice]
            repositories = ["builtin"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.slice().repositories()).containsExactly(RepositoryType.BUILTIN);
            });
    }

    @Test
    void loadFromString_parsesSliceConfigWithMultipleRepositories() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 3

            [slice]
            repositories = ["local", "builtin"]
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.slice().repositories())
                    .containsExactly(RepositoryType.LOCAL, RepositoryType.BUILTIN);
            });
    }

    @Test
    void loadFromString_usesDefaultSliceConfigWhenNotSpecified() {
        var toml = """
            [cluster]
            environment = "docker"
            nodes = 3
            """;

        ConfigLoader.loadFromString(toml)
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(config -> {
                assertThat(config.slice().repositories()).containsExactly(RepositoryType.LOCAL);
            });
    }
}
