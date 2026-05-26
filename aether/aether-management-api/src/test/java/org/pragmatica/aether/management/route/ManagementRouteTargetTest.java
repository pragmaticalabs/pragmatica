// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies that leader-bound management routes are declared with `RouteTarget.LEADER` rather
/// than `taskGroup(...)`. The CTM-driven scaling path is leader-gated; routing it via the
/// SCALING task group caused requests to be silently dropped on the SCALING owner when it
/// was not the leader.
class ManagementRouteTargetTest {

    @Test
    void clusterScale_routesToLeaderTarget() {
        assertThat(ManagementRoute.CLUSTER_SCALE.target())
                .as("CLUSTER_SCALE must dispatch to the leader — CTM.onClusterConfigChanged is leader-gated")
                .isEqualTo(RouteTarget.LEADER);
    }
}
