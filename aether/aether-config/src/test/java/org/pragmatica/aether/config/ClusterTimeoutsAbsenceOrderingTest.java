// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.TimeoutsConfig.ClusterTimeouts;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The #590 ordering invariant, pinned both as a predicate and through the validator that reports it.
///
/// `core_absence` is when a worker stops serving because it has lost the core; `community_absence` is
/// when the core stops counting that worker and re-places its slices. If the second fired first, the
/// core would hand a community's work to other nodes while the original ones were still running it —
/// two live writers on one slice set, the exact failure the mechanism exists to prevent. The gap
/// between them is the hand-off margin.
class ClusterTimeoutsAbsenceOrderingTest {

    @Test
    void absenceWindowsOrdered_defaults_fenceStrictlyBeforeTheCoreReplaces() {
        var defaults = ClusterTimeouts.clusterTimeouts();

        assertTrue(defaults.absenceWindowsOrdered(),
                   "core_absence (%s) must be strictly less than community_absence (%s)".formatted(defaults.coreAbsence(),
                                                                                                    defaults.communityAbsence()));
    }

    /// Both windows are multiples of the ping cadence — a threshold below the interval at which the
    /// evidence arrives would fence on a single missed ping.
    @Test
    void clusterTimeouts_defaults_bothWindowsExceedThePingInterval() {
        var defaults = ClusterTimeouts.clusterTimeouts();

        assertTrue(defaults.coreAbsence().nanos() > defaults.pingInterval().nanos());
        assertTrue(defaults.communityAbsence().nanos() > defaults.pingInterval().nanos());
    }

    @Test
    void absenceWindowsOrdered_inverted_isFalse() {
        assertFalse(windows(timeSpan(30).seconds(), timeSpan(20).seconds()).absenceWindowsOrdered());
    }

    /// Equal windows are unordered too. "Strictly less" is the requirement: with equal deadlines the
    /// two sides race, and which one wins is a scheduling accident.
    @Test
    void absenceWindowsOrdered_equalWindows_isFalse() {
        assertFalse(windows(timeSpan(20).seconds(), timeSpan(20).seconds()).absenceWindowsOrdered());
    }

    /// The predicate being right is worth nothing if nobody consults it. This is the wiring proof:
    /// an inverted pair must come back as a validation FAILURE naming both keys, not merely as a
    /// `false` some caller could ignore.
    @Test
    void validate_invertedAbsenceWindows_failsNamingBothKeys() {
        ConfigValidator.validate(configWith(windows(timeSpan(30).seconds(), timeSpan(20).seconds())))
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertThat(cause.message()).contains("timeouts.cluster.core_absence")
                                                                      .contains("timeouts.cluster.community_absence"));
    }

    @Test
    void validate_equalAbsenceWindows_fails() {
        ConfigValidator.validate(configWith(windows(timeSpan(20).seconds(), timeSpan(20).seconds())))
                       .onSuccessRun(Assertions::fail);
    }

    /// The other side of the wiring proof: a correctly ordered pair must not be reported. Without
    /// this, a validator that rejected everything would pass the test above.
    @Test
    void validate_defaultAbsenceWindows_succeeds() {
        ConfigValidator.validate(AetherConfig.aetherConfig(Environment.DOCKER))
                       .onFailureRun(Assertions::fail);
    }

    private static ClusterTimeouts windows(TimeSpan coreAbsence, TimeSpan communityAbsence) {
        var defaults = ClusterTimeouts.clusterTimeouts();

        return new ClusterTimeouts(defaults.hello(),
                                   defaults.reconciliationInterval(),
                                   defaults.pingInterval(),
                                   defaults.channelProtection(),
                                   coreAbsence,
                                   communityAbsence);
    }

    private static AetherConfig configWith(ClusterTimeouts cluster) {
        var base = AetherConfig.aetherConfig(Environment.DOCKER);
        var t = base.timeouts();
        var timeouts = new TimeoutsConfig(t.invocation(),
                                          t.forwarding(),
                                          t.deployment(),
                                          t.rollingUpdate(),
                                          cluster,
                                          t.consensus(),
                                          t.election(),
                                          t.swim(),
                                          t.observability(),
                                          t.dht(),
                                          t.worker(),
                                          t.security(),
                                          t.repository(),
                                          t.scaling(),
                                          t.storageMaintenance());

        return new AetherConfig(base.cluster(),
                                base.node(),
                                base.tls(),
                                base.docker(),
                                base.kubernetes(),
                                base.ttm(),
                                base.slice(),
                                base.appHttp(),
                                base.backup(),
                                base.dhtReplication(),
                                timeouts,
                                base.storage(),
                                base.cloud(),
                                base.endpoints(),
                                base.streaming(),
                                base.membership(),
                                base.storageEncryption());
    }
}
