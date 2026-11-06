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
import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #774: how the node assembles the config change feed. The applier's listener order is read at RUNTIME from an assembled node (v-2643
/// finding 3: a pin on the source text order stayed green when the feed's registration moved below the call that registers the FSM listener,
/// which swaps the runtime order and keeps the text order). The stop step and the slice store wiring stay pinned on the assembly source (the
/// same technique as `AetherNodeBackupCoordinatorStopWiringTest`); the stop pin reads the one chained expression whose `flatMap` IS the
/// runtime order.
class ConfigChangeFeedWiringTest {
    @TempDir
    Path tempDir;

    private Option<AetherNode> node = Option.none();

    @AfterEach
    void tearDown() {
        node.onPresent(booted -> booted.stop()
                                       .await(timeSpan(10).seconds())
                                       .onFailure(cause -> {}));
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void legacyNode_theApplierPublishesToTheFeed_beforeTheDeploymentFsmListener() {
        assertFeedRunsFirst(DeploymentEngine.LEGACY);
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void fsm2Node_theApplierPublishesToTheFeed_beforeTheDeploymentFsmListener() {
        assertFeedRunsFirst(DeploymentEngine.FSM2);
    }

    /// The listener names an assembled and started node's applier holds, in the order it runs them.
    private void assertFeedRunsFirst(DeploymentEngine engine) {
        var assembled = AetherNode.aetherNode(configuredNode(tempDir, engine), () -> {})
                                  .onFailure(cause -> fail("assembly must succeed: " + cause.message()))
                                  .unwrap();

        node = Option.some(assembled);
        assembled.start()
                 .await(timeSpan(30).seconds())
                 .onFailure(cause -> fail("start() must succeed: " + cause.message()));
        var names = assembled.dynamicConfigManager()
                             .map(DynamicConfigManager::appliedListenerNames)
                             .or(List.of());

        assertThat(names).as("the feed is registered first: a Watched subscription is triggered ahead of the deployment FSM's push")
                         .containsExactly(AetherNode.CONFIG_CHANGE_FEED_LISTENER, AetherNode.DEPLOYMENT_CONFIG_LISTENER);
    }

    @Test
    void nodeStop_releasesTheWatchSubscriptions_asTheFirstStep_beforeAnythingTheyReadIsTornDown() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("configChangeFeed.shutdown()")
                          .containsOnlyOnce("deploymentWiring.deactivateCluster(clusterDeploymentManager::deactivate)")
                          .containsOnlyOnce("NodeStopSequence.Steps.steps(this::releaseWatchesThenDeactivate,");
        assertThat(source).as("the first stop step is one chain: the deactivation runs in the shutdown's flatMap, so after it at runtime")
                          .containsOnlyOnce("private Promise<Unit> releaseWatchesThenDeactivate() { return configChangeFeed.shutdown()"
                                            + " .flatMap(() -> deploymentWiring.deactivateCluster(clusterDeploymentManager::deactivate)); }");
    }

    @Test
    void theSliceStoreAndTheFeedAreTheSameInstance_andTheFeedIsGivenTheOperatorSink() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("Option.some(configChangeFeed)")
                          .containsOnlyOnce("configChangeFeed.setOperatorWarningSink(operatorWarningSink)");
    }

    private static AetherNodeConfig configuredNode(Path storageRoot, DeploymentEngine engine) {
        var self = NodeId.nodeId("feed-wiring-" + UUID.randomUUID()).unwrap();
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
                               .configProvider(Option.some(ConfigurationProvider.builder().build()))
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build()
                               .withDeploymentEngine(engine);
    }
}
