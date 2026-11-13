// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #3158: the node stops itself on its own `NodeShuttingDown`, whichever deployment engine runs. The route used to be a LEGACY
/// `NodeDeploymentManager` hook, so FSM2 dropped the decision. This pins the route's behaviour (own decision stops once, another node's
/// does not, other variants do not) and, as source-text pins (as `StreamRegistrySweepWiringTest`), that the assembly binds the route
/// and binds the stop to the node. `EmberSelfStopOnShuttingDownTest` pins the same through an assembled cluster under both engines.
class SelfStopOnShuttingDownWiringTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId OTHER = new NodeId("node-other");

    @Test
    void ownShuttingDown_stopsTheNodeOnce_anotherNodesAndOtherVariantsDoNot() {
        var stops = new AtomicInteger();
        var entries = new ArrayList<MessageRouter.Entry<?>>();

        AetherNode.wireSelfStopOnShuttingDown(entries, SELF, stops::incrementAndGet);
        dispatch(entries, MembershipDecision.nodeShuttingDown(OTHER, List.of(SELF, OTHER)));
        dispatch(entries, MembershipDecision.nodeDraining(SELF, List.of(SELF, OTHER)));
        assertThat(stops.get()).as("controls: another node's SHUTTING_DOWN and our own DRAINING stop nothing").isZero();

        dispatch(entries, MembershipDecision.nodeShuttingDown(SELF, List.of(SELF, OTHER)));

        assertThat(stops.get()).isEqualTo(1);
    }

    @Test
    void shuttingDownAfterDraining_stillStopsTheNode() {
        var stops = new AtomicInteger();
        var entries = new ArrayList<MessageRouter.Entry<?>>();

        AetherNode.wireSelfStopOnShuttingDown(entries, SELF, stops::incrementAndGet);
        dispatch(entries, MembershipDecision.nodeDraining(SELF, List.of(SELF, OTHER)));
        dispatch(entries, MembershipDecision.nodeShuttingDown(SELF, List.of(SELF, OTHER)));

        assertThat(stops.get()).isEqualTo(1);
    }

    /// Red when the assembly's call is deleted (the route is then never bound under either engine) or its stop is not the late-bound one.
    @Test
    void theAssembly_bindsTheRoute_andBindsTheStopToTheNode() {
        var code = NodeDepartureWiringTest.assemblyCode();

        assertThat(code).containsOnlyOnce("wireSelfStopOnShuttingDown(aetherEntries,config.self(),()->Option.option(selfStopRef.get()).onPresent(Runnable::run));");
        assertThat(code).containsOnlyOnce("selfStopRef.set(node::stop);");
    }

    /// Every registered route sees the message, as the node's router does.
    private static void dispatch(List<MessageRouter.Entry<?>> entries, Message message) {
        entries.forEach(entry -> entry.asRouter().unwrap().route(message));
    }
}
