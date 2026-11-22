// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.node.assembly.DrainAssembly;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;

/// C6a-0 assembly pin (#3467, A1 and A2; C6-W0 #3413): the DRAIN channel the management server takes from the quorum-loss unit.
/// The channel is a seam of its own (`DrainAssembly.drainPort`), pinned here on both halves. That the management server
/// holds this very port is pinned at runtime by [NodeDrainPortIdentityBootTest], which replaced the drain assembly-text pins. The one assembly-text pin that
/// remains (#3303, A3c) is on the management validator and flag in `ManagementAssembly`.
class ManagementAssemblyDrainPortSeamTest {
    private static final NodeId TARGET = NodeId.nodeId("node-target").unwrap();

    private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();

    private static MembershipFsm fsm() {
        return MembershipFsm.membershipFsm(FsmObserver.noop(),
                                           System::currentTimeMillis,
                                           Long.MAX_VALUE,
                                           TimeSpan.timeSpan(40).millis(),
                                           TimeSpan.timeSpan(30).seconds(),
                                           TimeSpan.timeSpan(30).seconds());
    }

    /// A1: a request goes through the FSM-aware sink, so the registry AND the FSM both see it; a sink that only
    /// enqueued, or dropped the request, leaves the FSM member un-DEPARTING or the registry empty.
    @Test
    void request_goesThroughTheFsmAwareSink_registryAndFsmBothSeeIt() {
        var fsm = fsm();

        fsm.onMemberDescriptor(NodeInfo.nodeInfo(TARGET, new NodeAddress(TARGET.id(), 6000), Map.of(NodeInfo.LABEL_ROLE, "core")));
        fsm.onSwimHealthy(TARGET, 1L);
        DrainAssembly.drainPort(registry, fsm).request(TARGET);

        assertThat(registry.isDrainRequested(TARGET)).as("the registry holds the command").isTrue();
        assertThat(fsm.memberStates()).as("the FSM saw DrainRequested").containsEntry(TARGET, "Departing");
    }

    /// A2: the pending read is the registry's commanded set, read live (a request made after the port was built shows).
    @Test
    void pending_isTheRegistrysDrainTargets_readLive() {
        var port = DrainAssembly.drainPort(registry, fsm());

        assertThat(port.pending()).isEmpty();

        registry.requestDrain(TARGET);

        assertThat(port.pending()).containsExactly(TARGET);
    }
    /// #3303 (A3c): the assembly builds the management validator and the enabled flag from ONE call, so one read of the
    /// configured mode feeds both. A second mode read (a `managementSecurityEnabled()` call, or the mode itself) can
    /// disagree with the validator; deriving the flag from the app plane (`securityEnabled()`, `securityMode()`) breaks
    /// the #962 inheritance rule. Mutants: a second read of the mode turns the `doesNotContain` red; a second call, or
    /// none, turns `containsOnlyOnce` red.
    @Test
    void managementServer_buildsTheValidatorAndTheFlagFromOneModeRead() {
        var code = NodeDepartureWiringTest.assemblyCode();

        assertThat(code).containsOnlyOnce("SecurityValidator.managementValidator(");
        assertThat(code).doesNotContain("managementSecurityEnabled");
        assertThat(code).doesNotContain("managementSecurityMode");
        assertThat(code).doesNotContain(".securityEnabled()");
        assertThat(code).doesNotContain(".securityMode()");
    }
}
