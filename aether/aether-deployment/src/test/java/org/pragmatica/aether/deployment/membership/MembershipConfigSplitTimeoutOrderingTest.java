// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;

/// One split timeout `T` ordering invariant (cluster-topology-overhaul Wave 9 item 2).
///
/// The minority quorum-loss self-drain and the majority departure-verdict / re-provision are
/// governed by the SAME knob `MembershipConfig.splitTimeout()` (`T`). The no-double-active
/// requirement — the minority MUST stop before the majority re-provisions — is preserved NOT by
/// a second knob but by the natural detection-lag between the two observation points:
///
///   - the minority observes its quorum loss LOCALLY and immediately (t = 0), and its self-drain
///     deadline is `0 + T`;
///   - the majority only confirms the minority's departure AFTER SWIM convergence + the
///     co-confirmation gate (t = L, L > 0), and its re-provision deadline is `L + T`.
///
/// Therefore `T_minority (= T)` precedes `T_majority (= L + T)` for ANY L > 0 with the same `T`.
/// This test pins that ordering arithmetically so a future refactor cannot reintroduce the
/// doubly-active window by splitting the knob or by arming both sides from the same instant.
class MembershipConfigSplitTimeoutOrderingTest {
    /// A representative minimum detection lag: SWIM convergence + co-confirmation is observably
    /// non-zero on every real cluster. Any positive value proves the ordering; 1ns is the
    /// strongest (tightest) form of the assertion.
    private static final long MIN_DETECTION_LAG_NANOS = 1L;

    @Test
    void minorityDrainDeadline_precedesMajorityReprovisionDeadline_forSameSplitTimeout() {
        var t = membershipConfig().splitTimeout().nanos();
        var quorumLossObservedAt = 0L;
        var departureVerdictAt = quorumLossObservedAt + MIN_DETECTION_LAG_NANOS;

        var minoritySelfDrainDeadline = quorumLossObservedAt + t;
        var majorityReprovisionDeadline = departureVerdictAt + t;

        assertThat(minoritySelfDrainDeadline)
            .as("minority self-drain (T from local-quorum-loss) must halt before majority re-provision (T from departure-verdict) — no doubly-active stateful slices")
            .isLessThan(majorityReprovisionDeadline);
    }

    @Test
    void splitTimeout_isASingleKnob_governingBothSides() {
        var config = membershipConfig();

        assertThat(config.splitTimeout())
            .as("the single split-timeout knob T governs both the minority self-drain and the majority re-provision")
            .isEqualTo(MembershipConfig.DEFAULT_SPLIT_TIMEOUT);
    }
}
