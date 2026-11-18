// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

import static org.assertj.core.api.Assertions.assertThat;

/// `AetherNode.nodesReporting` is the leader's view of which nodes are draining (it feeds the cluster deployment
/// manager's draining-nodes supplier). A DRAINING target matches every draining state, so a node reporting HELD counts
/// as draining; any non-draining target matches only itself
/// (a HELD target is itself a draining target, so it matches every draining state). HELD is the report of a draining node whose departure push
/// has not resolved: it must not slip out of the draining set the moment it is reported.
class NodesReportingHeldTest {
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId PEER_HELD = new NodeId("peer-held");
    private static final NodeId PEER_DRAINING = new NodeId("peer-draining");
    private static final NodeId PEER_READY = new NodeId("peer-ready");

    private static final Map<NodeId, NodeReportedState> PEERS = Map.of(PEER_HELD,
                                                                       NodeReportedState.HELD,
                                                                       PEER_DRAINING,
                                                                       NodeReportedState.DRAINING,
                                                                       PEER_READY,
                                                                       NodeReportedState.READY);

    @Test
    void drainingTarget_countsAPeerReportingHeld_asDraining() {
        assertThat(AetherNode.nodesReporting(PEERS, holder(NodeReportedState.READY), SELF, NodeReportedState.DRAINING))
            .containsExactlyInAnyOrder(PEER_HELD, PEER_DRAINING);
    }

    @Test
    void drainingTarget_countsSelfReportingHeld_asDraining() {
        assertThat(AetherNode.nodesReporting(Map.of(), holder(NodeReportedState.HELD), SELF, NodeReportedState.DRAINING))
            .containsExactly(SELF);
    }

    @Test
    void drainingTarget_doesNotCountSelfReportingReady() {
        assertThat(AetherNode.nodesReporting(Map.of(), holder(NodeReportedState.READY), SELF, NodeReportedState.DRAINING))
            .isEmpty();
    }

    @Test
    void readyTarget_matchesOnlyReady_notHeldNorDraining() {
        assertThat(AetherNode.nodesReporting(PEERS, holder(NodeReportedState.HELD), SELF, NodeReportedState.READY))
            .isEqualTo(Set.of(PEER_READY));
    }

    @Test
    void heldTarget_isADrainingTarget_matchesEveryDrainingState() {
        assertThat(AetherNode.nodesReporting(PEERS, holder(NodeReportedState.HELD), SELF, NodeReportedState.HELD))
            .containsExactlyInAnyOrder(PEER_HELD, PEER_DRAINING, SELF);
    }

    private static NodeReportedStateHolder holder(NodeReportedState state) {
        return new NodeReportedStateHolder() {
            @Override
            public NodeReportedState current() {
                return state;
            }

            @Override
            @Contract
            public void onSubsystemsReady() {}

            @Override
            @Contract
            public void onDrainStarted() {}
        };
    }
}
