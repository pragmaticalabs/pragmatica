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
class ClusterSizeGateTest {

    @Test
    void enforce_fails_whenZero() {
        ClusterSizeGate.enforce(0)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("a cluster is at least three nodes"));
    }

    @Test
    void enforce_fails_whenOne() {
        ClusterSizeGate.enforce(1)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("a cluster is at least three nodes"));
    }

    @Test
    void enforce_fails_whenTwo() {
        ClusterSizeGate.enforce(2)
            .onSuccessRun(Assertions::fail)
            .onFailure(cause -> assertThat(cause.message())
                .contains("a cluster is at least three nodes"));
    }

    @Test
    void enforce_succeeds_whenThree() {
        ClusterSizeGate.enforce(3)
            .onFailureRun(Assertions::fail);
    }

    @Test
    void enforce_succeeds_whenFour() {
        // Deliberately even and not in ConfigValidator.nodeCountErrors' {3,5,7} set — this gate
        // only enforces the minimum-of-three floor, not the separate odd-count quorum preference.
        ClusterSizeGate.enforce(4)
            .onFailureRun(Assertions::fail);
    }
}
