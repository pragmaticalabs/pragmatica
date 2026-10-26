// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// `[app-http.upload]`: the node-wide bounds on upload routes. Defaults when absent, every key parsed, a mistyped key
/// refused (not left at its default), and a non-positive bound refused by validation.
class UploadConfigTest {
    private static final String MINIMAL_CLUSTER = """
        [cluster]
        environment = "docker"
        nodes = 3
        """;

    private static AetherConfig load(String upload) {
        return ConfigLoader.loadFromString(MINIMAL_CLUSTER + "\n[app-http]\nenabled = \"true\"\n\n" + upload)
                           .onFailure(cause -> fail(cause.message()))
                           .unwrap();
    }

    @Test
    void absentSection_takesTheDefaults() {
        var upload = load("").appHttp().upload();

        assertThat(upload).isEqualTo(UploadConfig.DEFAULT);
        assertThat(upload.maxSizeCeiling()).as("256 MiB until streaming storage").isEqualTo(256L * 1024 * 1024);
        assertThat(upload.maxInFlightBytes()).as("a quarter of the maximum heap").isEqualTo(Runtime.getRuntime().maxMemory() / 4);
        assertThat(upload.maxConcurrent()).isEqualTo(64);
        assertThat(upload.maxConcurrentPerPrincipal()).isEqualTo(8);
        assertThat(upload.idleTimeout().millis()).isEqualTo(30_000);
        assertThat(upload.minBodyRate()).isEqualTo(1024);
    }

    @Test
    void theWindowAndTheDefaultDuration_areThirtySecondsAndSixHours() {
        assertThat(UploadConfig.RATE_WINDOW.millis()).isEqualTo(30_000);
        assertThat(UploadConfig.DEFAULT_MAX_DURATION.millis()).isEqualTo(6L * 60 * 60 * 1000);
    }

    @Test
    void everyKey_isParsed() {
        var upload = load("""
                          [app-http.upload]
                          max_size_ceiling = "2GB"
                          max_concurrent = 10
                          max_concurrent_per_principal = 3
                          idle_timeout = "45s"
                          min_body_rate = "4KB"
                          max_in_flight_size = "512MB"
                          """).appHttp().upload();

        assertThat(upload.maxSizeCeiling()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(upload.maxConcurrent()).isEqualTo(10);
        assertThat(upload.maxConcurrentPerPrincipal()).isEqualTo(3);
        assertThat(upload.idleTimeout().millis()).isEqualTo(45_000);
        assertThat(upload.minBodyRate()).isEqualTo(4096);
        assertThat(upload.maxInFlightBytes()).isEqualTo(512L * 1024 * 1024);
    }

    @Test
    void aMistypedKey_refusesTheLoad_namingItAndTheValidKeys() {
        var result = ConfigLoader.loadFromString(MINIMAL_CLUSTER + "\n[app-http]\nenabled = \"true\"\n\n[app-http.upload]\nmax_concurent = 8\n");

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("max_concurent")
                                                             .contains("max_concurrent")
                                                             .contains("max_size_ceiling"));
    }

    @Test
    void nonPositiveBounds_areRefusedByValidation_eachNamed() {
        var result = ConfigLoader.loadFromString(MINIMAL_CLUSTER + """

                                                 [app-http]
                                                 enabled = "true"

                                                 [app-http.upload]
                                                 max_size_ceiling = 0
                                                 max_concurrent = 0
                                                 max_concurrent_per_principal = 0
                                                 idle_timeout = "0s"
                                                 min_body_rate = 0
                                                 max_in_flight_size = 0
                                                 """);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("max_size_ceiling")
                                                             .contains("max_concurrent must")
                                                             .contains("max_concurrent_per_principal")
                                                             .contains("idle_timeout")
                                                             .contains("min_body_rate")
                                                             .contains("max_in_flight_size"));
    }

    @Test
    void defaultBounds_pass_validation() {
        assertThat(ConfigValidator.validate(load("")).isSuccess()).isTrue();
    }

    @Test
    void everyWith_changesItsOwnField_andKeepsTheOthers() {
        var base = UploadConfig.DEFAULT.withMaxInFlightBytes(7_000);

        assertThat(base.withMaxConcurrent(2).maxInFlightBytes()).isEqualTo(7_000);
        assertThat(base.withMaxConcurrentPerPrincipal(2).maxInFlightBytes()).isEqualTo(7_000);
        assertThat(base.withMaxSizeCeiling(2).maxInFlightBytes()).isEqualTo(7_000);
        assertThat(base.withIdleTimeout(base.idleTimeout()).maxInFlightBytes()).isEqualTo(7_000);
        assertThat(base.withMinBodyRate(2).maxInFlightBytes()).isEqualTo(7_000);
        assertThat(base.withMaxInFlightBytes(9).maxSizeCeiling()).isEqualTo(UploadConfig.DEFAULT_MAX_SIZE_CEILING);
        assertThat(base.withMaxInFlightBytes(9).maxConcurrent()).isEqualTo(UploadConfig.DEFAULT_MAX_CONCURRENT);
        assertThat(base.withMaxInFlightBytes(9).minBodyRate()).isEqualTo(UploadConfig.DEFAULT_MIN_BODY_RATE);
        assertThat(base.withMaxInFlightBytes(9).maxInFlightBytes()).isEqualTo(9);
    }
}
