// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// U2 keys of `[app-http.upload]`: `streaming_ingest` (off by default, know 74c6c0810a: nothing reclaims a stored upload
/// until A4) and `unclaimed_ttl` (24 h by default, recorded as the stored object's expiry).
class UploadConfigStreamingTest {
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
    void absentKeys_streamingIsOff_andTheTtlIsADay() {
        var upload = load("").appHttp()
                             .upload();

        assertThat(upload.streamingIngest()).as("off by default until A4 (know 74c6c0810a)")
                                            .isFalse();
        assertThat(UploadConfig.DEFAULT.streamingIngest()).isFalse();
        assertThat(upload.unclaimedTtl()
                         .millis()).isEqualTo(24L * 60 * 60 * 1000);
    }

    @Test
    void bothKeys_areParsed() {
        var upload = load("""
                          [app-http.upload]
                          streaming_ingest = true
                          unclaimed_ttl = "2h"
                          """).appHttp()
                              .upload();

        assertThat(upload.streamingIngest()).isTrue();
        assertThat(upload.unclaimedTtl()
                         .millis()).isEqualTo(2L * 60 * 60 * 1000);
        assertThat(upload.maxConcurrent()).as("the other keys keep their defaults")
                                          .isEqualTo(UploadConfig.DEFAULT_MAX_CONCURRENT);
    }

    @Test
    void aMistypedStreamingKey_refusesTheLoad_namingTheValidKeys() {
        var result = ConfigLoader.loadFromString(MINIMAL_CLUSTER
                                                 + "\n[app-http]\nenabled = \"true\"\n\n[app-http.upload]\nstreaming_ingst = true\n");

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("streaming_ingst")
                                                             .contains("streaming_ingest")
                                                             .contains("unclaimed_ttl"));
    }

    @Test
    void aZeroTtl_isRefusedByValidation() {
        var result = ConfigLoader.loadFromString(MINIMAL_CLUSTER + """

                                                 [app-http]
                                                 enabled = "true"

                                                 [app-http.upload]
                                                 unclaimed_ttl = "0s"
                                                 """);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("unclaimed_ttl"));
    }

    @Test
    void theNewWiths_changeTheirOwnField_andKeepTheOthers() {
        var base = UploadConfig.DEFAULT.withMaxInFlightBytes(7_000);
        var streaming = base.withStreamingIngest(true);
        var ttl = base.withUnclaimedTtl(TimeSpan.timeSpan(5).minutes());

        assertThat(streaming.streamingIngest()).isTrue();
        assertThat(streaming.maxInFlightBytes()).isEqualTo(7_000);
        assertThat(ttl.unclaimedTtl()
                      .millis()).isEqualTo(300_000);
        assertThat(ttl.maxInFlightBytes()).isEqualTo(7_000);
        assertThat(base.withMaxConcurrent(2)
                       .withStreamingIngest(true)
                       .withMinBodyRate(3)
                       .streamingIngest()).as("the U1 withs carry the new fields")
                                          .isTrue();
        assertThat(ttl.withIdleTimeout(TimeSpan.timeSpan(1).seconds())
                      .unclaimedTtl()).isEqualTo(TimeSpan.timeSpan(5).minutes());
    }
}
