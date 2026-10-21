// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.CircuitBreakerState;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.LastProvisionFailure;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.ProvisioningDecisionSnapshot;
import org.pragmatica.aether.deployment.membership.ntt.ReconcileTrigger;
import org.pragmatica.aether.node.ProvisioningDiagnostics;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// A wait for a complete inventory is not a provisioning failure: the provisioning status response gives it fields of its own.
class ClusterConfigRoutesProvisioningResponseTest {
    private static final ProvisioningDecisionSnapshot DECISION = new ProvisioningDecisionSnapshot(ReconcileTrigger.values()[0], 3, 3, 3, true, true, true, 0L, "NO_DEFICIT");
    private static final CircuitBreakerState BREAKER = new CircuitBreakerState(0, 3, 0L, false);

    @Test
    void anInventoryWait_isInventoryWaiting_andNotALastProvisionFailure() {
        var response = ClusterConfigRoutes.toProvisioningResponse(new ProvisioningDiagnostics(DECISION, BREAKER, Option.none(),
            Option.some(new LastProvisionFailure("Complete inventory is unavailable", 42L)), 7L));

        assertThat(response.inventoryWaiting()).isTrue();
        assertThat(response.inventoryWaits()).isEqualTo(7L);
        assertThat(response.lastInventoryWait().unwrap().cause()).isEqualTo("Complete inventory is unavailable");
        assertThat(response.lastInventoryWait().unwrap().atEpochMs()).isEqualTo(42L);
        assertThat(response.lastProvisionFailure().isPresent()).isFalse();
    }

    @Test
    void aResolvedWait_isNotWaiting_butStaysCounted() {
        var response = ClusterConfigRoutes.toProvisioningResponse(new ProvisioningDiagnostics(DECISION, BREAKER,
            Option.some(new LastProvisionFailure("boom", 1L)), Option.none(), 7L));

        assertThat(response.inventoryWaiting()).isFalse();
        assertThat(response.inventoryWaits()).isEqualTo(7L);
        assertThat(response.lastInventoryWait().isPresent()).isFalse();
        assertThat(response.lastProvisionFailure().unwrap().cause()).isEqualTo("boom");
    }
}
