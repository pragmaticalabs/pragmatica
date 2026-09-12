// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class ConfigValidatorTest {

    @Test
    void validate_succeeds_withValidConfig() {
        var config = AetherConfig.aetherConfig(Environment.DOCKER);

        ConfigValidator.validate(config)
            .onFailureRun(Assertions::fail);
    }

    @Test
    void validate_succeeds_withAllEnvironments() {
        for (var env : Environment.values()) {
            var config = AetherConfig.aetherConfig(env);

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for " + env + ": " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenNodeCountTooLow() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .nodes(1)
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("below the supported minimum of 5")
                .contains("no fault budget during maintenance"));
    }

    @Test
    void validate_fails_whenNodeCountEven() {
        // 6, not 4: since the minimum rose to 5 an even 4 reports the MINIMUM error, so it could no
        // longer exercise the odd-count branch at all.
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .nodes(6)
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("which is even"));
    }

    @Test
    void validate_fails_whenNodeCountTooHigh() {
        // 11, not 9: the 2026-09-12 ruling makes 9 legal. The message must also say the bound is on
        // the CONSENSUS tier rather than the fleet, since the remedy is to add workers.
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .nodes(11)
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("above the maximum consensus tier of 9")
                .contains("add further capacity as workers"));
    }

    @Test
    void validate_succeeds_withValidNodeCounts() {
        for (int nodes : new int[]{5, 7, 9}) {
            var config = AetherConfig.builder()
                .withEnvironment(Environment.DOCKER)
                .nodes(nodes)
                .build();

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for " + nodes + " nodes: " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenInvalidHeapFormat() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
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
                .withEnvironment(Environment.DOCKER)
                .heap(heap)
                .build();

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for heap " + heap + ": " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenInvalidGc() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
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
                .withEnvironment(Environment.DOCKER)
                .gc(gc)
                .build();

            ConfigValidator.validate(config)
                .onFailure(cause -> Assertions.fail("Failed for gc " + gc + ": " + cause.message()));
        }
    }

    @Test
    void validate_fails_whenPortsConflict() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .ports(PortsConfig.portsConfig(8080, 8080).unwrap())
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Management port and cluster port must be different"));
    }

    @Test
    void validate_fails_whenPortOutOfRange() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .ports(PortsConfig.portsConfig(0, 8090).unwrap())
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
            .withEnvironment(Environment.DOCKER)
            .nodes(5)
            .ports(PortsConfig.portsConfig(8080, 8083).unwrap())
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Port ranges overlap"));
    }

    @Test
    void validate_collectsMultipleErrors() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .nodes(2)
            .heap("bad")
            .gc("invalid")
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> {
                var message = cause.message();
                assertThat(message).contains("below the supported minimum of 5");
                assertThat(message).contains("Invalid heap format");
                assertThat(message).contains("Invalid GC");
            });
    }

    @Test
    void validate_fails_whenTlsEnabledWithoutCerts() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .tls(true)
            .tlsConfig(TlsConfig.tlsConfig(false, "", "", "").unwrap())
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
            .withEnvironment(Environment.DOCKER)
            .tls(true)
            .tlsConfig(TlsConfig.tlsConfig())
            .build();

        ConfigValidator.validate(config)
            .onFailureRun(Assertions::fail);
    }

    /// #250 review: `StorageMaintenanceDriver` schedules on this interval unconditionally; a
    /// non-positive value must be rejected at validation time, not discovered at scheduler wiring.
    @Test
    void validate_fails_whenStorageMaintenanceIntervalNotPositive() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .timeouts(timeoutsWithStorageMaintenanceInterval(timeSpan(0).millis()))
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Storage maintenance interval must be positive"));
    }

    /// #250 review (round 2): positivity alone let 1ms through — a value technically legal but far
    /// below any interval a lifecycle-iterating pass could safely repeat on. A positive-but-below-floor
    /// interval must also be rejected, with a message distinct from the positivity check above.
    @Test
    void validate_fails_whenStorageMaintenanceIntervalBelowMinimum() {
        var config = AetherConfig.builder()
            .withEnvironment(Environment.DOCKER)
            .timeouts(timeoutsWithStorageMaintenanceInterval(timeSpan(1).millis()))
            .build();

        ConfigValidator.validate(config)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Storage maintenance interval must be at least"));
    }

    private static TimeoutsConfig timeoutsWithStorageMaintenanceInterval(TimeSpan interval) {
        var defaults = TimeoutsConfig.timeoutsConfig();

        return new TimeoutsConfig(defaults.invocation(), defaults.forwarding(), defaults.deployment(),
                                  defaults.rollingUpdate(), defaults.cluster(), defaults.consensus(),
                                  defaults.election(), defaults.swim(), defaults.observability(),
                                  defaults.dht(), defaults.worker(), defaults.security(),
                                  defaults.repository(), defaults.scaling(),
                                  new TimeoutsConfig.StorageMaintenanceTimeouts(interval));
    }
}
