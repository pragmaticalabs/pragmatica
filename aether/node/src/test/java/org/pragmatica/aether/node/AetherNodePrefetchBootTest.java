// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.PrefetchManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// A booted node owns real prefetch managers for the instances that take part in cross-node prefetching, none for
/// `streams`, and a stopped node has put them to sleep: it neither gossips hints nor starts fetches for its peers.
/// The managers wake on the node's ready signal, which needs a cluster and is pinned in the Ember suite; waking one
/// by hand here is what lets the stop path be observed on a single booted node.
class AetherNodePrefetchBootTest {
    private AetherNode node;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop failed in teardown: " + cause.message()));
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    private void boot() {
        node = AetherNode.aetherNode(AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                                        Option.none(),
                                                                                        ConfigurationProvider.builder().build(),
                                                                                        tempDir),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void aBootedNode_hasRealPrefetchManagersForContentAndArtifacts_andNoneForStreams() {
        boot();

        var setups = node.storageSetups();

        assertThat(setups.get("content").prefetchManager()).isNotSameAs(PrefetchManager.NOOP);
        assertThat(setups.get("artifacts").prefetchManager()).isNotSameAs(PrefetchManager.NOOP);
        assertThat(setups.get("streams").prefetchManager()).isSameAs(PrefetchManager.NOOP);
    }

    /// The node's self-ready signal is what wakes them. A single core forms on its own (see
    /// `AetherNodeDhtCatchUpBootTest`), so a started node reaches ready without a cluster around it. Red when
    /// `AetherNode` stops waking the managers from the self-ready signal.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void aStartedNode_wakesItsPrefetchManagers_whenItReportsReady() {
        var self = NodeId.nodeId("prefetch-ready-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        node = AetherNode.aetherNode(AetherNodeConfig.builder()
                                                     .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                                     .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.DEFAULT).coreMax(1)
                                                     .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                                                     .certificateProvider(Option.none()).configProvider(Option.none()).environment(Option.none())
                                                     .managementHttpProtocol(HttpProtocol.H1)
                                                     .storageConfig(HermeticStorage.nodeStorageIn(tempDir, false))
                                                     .build(),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();

        var managers = StorageFactory.prefetchManagers(node.storageSetups());

        assertThat(managers.values()).as("control: dormant until the node is ready")
                                     .allSatisfy(manager -> assertThat(manager.isActive()).isFalse());

        assertThat(node.prefetchGossipStats().isEmpty()).as("control: SWIM is not running before start, so there is nothing to report").isTrue();

        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start must succeed: " + cause.message()));

        await().atMost(Duration.ofSeconds(90))
               .untilAsserted(() -> assertThat(managers.values()).allSatisfy(manager -> assertThat(manager.isActive()).isTrue()));
        await().atMost(Duration.ofSeconds(30))
               .untilAsserted(() -> assertThat(node.prefetchGossipStats().isPresent())
                   .as("a running node reports its prefetch gossip counters, so an operator can see the drops")
                   .isTrue());
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void stop_putsTheNodesPrefetchManagersToSleep() {
        boot();

        var managers = StorageFactory.prefetchManagers(node.storageSetups());

        managers.values().forEach(PrefetchManager::activate);
        assertThat(managers.values()).as("control: awake before the stop").allSatisfy(manager -> assertThat(manager.isActive()).isTrue());

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;

        assertThat(managers.values()).allSatisfy(manager -> assertThat(manager.isActive()).isFalse());
    }
}
