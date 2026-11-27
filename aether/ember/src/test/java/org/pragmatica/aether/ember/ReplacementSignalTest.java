// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #3740: a test that begins a second replacement waits for the state `begin` checks, the leader's, not for any node's.
class ReplacementSignalTest {
    private static final NodeId VICTIM = new NodeId("wql-2");
    private static final NodeId FRESH = new NodeId("fresh-1");

    @Test
    void settledOnLeader_isFalse_whileOnlyAFollowerShowsDone() {
        var leader = node(true, NodeReplacementPhase.RETIRING_OLD);
        var follower = node(false, NodeReplacementPhase.DONE);

        assertThat(ReplacementSignal.settledOnLeader(List.of(follower, leader), VICTIM)).as("the leader has not applied DONE: begin there is still refused").isFalse();
    }

    @Test
    void settledOnLeader_isTrue_onceTheLeaderShowsDone() {
        var leader = node(true, NodeReplacementPhase.DONE);
        var follower = node(false, NodeReplacementPhase.RETIRING_OLD);

        assertThat(ReplacementSignal.settledOnLeader(List.of(follower, leader), VICTIM)).isTrue();
    }

    @Test
    void settledOnLeader_isFalse_withoutALeader() {
        assertThat(ReplacementSignal.settledOnLeader(List.of(node(false, NodeReplacementPhase.DONE)), VICTIM)).isFalse();
    }

    @Test
    void newestRecordOnAnyNode_isTheHighestEpoch_whicheverNodeHoldsIt() {
        var stale = node(true, NodeReplacementPhase.JOINING, 1L);
        var ahead = node(false, NodeReplacementPhase.DONE, 7L);
        var middle = node(false, NodeReplacementPhase.RETIRING_OLD, 4L);

        assertThat(ReplacementSignal.newestRecordOnAnyNode(List.of(stale, ahead, middle), VICTIM).map(NodeReplacementValue::epoch)).isEqualTo(Option.some(7L));
        assertThat(ReplacementSignal.newestRecordOnAnyNode(List.of(ahead, stale, middle), VICTIM).map(NodeReplacementValue::epoch)).isEqualTo(Option.some(7L));
    }

    @Test
    void newestRecordOnAnyNode_isNone_whenNoNodeHoldsARecord() {
        assertThat(ReplacementSignal.newestRecordOnAnyNode(List.of(), VICTIM)).isEqualTo(Option.none());
    }

    private static ManageableNode node(boolean leader, NodeReplacementPhase phase) {
        return node(leader, phase, 0L);
    }

    private static ManageableNode node(boolean leader, NodeReplacementPhase phase, long epoch) {
        var record = new NodeReplacementValue(FRESH, "core", phase, 0L, "", "", NodeReplacementValue.MODE_CTM, 0, "", epoch);
        var service = (NodeReplacementService) Proxy.newProxyInstance(NodeReplacementService.class.getClassLoader(),
                                                                      new Class<?>[]{NodeReplacementService.class},
                                                                      (proxy, method, args) -> Option.some(record));

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class<?>[]{ManageableNode.class},
                                                       (proxy, method, args) -> "isLeader".equals(method.getName()) ? (Object) leader : service);
    }
}
