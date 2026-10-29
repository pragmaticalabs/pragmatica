// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;

/// #1978 (I-36, NT-173's wiring): the departure sweep is called for a node that leaves committed membership, or is decommissioned,
/// and not for one that joins. `KvBackedStreamRegistryHoldersTest` pins the sweep; this pins the route that calls it, which deleting the
/// registration would leave unnoticed.
class StreamRegistrySweepWiringTest {
    private static final NodeId LEAVER = new NodeId("node-leaver");
    private static final NodeId JOINER = new NodeId("node-joiner");

    @Test
    void membershipRemoval_andDecommission_sweepTheNode_butAJoinDoesNot() {
        var swept = new ArrayList<NodeId>();
        var entries = new ArrayList<MessageRouter.Entry<?>>();

        AetherNode.wireStreamRegistrySweep(entries, swept::add);
        dispatch(entries, MembershipDecision.nodeJoined(JOINER, List.of(JOINER)));
        assertThat(swept).as("control: a join sweeps nothing").isEmpty();

        dispatch(entries, MembershipDecision.nodeRemoved(LEAVER, List.of(JOINER)));
        dispatch(entries, MembershipDecision.nodeDecommissioned(JOINER, List.of()));

        assertThat(swept).containsExactly(LEAVER, JOINER);
    }

    /// Every registered route sees the message, as the node's router does.
    private static void dispatch(List<MessageRouter.Entry<?>> entries, MembershipDecision message) {
        entries.forEach(entry -> entry.asRouter().unwrap().route(message));
    }
}
