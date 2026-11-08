// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Wiring pins for four statements of the node assembly that no boot test reaches (F1 mutation survivors: deleting any one of them left the
/// 4,892-case `aether/node` run green). Following `ClusterSyncWiringTest`, each pin reads the assembly source with comments stripped and
/// whitespace removed and must match exactly once; it pins that the wiring expression is present, not that it works.
class NodeWiringSurvivorPinsTest {
    /// anode-AetherNode-04: the node binds the pong fan's DRAINING reports to the membership FSM and the deployment manager.
    @Test
    void node_bindsTheDrainReportToThePongFan() {
        assertThat(code()).containsOnlyOnce("DrainAssembly.bindDrainReport(pongSignalFan,membershipFsm,drainReportTarget);");
    }

    /// anode-DrainAssembly-00: the binding registers the listener on the pong fan.
    @Test
    void drainAssembly_registersTheDrainReportListenerOnThePongFan() {
        assertThat(code()).containsOnlyOnce("pongSignalFan.onDrainingReported(drainReportListener(membershipFsm,drainReportTarget));");
    }

    /// anode-ManagementAssembly-00: the management server's meter registry joins the registry the deployment wiring records into.
    @Test
    void managementAssembly_addsItsMeterRegistryToTheDeploymentMeters() {
        assertThat(code()).containsOnlyOnce("deploymentMeters.add(managementServer.meterRegistry());");
    }

    /// anode-WorkerHierarchyAssembly-00: a worker that becomes ready runs the passive-client activation after switching to forwarding.
    @Test
    void workerReady_runsThePassiveClientActivation_afterSwitchingToForwarding() {
        assertThat(code()).containsOnlyOnce("switchableCluster.switchTo(forwardingClusterNode);passiveClientActivation.run();");
    }

    /// B4 (EmberFsm2WorkerArmTest no longer pins this, #2842): the node binds B4 to the FSM2 system through the deployment wiring's passive-client switch.
    @Test
    void node_bindsB4ThroughThePassiveClientSwitch() {
        assertThat(code()).containsOnlyOnce("varpassiveClientActivation=deploymentWiring.passiveClientSwitch(()->reconcileNodeActivation(");
    }

    /// B4: the bound activation is the one handed to the worker assembly, not a substitute.
    @Test
    void node_handsTheB4BindingToTheWorkerAssembly() {
        assertThat(code()).containsOnlyOnce("controlLoop,passiveClientActivation,workerEndpointDirectory,");
    }

    private static String code() {
        return AssemblySource.stripped();
    }
}
