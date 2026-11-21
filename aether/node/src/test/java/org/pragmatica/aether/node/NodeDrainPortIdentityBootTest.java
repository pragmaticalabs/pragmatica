// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// C6-W0 (#3519) runtime identity pin: the node is assembled through the production assembly and the DRAIN channel is
/// read off the live objects, not off the source text. A text pin names a call, not an instance, so a second unwired
/// registry, a bypassed FSM or a `null` that becomes `DrainPort.none()` all survived it. Here:
///  - the port the management server's lifecycle routes hold IS (same instance) the quorum-loss unit's port;
///  - a drain command issued through it reaches the node's own `DrainCommandRegistry` and passes through the node's own
///    membership FSM (the FSM's member state is observed, not a call count);
///  - the quorum-loss inputs carry the node's own KV store and pong fan, which the unit does not read until C6b-4.
/// The assembly-text pins this replaced added no case the runtime reads miss (the one-sink-built-once and
/// no-`DrainPort.none()` pins are implied by identity plus the FSM and registry effect), so none was kept.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeDrainPortIdentityBootTest {
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final NodeId TARGET = NodeId.nodeId("node-drain-identity-target").unwrap();

    private AetherNode node;
    private AssemblyObserver.QuorumLoss quorumLoss;
    private ManagementServer managementServer;

    @BeforeAll
    void boot() throws IOException {
        var tempDir = Files.createTempDirectory("drain-port-identity");
        var self = NodeId.nodeId("drain-port-identity-boot-" + UUID.randomUUID()).unwrap();

        try (var capture = AssemblyObserver.capture()) {
            node = AetherNode.aetherNode(minimalConfig(self, tempDir, ClusterTestPorts.freeTcpAndUdpPort()), () -> {})
                             .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                             .unwrap();
            quorumLoss = capture.quorumLoss();
            managementServer = capture.management();
        }
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
    }

    @AfterAll
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
    void managementServer_holdsTheQuorumLossUnitsPort_sameInstance() throws Exception {
        assertThat(quorumLoss).as("the quorum-loss unit was observed").isNotNull();
        assertThat(managementServer).as("the management server was observed").isNotNull();

        assertThat(drainPortOf(managementServer)).as("the management server's port is the quorum-loss unit's own")
                                                 .isSameAs(quorumLoss.drain().drainPort());
        assertThat(drainPortOf(managementServer)).as("and it is a registry-backed port, not the no-op")
                                                 .isNotSameAs(NodeLifecycleRoutes.DrainPort.none());
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void drainThroughTheManagementPort_reachesTheNodesRegistry_andPassesThroughItsMembershipFsm() throws Exception {
        var fsm = quorumLoss.inputs().membershipFsm();
        var registry = quorumLoss.inputs().drainCommandRegistry();

        fsm.onMemberDescriptor(NodeInfo.nodeInfo(TARGET, new NodeAddress(TARGET.id(), 6000), Map.of(NodeInfo.LABEL_ROLE, "core")));
        fsm.onSwimHealthy(TARGET, 1L);
        drainPortOf(managementServer).request(TARGET);

        assertThat(registry.isDrainRequested(TARGET)).as("the node's own registry holds the command").isTrue();
        assertThat(drainPortOf(managementServer).pending()).as("the port reads the node's own registry").contains(TARGET);
        assertThat(fsm.memberStates()).as("the node's own FSM saw DrainRequested").containsEntry(TARGET, "Departing");
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void quorumLossInputs_carryTheNodesOwnKvStoreAndPongFan() {
        assertThat(quorumLoss.kvStore()).as("control: the node's KV store local is a real instance").isNotNull();
        assertThat(quorumLoss.pongSignalFan()).as("control: the node's pong fan local is a real instance").isNotNull();
        assertThat(quorumLoss.inputs().kvStore()).as("kvStore input is the node's own").isSameAs(quorumLoss.kvStore());
        assertThat(quorumLoss.inputs().pongSignalFan()).as("pongSignalFan input is the node's own")
                                                       .isSameAs(quorumLoss.pongSignalFan());
    }

    /// The DRAIN channel the server's lifecycle routes hold: both fields are private, and the server exposes no accessor
    /// (adding one for a test would put test surface on the public management API). `ManagementServer` is an interface;
    /// the field lives on its package-private implementation, read through the runtime class.
    private static NodeLifecycleRoutes.DrainPort drainPortOf(ManagementServer server) throws Exception {
        var routes = read(server.getClass(), "nodeLifecycleRoutes", server);

        return (NodeLifecycleRoutes.DrainPort) read(NodeLifecycleRoutes.class, "drainPort", routes);
    }

    private static Object read(Class<?> owner, String fieldName, Object target) throws Exception {
        Field field = owner.getDeclaredField(fieldName);

        field.setAccessible(true);
        return field.get(target);
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot, int managementPort) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(managementPort)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig(false,
                                                                    AppHttpConfig.DEFAULT_APP_HTTP_PORT,
                                                                    Map.of(),
                                                                    AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                                                    SecurityMode.NONE,
                                                                    Option.empty(),
                                                                    HttpProtocol.H1)
                                                     .unwrap())
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
