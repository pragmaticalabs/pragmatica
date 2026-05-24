// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the pure φ-accrual algorithm in [`PhiAccrualDetector`] (Spike-1, issue #231;
/// design §5E). A monotonic logical clock is advanced explicitly so suspicion behavior is
/// fully deterministic — no wall-clock dependence.
class PhiAccrualDetectorTest {
    private static final NodeId PEER = new NodeId("peer");
    private static final NodeId LOW_JITTER = new NodeId("low-jitter");
    private static final NodeId HIGH_JITTER = new NodeId("high-jitter");

    private static final PhiAccrualConfig CONFIG = PhiAccrualConfig.DEFAULT;
    private static final long INTERVAL_MS = 1_000L;

    /// Feed `count` heartbeats spaced `INTERVAL_MS` apart starting at `startMs`; returns the
    /// timestamp of the last heartbeat.
    private static long feedSteady(PhiAccrualDetector detector, NodeId peer, long startMs, int count) {
        var now = startMs;
        for (var i = 0; i < count; i++) {
            detector.heartbeat(peer, now);
            now += INTERVAL_MS;
        }
        return now - INTERVAL_MS;
    }

    @Test
    void phi_unknownPeer_isZero() {
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);

        assertThat(detector.phi(PEER, 5_000L)).isZero();
    }

    @Test
    void phi_duringWarmup_isZero() {
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);
        // minSamples=8 → need 9 heartbeats to produce 8 intervals; feed only 8 (7 intervals).
        var last = feedSteady(detector, PEER, 0L, CONFIG.minSamples());

        assertThat(detector.phi(PEER, last)).isZero();
    }

    @Test
    void phi_steadyHeartbeatsRightAfterArrival_isSmall() {
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);
        var last = feedSteady(detector, PEER, 0L, 30);

        // Queried at the arrival instant (elapsed = 0, below mean) → near-zero suspicion.
        assertThat(detector.phi(PEER, last)).isLessThan(0.5);
    }

    @Test
    void phi_increasesMonotonically_asSilenceGrows() {
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);
        var last = feedSteady(detector, PEER, 0L, 30);

        // Sample within the unsaturated band (just past μ≈1000ms, before the -log10 floor caps
        // φ at 9.0); the σ floor of 50ms makes φ climb steeply here.
        var phiShort = detector.phi(PEER, last + 1_050L);
        var phiMedium = detector.phi(PEER, last + 1_100L);
        var phiLong = detector.phi(PEER, last + 1_150L);

        assertThat(phiShort).isLessThan(phiMedium);
        assertThat(phiMedium).isLessThan(phiLong);
    }

    @Test
    void suspected_afterSufficientSilence_crossesThreshold() {
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);
        var last = feedSteady(detector, PEER, 0L, 30);

        // Just after a heartbeat: not suspected.
        assertThat(detector.suspected(PEER, last)).isFalse();
        // Long silence (elapsed ≫ μ ≈ 1000ms): φ rises past Φ_evict=8.
        assertThat(detector.suspected(PEER, last + 60_000L)).isTrue();
    }

    @Test
    void phi_highJitterWindow_toleratesLongerSilenceThanLowJitter() {
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);
        // Both windows share mean ≈ 1000ms; only variance differs.
        // Low-jitter: constant-ish 1000ms intervals (small σ → σ floor dominates).
        var lowLast = feedSteady(detector, LOW_JITTER, 0L, 30);
        // High-jitter: alternating 500/1500ms intervals, same 1000ms mean, large σ.
        var highLast = feedAlternating(detector, HIGH_JITTER, 0L, 30, 500L, 1_500L);

        // Query both at the SAME elapsed silence (200ms past the shared 1000ms mean), in the
        // band where the low-jitter φ has risen well above zero but not yet hit the cap.
        var elapsed = 1_200L;
        var phiLow = detector.phi(LOW_JITTER, lowLast + elapsed);
        var phiHigh = detector.phi(HIGH_JITTER, highLast + elapsed);

        // High variance → the same silence is less surprising → lower φ. So low-jitter is
        // suspected sooner: phi(lowJitter) > phi(highJitter).
        assertThat(phiLow).isGreaterThan(phiHigh);
    }

    @Test
    void phi_constantIntervals_sigmaFloorPreventsRunaway() {
        // Perfectly constant intervals → raw σ = 0; without the floor φ would explode the
        // instant elapsed exceeds the mean. The σ floor keeps it bounded for modest silence.
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);
        var last = feedSteady(detector, PEER, 0L, 30);

        var phi = detector.phi(PEER, last + 1_100L);

        assertThat(phi).isFinite();
        assertThat(phi).isLessThan(CONFIG.threshold());
    }

    @Test
    void forget_resetsPeerToZeroPhi() {
        var detector = PhiAccrualDetector.phiAccrualDetector(CONFIG);
        var last = feedSteady(detector, PEER, 0L, 30);
        // Confirm it had risen above zero before forgetting.
        assertThat(detector.phi(PEER, last + 5_000L)).isGreaterThan(0.0);

        detector.forget(PEER);

        assertThat(detector.phi(PEER, last + 5_000L)).isZero();
    }

    private static long feedAlternating(PhiAccrualDetector detector,
                                        NodeId peer,
                                        long startMs,
                                        int count,
                                        long shortMs,
                                        long longMs) {
        var now = startMs;
        detector.heartbeat(peer, now);
        for (var i = 0; i < count; i++) {
            now += (i % 2 == 0) ? shortMs : longMs;
            detector.heartbeat(peer, now);
        }
        return now;
    }
}
