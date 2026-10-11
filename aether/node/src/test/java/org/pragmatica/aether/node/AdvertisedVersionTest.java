// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;

import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #1543 F2: which version a node is known to run. A bootstrap peer is in the topology without a version label, so its version reaches
/// status and the lifecycle only through the membership descriptor; losing that fallback reports every bootstrap peer as unknown ("") and
/// the upgrade run never sees them at the target version.
class AdvertisedVersionTest {
    private static final NodeId PEER = new NodeId("peer-1");

    private static TopologyManager topologyWith(Map<String, String> labels) {
        var topology = mock(TopologyManager.class);

        when(topology.get(PEER)).thenReturn(Option.some(NodeInfo.nodeInfo(PEER, nodeAddress("localhost", 9000).unwrap(), labels)));

        return topology;
    }

    private static Option<MembershipFsm> membershipWith(String version) {
        var fsm = mock(MembershipFsm.class);

        when(fsm.memberDescriptor(any())).thenReturn(Option.some(new MemberDescriptor(Option.none(), "core", "", version)));

        return Option.some(fsm);
    }

    @Test
    void theTopologyLabelWins_overTheDescriptor() {
        assertThat(AdvertisedVersion.of(PEER, topologyWith(Map.of(NodeInfo.LABEL_VERSION, "2.0.0")), membershipWith("1.0.0"))).isEqualTo("2.0.0");
    }

    /// The bootstrap peer: configured in the topology with no version label; its version came with its handshake into the descriptor.
    @Test
    void aBootstrapPeerWithoutALabel_isKnownByItsMembershipDescriptor() {
        assertThat(AdvertisedVersion.of(PEER, topologyWith(Map.of()), membershipWith("1.0.0"))).isEqualTo("1.0.0");
        assertThat(AdvertisedVersion.of(PEER, topologyWith(Map.of(NodeInfo.LABEL_VERSION, " ")), membershipWith("1.0.0"))).isEqualTo("1.0.0");
    }

    @Test
    void aNodeKnownByNeither_isUnknown() {
        assertThat(AdvertisedVersion.of(PEER, topologyWith(Map.of()), membershipWith(""))).isEmpty();
        assertThat(AdvertisedVersion.of(PEER, topologyWith(Map.of()), Option.none())).isEmpty();
    }
}
