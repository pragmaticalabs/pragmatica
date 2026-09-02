// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression coverage for the `waitForDrainComplete` sibling found by the #522 sweep.
///
/// The gate previously searched the whole `/api/nodes/lifecycle/{id}` body for the token
/// `DECOMMISSIONED`; it now reads the `state` field. `WaveExecutor` gates rolling-upgrade
/// waves on this, so a body-wide match could let a wave restart a node that is still
/// shedding traffic.
class ClusterHttpClientDrainStateTest {
    @Test
    void isDecommissioned_stateIsDecommissioned_returnsTrue() {
        assertTrue(ClusterHttpClient.isDecommissioned(
                Result.success("{\"nodeId\":\"core-2\",\"state\":\"DECOMMISSIONED\",\"updatedAt\":0}")));
    }

    @Test
    void isDecommissioned_stateIsDraining_returnsFalse() {
        assertFalse(ClusterHttpClient.isDecommissioned(
                Result.success("{\"nodeId\":\"core-2\",\"state\":\"DRAINING\",\"updatedAt\":0}")));
    }

    @Test
    void isDecommissioned_tokenPresentOutsideStateField_returnsFalse() {
        // The exact false-success the substring reading allowed: the node is still DRAINING
        // while another field mentions the target state.
        assertFalse(ClusterHttpClient.isDecommissioned(
                Result.success("{\"nodeId\":\"core-2\",\"state\":\"DRAINING\","
                               + "\"message\":\"transition to DECOMMISSIONED pending\",\"updatedAt\":0}")),
                    "a node still draining must never be reported as decommissioned");
    }

    @Test
    void isDecommissioned_errorEnvelopeNamingTheState_returnsFalse() {
        assertFalse(ClusterHttpClient.isDecommissioned(
                Result.success("{\"error\":\"node is not DECOMMISSIONED\"}")));
    }

    @Test
    void isDecommissioned_failedRequest_returnsFalse() {
        assertFalse(ClusterHttpClient.isDecommissioned(Causes.cause("connection refused").result()));
    }

    @Test
    void isDecommissioned_malformedBody_returnsFalse() {
        assertFalse(ClusterHttpClient.isDecommissioned(Result.success("not json")));
    }
}
