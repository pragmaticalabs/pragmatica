package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidatorTest {

    @Test
    void validate_succeeds_withValidConfig() {
        var config = AetherConfig.forEnvironment(Environment.DOCKER);

        ConfigValidator.validate(config)
            .onFailureRun(Assertions::fail);
    }

    @Test
    void validate_succeeds_withAllEnvironments() {
        for (var env : Environment.values()) {
            var config = AetherConfig.forEnvironment(env);

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for " + env + ": " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenNodeCountTooLow() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .nodes(1)
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Minimum 3 nodes required"));
    }

    @Test
    void validate_fails_whenNodeCountEven() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .nodes(4)
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Node count must be odd"));
    }

    @Test
    void validate_fails_whenNodeCountTooHigh() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .nodes(9)
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Maximum recommended node count is 7"));
    }

    @Test
    void validate_succeeds_withValidNodeCounts() {
        for (int nodes : new int[]{3, 5, 7}) {
            var config = AetherConfig.builder()
                .environment(Environment.DOCKER)
                .nodes(nodes)
                .build();

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for " + nodes + " nodes: " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenInvalidHeapFormat() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .heap("invalid")
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Invalid heap format"));
    }

    @Test
    void validate_succeeds_withValidHeapFormats() {
        for (String heap : new String[]{"256m", "512M", "1g", "2G", "4g"}) {
            var config = AetherConfig.builder()
                .environment(Environment.DOCKER)
                .heap(heap)
                .build();

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for heap " + heap + ": " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenInvalidGc() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .gc("invalid")
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Invalid GC"));
    }

    @Test
    void validate_succeeds_withValidGcOptions() {
        for (String gc : new String[]{"zgc", "ZGC", "g1", "G1"}) {
            var config = AetherConfig.builder()
                .environment(Environment.DOCKER)
                .gc(gc)
                .build();

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for gc " + gc + ": " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenPortsConflict() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .ports(new PortsConfig(8080, 8080))
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Management port and cluster port must be different"));
    }

    @Test
    void validate_fails_whenPortOutOfRange() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .ports(new PortsConfig(0, 8090))
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Management port must be between 1 and 65535"));
    }

    @Test
    void validate_fails_whenPortRangesOverlap() {
        // With 5 nodes: management 8080-8084 would overlap with cluster 8083-8087
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .nodes(5)
            .ports(new PortsConfig(8080, 8083))
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Port ranges overlap"));
    }

    @Test
    void validate_collectsMultipleErrors() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .nodes(2)
            .heap("bad")
            .gc("invalid")
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> {
                var message = cause.message();
                assertThat(message).contains("Minimum 3 nodes");
                assertThat(message).contains("Invalid heap format");
                assertThat(message).contains("Invalid GC");
            });
    }

    @Test
    void validate_fails_whenTlsEnabledWithoutCerts() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .tls(true)
            .tlsConfig(new TlsConfig(false, "", "", ""))
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> {
                var message = cause.message();
                assertThat(message).contains("TLS enabled but no certificate path provided");
                assertThat(message).contains("TLS enabled but no key path provided");
            });
    }

    @Test
    void validate_succeeds_whenTlsAutoGenerateEnabled() {
        var config = AetherConfig.builder()
            .environment(Environment.DOCKER)
            .tls(true)
            .tlsConfig(TlsConfig.autoGenerated())
            .build();

        ConfigValidator.validate(config)
            .onFailureRun(Assertions::fail);
    }
}
