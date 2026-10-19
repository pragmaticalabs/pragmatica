// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1468 — the two accessor hops between the engine's `ownRestoreFailure()` and the `Main` watchdog:
/// `RabiaNode` over its engine, and `AetherNode` over its `RabiaNode`. Both interfaces default to `Option.none()`,
/// so a missing or wrong override compiles and silently disables the whole feature.
///
/// The failure is injected into the REAL engine of an assembled (not started) node by writing its private field,
/// because producing a genuine own-restore failure needs a persisted snapshot and a failing state machine that the
/// assembly does not expose. The engine's own recording of the failure is pinned by `RabiaOwnRestoreFailureTest`;
/// this test pins only that each hop reads it. The control (empty before the write) shows the accessors are live.
class AetherNodeOwnRestoreFailureAccessorTest {
    private static final Cause UNREADABLE = Causes.cause("snapshot is corrupt and cannot be decoded");

    @TempDir Path storageRoot;
    private AetherNode node;

    @AfterEach void close() {
        if (node != null) node.stop().await(timeSpan(10).seconds()).unwrap();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    void ownRestoreFailure_isEmpty_onAFreshAssembledNode() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(coreConfig(), () -> {}).unwrap();

        assertThat(rabiaNode(node).ownRestoreFailure()).isEqualTo(Option.none());
        assertThat(node.ownRestoreFailure()).isEqualTo(Option.none());
        assertThat(rabiaNode(node).restoredStateSaveFailure()).isEqualTo(Option.none());
        assertThat(node.restoredStateSaveFailure()).isEqualTo(Option.none());
    }

    @Test
    void rabiaNode_reportsTheEnginesRestoredStateSaveFailure() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(coreConfig(), () -> {}).unwrap();

        failEngineField(rabiaNode(node), "restoredStateSaveFailure");

        assertThat(rabiaNode(node).restoredStateSaveFailure()).isEqualTo(Option.some(UNREADABLE));
        assertThat(rabiaNode(node).ownRestoreFailure()).as("the two fences are separate accessors").isEqualTo(Option.none());
    }

    @Test
    void aetherNode_reportsTheClusterNodesRestoredStateSaveFailure() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(coreConfig(), () -> {}).unwrap();

        failEngineField(rabiaNode(node), "restoredStateSaveFailure");

        assertThat(node.restoredStateSaveFailure()).isEqualTo(Option.some(UNREADABLE));
        assertThat(node.ownRestoreFailure()).isEqualTo(Option.none());
    }

    @Test
    void rabiaNode_reportsTheEnginesOwnRestoreFailure() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(coreConfig(), () -> {}).unwrap();

        failEnginesOwnRestore(rabiaNode(node));

        assertThat(rabiaNode(node).ownRestoreFailure()).isEqualTo(Option.some(UNREADABLE));
    }

    @Test
    void aetherNode_reportsTheClusterNodesOwnRestoreFailure() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(coreConfig(), () -> {}).unwrap();

        failEnginesOwnRestore(rabiaNode(node));

        assertThat(node.ownRestoreFailure()).isEqualTo(Option.some(UNREADABLE));
    }

    private AetherNodeConfig coreConfig() {
        var self = new NodeId("own-restore-accessor-core");
        return AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6124).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(org.pragmatica.aether.config.SliceConfig.sliceConfig())
            .artifactRepo(org.pragmatica.dht.DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none())
            .environment(Option.none()).managementHttpProtocol(org.pragmatica.aether.config.HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
    }

    private static RabiaNode<?> rabiaNode(AetherNode node) throws ReflectiveOperationException {
        var method = node.getClass().getDeclaredMethod("clusterNode");

        method.setAccessible(true);

        return (RabiaNode<?>) method.invoke(node);
    }

    private static void failEnginesOwnRestore(RabiaNode<?> rabiaNode) throws ReflectiveOperationException {
        failEngineField(rabiaNode, "ownRestoreFailure");
    }

    private static void failEngineField(RabiaNode<?> rabiaNode, String fieldName) throws ReflectiveOperationException {
        var accessor = rabiaNode.getClass().getDeclaredMethod("consensus");

        accessor.setAccessible(true);

        var engine = accessor.invoke(rabiaNode);
        var field = engine.getClass().getDeclaredField(fieldName);

        field.setAccessible(true);
        field.set(engine, Option.some(UNREADABLE));
    }
}
