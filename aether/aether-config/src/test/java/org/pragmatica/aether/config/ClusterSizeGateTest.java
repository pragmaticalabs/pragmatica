// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// #782 — boot-time gate on the CONFIGURED expected cluster size (Main#expectedClusterSize),
// independent of ConfigValidator's declarative [cluster] nodes TOML check. See ClusterSizeGate#enforce.
// Call-site arithmetic (static vs. discovery, configured vs. resolved) is pinned in
// aether/node's MainClusterSizeTest, not here — this file only exercises the pure function.
//
// #1019 / owner ruling 2026-09-12 — the floor moved from 3 to 5. The previous enforce(3) and
// enforce(4) success cases were CORRECT for the policy of their day; they are superseded, not wrong.
// A 3-node cluster tolerates zero failures during maintenance.
class ClusterSizeGateTest {

    @Test
    void enforce_fails_whenZero() {
        ClusterSizeGate.enforce(0)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("below the supported minimum of 5"));
    }

    @Test
    void enforce_fails_whenTwo() {
        ClusterSizeGate.enforce(2)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("below the supported minimum of 5"));
    }

    // The old minimum. Refused now: a rolling restart leaves 2 of 3 and any further fault
    // loses quorum, so a 3-node cluster spends planned maintenance with no fault budget.
    @Test
    void enforce_fails_whenThree() {
        ClusterSizeGate.enforce(3)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("below the supported minimum of 5"));
    }

    @Test
    void enforce_fails_whenFour() {
        ClusterSizeGate.enforce(4)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("below the supported minimum of 5"));
    }

    @Test
    void enforce_succeeds_whenFive() {
        ClusterSizeGate.enforce(5)
            .onFailureRun(Assertions::fail);
    }

    @Test
    void enforce_succeeds_whenSeven() {
        ClusterSizeGate.enforce(7)
            .onFailureRun(Assertions::fail);
    }

    // Deliberately even and not in ConfigValidator.nodeCountErrors' {5,7,9} set — this gate only
    // enforces the minimum floor, not the separate odd-count quorum requirement.
    @Test
    void enforce_succeeds_whenSix() {
        ClusterSizeGate.enforce(6)
            .onFailureRun(Assertions::fail);
    }

    // The message is the operator's only signal at a refused boot, so it is pinned rather than
    // left to drift: it must name the configured size, the minimum, the reason, and the remedy.
    @Test
    void enforce_failureMessage_namesSizeMinimumReasonAndRemedy() {
        ClusterSizeGate.enforce(3)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("Expected cluster size 3")
                .contains("supported minimum of 5")
                .contains("no fault budget during maintenance")
                .contains("scale it BEFORE upgrading"));
    }
}
