// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #2061: pins what the `AetherNode` `PeerJoined` route does since the node-health alert moved to the membership FSM's
/// edge into `Member`: it ends the announced-departure marks (`AlertManager.forgetDepartureMarks`) and leaves an active
/// node-health alert untouched. Neither half is reachable from the unit tests, which hand-call the `AlertManager`, so a
/// route made a no-op (the mark would survive the handshake) or one that clears alerts again (the pre-#2061 wiring, which
/// silenced a still-dead node's CRITICAL alert) would leave them green.
///
/// A real self-forming single-node cluster (the #858 boot shape); the handshakes are injected on the node's own router.
class PeerJoinedRouteWiringBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void peerJoined_endsTheDepartureMark_andLeavesAnActiveNodeHealthAlertUntouched() {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        var alerts = node.alertManager();
        var dead = NodeId.nodeId("peer-joined-dead").unwrap();
        var drained = NodeId.nodeId("peer-joined-drained").unwrap();

        alerts.onNodeFailed(dead, node.self());
        alerts.noteMembershipTransition(drained, "DrainRequested");
        assertThat(alerts.getActiveNodeHealthAlerts()).as("control: the DEAD verdict raised the alert").hasSize(1);
        assertThat(alerts.hasAnnouncedDeparture(drained)).as("control: the drain mark is held").isTrue();

        route(dead);
        route(drained);

        assertThat(alerts.getActiveNodeHealthAlerts()).as("a handshake from a node the FSM holds dead resolves no alert").hasSize(1);
        assertThat(alerts.hasAnnouncedDeparture(drained)).as("the handshake ended the drain mark").isFalse();
    }

    private void route(NodeId peer) {
        node.route(TransportObservation.peerJoined(peer, List.of(node.self(), peer), TransportObservation.ObservationSource.QUIC));
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes`, mutual self-signed QUIC TLS, management
    /// and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("peer-joined-route-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self)
                                .coreNodes(List.of(selfInfo))
                                .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig())
                                .artifactRepo(DHTConfig.FULL)
                                .coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig())
                                .tls(Option.none())
                                .quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none())
                                .configProvider(Option.none())
                                .environment(Option.none())
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                                .build();
    }
}
