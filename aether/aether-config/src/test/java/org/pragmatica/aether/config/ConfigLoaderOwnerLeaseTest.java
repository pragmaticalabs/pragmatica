// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 step 4: the `[streaming]` owner lease keys, their defaults and the validator bounds `R < T`,
/// `clock_drift_bound` in `[1e-3, 1)` and `T + W < suspect_timeout`. Each bound has a case that turns red
/// when the bound is removed or shifted by one step.
class ConfigLoaderOwnerLeaseTest {
    private static final String HEADER = """
        [cluster]
        environment = "docker"
        nodes = 3

        """;

    private static String streaming(String keys, String suspect) {
        return HEADER + "[streaming]\n" + keys + "\n[timeouts.swim]\nsuspect_timeout = \"" + suspect + "\"\n";
    }

    private static void assertAccepted(String keys, String suspect) {
        ConfigLoader.loadFromString(streaming(keys, suspect))
                    .onFailure(cause -> Assertions.fail(cause.message()));
    }

    private static void assertRefused(String keys, String suspect, String... mentions) {
        ConfigLoader.loadFromString(streaming(keys, suspect))
                    .onSuccess(config -> Assertions.fail("must be refused: " + keys + " suspect " + suspect))
                    .onFailure(cause -> assertThat(cause.message()).contains(mentions));
    }

    @Test
    void defaults_arePinned_andAbsentKeysTakeThem() {
        assertThat(StreamingConfig.DEFAULT_OWNER_LEASE.millis()).isEqualTo(3_000L);
        assertThat(StreamingConfig.DEFAULT_OWNER_LEASE_RENEWAL.millis()).isEqualTo(1_000L);
        assertThat(StreamingConfig.DEFAULT_CLOCK_DRIFT_BOUND).isEqualTo(0.1);
        assertThat(StreamingConfig.MIN_CLOCK_DRIFT_BOUND).isEqualTo(1e-3);

        ConfigLoader.loadFromString(HEADER + "[streaming]\nreshuffle_concurrency = 2\n")
                    .onFailure(cause -> Assertions.fail(cause.message()))
                    .onSuccess(config -> assertThat(config.streaming()).isEqualTo(StreamingConfig.streamingConfig()));
        assertThat(StreamingConfig.streamingConfig().ownerLease()).isEqualTo(StreamingConfig.DEFAULT_OWNER_LEASE);
        assertThat(StreamingConfig.streamingConfig().ownerLeaseRenewal()).isEqualTo(StreamingConfig.DEFAULT_OWNER_LEASE_RENEWAL);
        assertThat(StreamingConfig.streamingConfig().clockDriftBound()).isEqualTo(StreamingConfig.DEFAULT_CLOCK_DRIFT_BOUND);
    }

    /// The design's `W = T (1 + rho) / (1 - rho) + 50 ms`, about 3.72 s at the defaults.
    @Test
    void ownerLeaseWait_isTheDriftPaddedLeasePlusMargin() {
        assertThat(StreamingConfig.streamingConfig().ownerLeaseWait().millis()).isEqualTo(3_716L);
        assertThat(StreamingConfig.streamingConfig()
                                  .withOwnerLease(StreamingConfig.DEFAULT_OWNER_LEASE, StreamingConfig.DEFAULT_OWNER_LEASE_RENEWAL, 0.5)
                                  .ownerLeaseWait()
                                  .millis()).isEqualTo(9_050L);
    }

    @Test
    void keys_areParsed() {
        ConfigLoader.loadFromString(streaming("""
                                              owner_lease = "2s"
                                              owner_lease_renewal = "500ms"
                                              clock_drift_bound = 0.05
                                              """,
                                              "10s"))
                    .onFailure(cause -> Assertions.fail(cause.message()))
                    .onSuccess(config -> {
                        assertThat(config.streaming().ownerLease().millis()).isEqualTo(2_000L);
                        assertThat(config.streaming().ownerLeaseRenewal().millis()).isEqualTo(500L);
                        assertThat(config.streaming().clockDriftBound()).isEqualTo(0.05);
                    });
    }

    @Test
    void renewalNotBelowTheLease_isRefused() {
        assertRefused("owner_lease = \"3s\"\nowner_lease_renewal = \"3s\"", "10s", "owner_lease_renewal", "owner_lease");
        assertRefused("owner_lease = \"3s\"\nowner_lease_renewal = \"4s\"", "10s", "owner_lease_renewal");
        assertAccepted("owner_lease = \"3s\"\nowner_lease_renewal = \"2999ms\"", "10s");
    }

    @Test
    void clockDriftBound_belowTheFloorOrAtOne_isRefused() {
        assertRefused("clock_drift_bound = 0.0009", "10s", "clock_drift_bound");
        assertRefused("clock_drift_bound = 1.0", "10s", "clock_drift_bound");
        assertRefused("clock_drift_bound = 1.5", "10s", "clock_drift_bound");
        assertRefused("clock_drift_bound = -0.1", "10s", "clock_drift_bound");
        assertAccepted("clock_drift_bound = 0.001", "10s");
    }

    /// rho = 0.5 makes W = 3 T + 50 ms exact in binary floating point, so with T = 2 s the bound is exactly 8050 ms and the
    /// strict inequality is pinned from both sides.
    @Test
    void leasePlusWait_mustBeBelowTheSuspectTimeout_strictly() {
        var keys = "owner_lease = \"2s\"\nowner_lease_renewal = \"1s\"\nclock_drift_bound = 0.5";

        assertRefused(keys, "8049ms", "owner_lease", "suspect_timeout");
        assertRefused(keys, "8050ms", "owner_lease", "suspect_timeout");
        assertAccepted(keys, "8051ms");
    }

    @Test
    void defaults_fitInsideTheDefaultSuspectTimeout() {
        assertAccepted("reshuffle_concurrency = 2", "10s");
    }
}
