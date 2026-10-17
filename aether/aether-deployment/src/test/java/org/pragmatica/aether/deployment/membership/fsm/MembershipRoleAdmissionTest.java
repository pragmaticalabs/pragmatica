// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;

class MembershipRoleAdmissionTest {
    private static final NodeId NODE = new NodeId("member");

    @Test
    void descriptor_roleChange_preservesOriginalInstanceRole() {
        var fsm = membership();
        fsm.onMemberDescriptor(info("worker", "first"));
        fsm.onSwimHealthy(NODE, 1);
        fsm.onMemberDescriptor(info("core", "second"));
        assertThat(fsm.memberDescriptor(NODE).unwrap().role()).isEqualTo("worker");
        assertThat(fsm.memberDescriptor(NODE).unwrap().source()).isEqualTo("first");
        assertThat(fsm.coreCountedMembers()).isEmpty();
    }

    @Test
    void healthy_unknownRole_defersJoinUntilDescriptorArrives() {
        var fsm = membership();
        var joins = new ArrayList<MembershipDeltaEdge>();
        fsm.onMembershipDelta(joins::add);
        fsm.onSwimHealthy(NODE, 1);
        assertThat(joins).isEmpty();
        assertThat(fsm.coreCountedMembers()).isEmpty();
        fsm.onMemberDescriptor(info("worker", "source"));
        fsm.onMemberDescriptor(info("worker", "source"));
        assertThat(joins).hasSize(1);
        assertThat(joins.getFirst().role()).isEqualTo("worker");
    }

    private static MembershipFsm membership() {
        var delay = TimeSpan.timeSpan(1).hours();
        return MembershipFsm.membershipFsm(Long.MAX_VALUE, delay, delay, delay);
    }

    private static NodeInfo info(String role, String source) {
        return NodeInfo.nodeInfo(NODE, NodeAddress.nodeAddress("localhost", 10000).unwrap(),
                                 Map.of(NodeInfo.LABEL_ROLE, role, NodeInfo.LABEL_SOURCE, source));
    }
}
