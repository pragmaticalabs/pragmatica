// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.statemachine.FsmObserver;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F — a node's version travels with its handshake like its role and source, so a node that was in the configured peer list from
/// the start (never "discovered" with labels by the topology) still has a version in every peer's membership view.
class MemberDescriptorVersionTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER = new NodeId("node-b");

    private static NodeInfo hello(NodeId id, Map<String, String> labels) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("host-x", 6000).unwrap(), labels);
    }

    private static MembershipFsm seeded() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(), System::currentTimeMillis, Long.MAX_VALUE, org.pragmatica.lang.io.TimeSpan.timeSpan(40).millis());

        fsm.seed(Set.of(SELF, PEER));

        return fsm;
    }

    @Test
    void theHandshakeLabelBecomesTheDescriptorVersion() {
        var info = hello(PEER, Map.of(NodeInfo.LABEL_ROLE, "core", NodeInfo.LABEL_VERSION, "1.0.0"));

        assertThat(MemberDescriptor.fromNodeInfo(info).version()).isEqualTo("1.0.0");
        assertThat(MemberDescriptor.fromNodeInfo(hello(PEER, Map.of())).version()).isEmpty();
    }

    @Test
    void aBootstrapPeerSeededWithoutLabels_learnsItsVersionFromTheHandshake() {
        var fsm = seeded();

        assertThat(fsm.memberDescriptor(PEER).map(MemberDescriptor::version).or("<no descriptor>")).as("before any handshake").isIn("", "<no descriptor>");

        fsm.onMemberDescriptor(hello(PEER, Map.of(NodeInfo.LABEL_ROLE, "core", NodeInfo.LABEL_VERSION, "1.0.0")));

        assertThat(fsm.memberDescriptor(PEER).map(MemberDescriptor::version).or("<no descriptor>")).isEqualTo("1.0.0");
    }

    @Test
    void aLabelLessObservation_neverErasesAKnownVersion_andAKnownVersionIsNotRewritten() {
        var fsm = seeded();

        fsm.onMemberDescriptor(hello(PEER, Map.of(NodeInfo.LABEL_ROLE, "core", NodeInfo.LABEL_VERSION, "1.0.0")));
        fsm.onMemberDescriptor(hello(PEER, Map.of()));
        fsm.onMemberDescriptor(hello(PEER, Map.of(NodeInfo.LABEL_VERSION, "9.9.9")));

        assertThat(fsm.memberDescriptor(PEER).map(MemberDescriptor::version).or("<no descriptor>")).isEqualTo("1.0.0");
    }
}
