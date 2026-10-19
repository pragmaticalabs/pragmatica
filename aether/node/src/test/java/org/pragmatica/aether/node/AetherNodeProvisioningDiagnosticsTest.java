// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.CircuitBreakerState;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.LastProvisionFailure;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.ProvisioningDecisionSnapshot;
import org.pragmatica.aether.deployment.membership.ntt.ReconcileTrigger;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// What the node hands the provisioning endpoint is what the manager reports: the circuit, the last failure and, separately, the
/// current inventory wait and the number of waits.
class AetherNodeProvisioningDiagnosticsTest {
    private static final ProvisioningDecisionSnapshot DECISION = new ProvisioningDecisionSnapshot(ReconcileTrigger.values()[0], 3, 3, 3, true, true, true, 0L, "NO_DEFICIT");
    private static final CircuitBreakerState BREAKER = new CircuitBreakerState(2, 3, 0L, false);

    private static ClusterTopologyManager manager(Option<LastProvisionFailure> failure, Option<LastProvisionFailure> wait, long waits) {
        return (ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                               new Class<?>[]{ClusterTopologyManager.class},
                                                               (_, method, _) -> switch (method.getName()) {
                                                                   case "circuitBreakerState" -> BREAKER;
                                                                   case "lastProvisionFailure" -> failure;
                                                                   case "inventoryWait" -> wait;
                                                                   case "inventoryWaits" -> waits;
                                                                   default -> throw new UnsupportedOperationException(method.getName());
                                                               });
    }

    @Test
    void provisioningDiagnosticsOf_carriesTheManagersWaitAndFailureSeparately() {
        var failure = new LastProvisionFailure("boom", 1L);
        var wait = new LastProvisionFailure("Complete inventory is unavailable", 2L);

        var diagnostics = AetherNode.provisioningDiagnosticsOf(DECISION, manager(Option.some(failure), Option.some(wait), 9L));

        assertThat(diagnostics.decision()).isSameAs(DECISION);
        assertThat(diagnostics.circuitBreaker()).isEqualTo(BREAKER);
        assertThat(diagnostics.lastProvisionFailure()).isEqualTo(Option.some(failure));
        assertThat(diagnostics.inventoryWait()).isEqualTo(Option.some(wait));
        assertThat(diagnostics.inventoryWaits()).isEqualTo(9L);
    }

    @Test
    void provisioningDiagnosticsOf_withoutAWait_carriesNone() {
        var diagnostics = AetherNode.provisioningDiagnosticsOf(DECISION, manager(Option.none(), Option.none(), 0L));

        assertThat(diagnostics.inventoryWait().isPresent()).isFalse();
        assertThat(diagnostics.lastProvisionFailure().isPresent()).isFalse();
    }
}
