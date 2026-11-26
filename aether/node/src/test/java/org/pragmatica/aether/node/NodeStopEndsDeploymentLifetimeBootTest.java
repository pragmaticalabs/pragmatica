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
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentContext;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.config.ConfigService;
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

/// #3754, N2 / b05 of the #3776 review: pins the PRODUCTION registration `AetherNode` makes at its `nodeDeploymentManagerFromSnapshot(...)` call
/// (`deploymentWiring.nodeStop()` as the last argument). `NodeStopWiringTest` and `FalseDeathWorkerCatchUpTest` assemble their own wiring and
/// register the stop themselves, so replacing the node's argument with a no-op left every test green and the booted node's deployment FSM
/// outliving its stop. This boots the real node (LEGACY: the legacy node deployment FSM is bound only there, B13.ndm.Stop), stops it through
/// `AetherNode.stop()`, and reads the lifetime of the FSM the node itself assembled.
///
/// Mutant: the argument replaced by `stop -> {}` leaves the lifetime live after the node's stop and goes red here.
class NodeStopEndsDeploymentLifetimeBootTest {
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(_ -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedLegacyNode_whenStopped_endsItsDeploymentFsmLifetime() throws ReflectiveOperationException {
        var self = NodeId.nodeId("stop-lifetime-boot-" + UUID.randomUUID()).unwrap();

        node = AetherNode.aetherNode(minimalConfig(self, tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
        assertThat(node.deploymentWiring().isFsm2()).as("fixture: the boot ran the LEGACY engine, where the node FSM is bound").isFalse();

        var lifetime = ((NodeDeploymentContext) field(component(node, "nodeDeploymentManager"), "ctx")).lifetime();

        assertThat(lifetime.isEnded()).as("control: a started node's deployment FSM lifetime is live").isFalse();

        node.stop()
            .await(timeSpan(30).seconds())
            .onFailure(cause -> fail("stop() must succeed: " + cause.message()));
        node = null;

        assertThat(lifetime.isEnded()).as("the node's stop ended the deployment FSM's lifetime, through the node's own registration").isTrue();
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot) {
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
                               .build()
                               .withDeploymentEngine(DeploymentEngine.LEGACY);
    }

    private static Object component(Object instance, String name) throws ReflectiveOperationException {
        var method = instance.getClass().getDeclaredMethod(name);

        method.setAccessible(true);

        return method.invoke(instance);
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        var field = instance.getClass().getDeclaredField(name);

        field.setAccessible(true);

        return field.get(instance);
    }
}
