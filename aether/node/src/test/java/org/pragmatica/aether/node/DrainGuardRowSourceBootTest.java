// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes.SliceFloor;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// F5a (know `63ff3388bd`, v3135a B2): the row source `AetherNode` hands the drain guards is the FSM2 one. Under FSM2 nothing writes a
/// `NodeArtifactKey`, so a guard built over the legacy scan sees every node as owning nothing and never refuses a drain: a silent `minAvailable`
/// breach. Each test commits an ACTIVE observed row for the node plus a slice target, then reads the wired guard (not `AetherNode.sliceRows()`,
/// which would re-ask the wiring and never read the instance the assembly was given). Red when the assembly swaps `deploymentWiring.sliceRows()`
/// for `SliceRowSource.legacy()` at the reconciler's inputs, at its ownership predicate or its guard, or at the management inputs' floor.
class DrainGuardRowSourceBootTest {
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    /// The automatic drain: the victim ordering demotes an owner, and the guard refuses the owner whose removal breaks `minAvailable`.
    @Test
    @Timeout(value = 180, unit = SECONDS)
    void fsm2Node_reconcilerOwnershipAndGuard_readTheObservedRows() {
        boot(AetherNodeConfig.MANAGEMENT_DISABLED);
        var reconciler = (LeaderReconciler) accessor("leaderReconciler");

        commitActiveRowForSelf();

        await("ownership predicate").atMost(Duration.ofSeconds(30)).until(() -> reconciler.ownsActiveSlicesView().test(node.self()));
        assertThat(reconciler.sliceDrainGuardView().apply(node.self(), Set.of()).isPresent()).as("draining the sole ACTIVE owner of a minAvailable-1 slice is refused").isTrue();
    }

    /// The operator drain: the management server's slice floor lists the same breach (#1720). No accessor is added to `ManagementServer`: the
    /// test reads its private `sliceFloor` field, the instance the assembly built.
    @Test
    @Timeout(value = 180, unit = SECONDS)
    void fsm2Node_managementSliceFloor_readsTheObservedRows() {
        boot(ClusterTestPorts.freeTcpAndUdpPort());
        var server = ((Option<?>) accessor("managementServer")).unwrap();
        var floor = (SliceFloor) Result.lift(() -> {
            var field = ManagementServer.class.getDeclaredField("sliceFloor");

            field.setAccessible(true);

            return field.get(server);
        }).unwrap();

        commitActiveRowForSelf();

        await("slice floor").atMost(Duration.ofSeconds(30)).until(() -> !floor.violations().apply(node.self(), Set.of()).isEmpty());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitActiveRowForSelf() {
        var row = new SliceObservedValue(SliceObservedState.ACTIVE, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, 0L), Option.none(), false, 1, List.of("handle"), 0L);
        var store = (KVStore) node.kvStore();

        store.process(store.createBatch((List) List.of(new KVCommand.Put<AetherKey, AetherValue>(SliceTargetKey.sliceTargetKey(ORDERS.base()), SliceTargetValue.sliceTargetValue(ORDERS.version(), 1)),
                                                       new KVCommand.Put<AetherKey, AetherValue>(SliceObservedKey.sliceObservedKey(node.self(), ORDERS), row))));
    }

    private Object accessor(String name) {
        return Result.lift(() -> {
            var method = node.getClass().getDeclaredMethod(name);

            method.setAccessible(true);

            return method.invoke(node);
        }).unwrap();
    }

    private void boot(int managementPort) {
        node = AetherNode.aetherNode(minimalConfig(tempDir, managementPort).withDeploymentEngine(DeploymentEngine.FSM2), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start() must succeed: " + cause.message()));
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot, int managementPort) {
        var self = NodeId.nodeId("drain-guard-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(managementPort)
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
