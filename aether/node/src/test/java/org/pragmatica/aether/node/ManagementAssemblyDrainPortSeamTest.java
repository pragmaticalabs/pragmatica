// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.node.assembly.ManagementAssembly;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;

/// C6a-0 assembly pin (#3467, A1 and A2): the DRAIN channel `ManagementAssembly.managementServer` hands the management
/// server. `managementServer` reads 25 collaborators, so no test builds it; the channel is a seam of its own
/// (`ManagementAssembly.drainPort`), pinned here on both halves, and `managementServer` is pinned to call the seam.
class ManagementAssemblyDrainPortSeamTest {
    private static final NodeId TARGET = NodeId.nodeId("node-target").unwrap();

    private final DrainCommandRegistry registry = DrainCommandRegistry.drainCommandRegistry();

    /// A1: a request goes through the FSM-aware sink, so the registry AND the FSM both see it; a sink that only
    /// enqueued, or dropped the request, leaves the FSM member un-DEPARTING or the registry empty.
    @Test
    void request_goesThroughTheFsmAwareSink_registryAndFsmBothSeeIt() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              Long.MAX_VALUE,
                                              TimeSpan.timeSpan(40).millis(),
                                              TimeSpan.timeSpan(30).seconds(),
                                              TimeSpan.timeSpan(30).seconds());

        fsm.onMemberDescriptor(NodeInfo.nodeInfo(TARGET, new NodeAddress(TARGET.id(), 6000), Map.of(NodeInfo.LABEL_ROLE, "core")));
        fsm.onSwimHealthy(TARGET, 1L);
        ManagementAssembly.drainPort(registry, new AtomicReference<>(fsm)).request(TARGET);

        assertThat(registry.isDrainRequested(TARGET)).as("the registry holds the command").isTrue();
        assertThat(fsm.memberStates()).as("the FSM saw DrainRequested").containsEntry(TARGET, "Departing");
    }

    /// A2: the pending read is the registry's commanded set, read live (a request made after the port was built shows).
    @Test
    void pending_isTheRegistrysDrainTargets_readLive() {
        var port = ManagementAssembly.drainPort(registry, new AtomicReference<>());

        assertThat(port.pending()).isEmpty();

        registry.requestDrain(TARGET);

        assertThat(port.pending()).containsExactly(TARGET);
    }

    /// The seam is only worth pinning if `managementServer` goes through it: inlining another port there (none(), an
    /// inline registry) removes this call and the pin goes red.
    @Test
    void managementServer_usesTheSeam_noOtherPortIsBuiltInTheAssembly() {
        var code = NodeDepartureWiringTest.assemblyCode();

        assertThat(code).containsOnlyOnce("drainPort(drainCommandRegistry,membershipFsmRef),");
        assertThat(code).containsOnlyOnce("DrainPort.registry(");
        assertThat(code).doesNotContain("DrainPort.none(");
    }
}
