// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.node.lifecycle.NodeState;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.TierLevel;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// Promotion on a booted node, not on a hand-built factory result: the node's self-ready path activates demotion and
/// promotion of `artifacts` and `content`, and the node's own scheduled maintenance tick then copies a hot block out
/// of the shared tier into this node's memory tier.
///
/// The maintenance cadence is cut to its one-second floor so the scheduler's tick, which is the production caller, fires
/// within the test. Disk tiers are unavailable by design here (`HermeticStorage` roots them under a regular file), so
/// each instance is `[memory, DHT]`: the slow tier a hot block is promoted out of is the shared one.
class AetherNodePromotionBootTest {
    private static final String ARTIFACTS = "artifacts";
    private static final String CONTENT = "content";
    private static final int BLOCK_BYTES = 1024;
    private static final int MEMORY_BLOCKS = 8;
    private static final int HOT_READS = 51;
    private static final long ACTIVE_BOUND_MS = 60_000;
    private static final long PROMOTION_BOUND_MS = 60_000;

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

    @Test
    @Timeout(value = 150, unit = SECONDS)
    void boot_readyActivatesPerNodeMaintenance_andTheNodesOwnTickPromotesAHotBlock() throws InterruptedException {
        node = AetherNode.aetherNode(bootConfig(), () -> {})
                         .onFailure(cause -> fail("construction must succeed: " + cause.message()))
                         .unwrap();

        var artifacts = node.storageSetups().get(ARTIFACTS);

        assertThat(node.storageSetups()).containsKeys(ARTIFACTS, CONTENT);
        assertThat(artifacts.promotionManager()).as("fixture: promotion is on by default, so the factory built a real manager")
                                                .isNotSameAs(PromotionManager.NOOP);
        assertThat(maintenanceActive(ARTIFACTS)).as("dormant until the node reports ready").isFalse();
        assertThat(maintenanceActive(CONTENT)).isFalse();

        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start() must succeed: " + cause.message()));
        awaitActive();

        assertThat(artifacts.demotionManager().isActive()).as("ready activated demotion on this node").isTrue();
        assertThat(artifacts.promotionManager().isActive()).as("ready activated promotion on this node").isTrue();
        assertThat(node.storageSetups().get(CONTENT).promotionManager().isActive()).isTrue();
        assertThat(node.storageSetups().get(StorageFactory.STREAMS_NAME).promotionManager()).as("streams never promotes")
                                                                                              .isSameAs(PromotionManager.NOOP);

        var ids = fillMemoryTier(artifacts);

        awaitMemoryBelow(artifacts, MEMORY_BLOCKS);

        var cold = ids.stream().filter(id -> !isResidentInMemory(artifacts, id)).findFirst().orElseThrow();

        ids.stream()
           .filter(id -> isResidentInMemory(artifacts, id))
           .limit(3)
           .forEach(id -> artifacts.instance().delete(id).await().onFailure(cause -> fail("delete failed: " + cause.message())));

        for (var i = 0; i < HOT_READS; i++) {
            artifacts.instance().get(cold).await().onFailure(cause -> fail("read failed: " + cause.message()));
        }

        awaitPromoted(artifacts, cold);

        assertThat(artifacts.promotionManager().stats().blocksPromoted()).isGreaterThanOrEqualTo(1);
        assertThat(artifacts.promotionManager().stats().cycles()).isGreaterThanOrEqualTo(1);

        var stopping = node;

        node = null;
        stopping.stop().await(timeSpan(30).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));

        assertThat(artifacts.promotionManager().isActive()).as("stopping the node deactivates promotion").isFalse();
        assertThat(artifacts.demotionManager().isActive()).as("and demotion").isFalse();
    }

    private boolean maintenanceActive(String instance) {
        var setup = node.storageSetups().get(instance);

        return setup.demotionManager().isActive() || setup.promotionManager().isActive();
    }

    private void awaitActive() throws InterruptedException {
        var deadline = System.currentTimeMillis() + ACTIVE_BOUND_MS;

        while (node.nodeLifecycle().currentState() != NodeState.ACTIVE && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(node.nodeLifecycle().currentState()).as("a single-node boot reaches ACTIVE").isEqualTo(NodeState.ACTIVE);
    }

    private void awaitMemoryBelow(StorageFactory.StorageSetup setup, int blocks) throws InterruptedException {
        var deadline = System.currentTimeMillis() + PROMOTION_BOUND_MS;

        while (setup.metadataStore().listBlocksByTier(TierLevel.MEMORY).size() >= blocks && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(setup.metadataStore().listBlocksByTier(TierLevel.MEMORY).size())
                .as("the node's own tick demoted the full memory tier below its high watermark")
                .isLessThan(blocks);
    }

    private void awaitPromoted(StorageFactory.StorageSetup setup, BlockId id) throws InterruptedException {
        var deadline = System.currentTimeMillis() + PROMOTION_BOUND_MS;

        while (!isResidentInMemory(setup, id) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }

        assertThat(isResidentInMemory(setup, id)).as("the node's scheduled maintenance tick promoted the hot block out of "
                                                     + "the shared tier into this node's memory")
                                                 .isTrue();
    }

    private static boolean isResidentInMemory(StorageFactory.StorageSetup setup, BlockId id) {
        return setup.metadataStore().getLifecycle(id).map(lc -> lc.presentIn().contains(TierLevel.MEMORY)).or(false);
    }

    private static List<BlockId> fillMemoryTier(StorageFactory.StorageSetup setup) {
        var ids = new ArrayList<BlockId>();

        for (var i = 0; i < MEMORY_BLOCKS; i++) {
            ids.add(setup.instance()
                         .put(distinctBlock(i))
                         .await()
                         .onFailure(cause -> fail("writing fill block failed: " + cause.message()))
                         .unwrap());
        }

        return ids;
    }

    private static byte[] distinctBlock(int index) {
        var block = new byte[BLOCK_BYTES];

        for (var i = 0; i < block.length; i++) {
            block[i] = (byte) ((index * 29 + i) % 251);
        }

        return block;
    }

    private AetherNodeConfig bootConfig() {
        var self = NodeId.nodeId("promotion-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());
        var hermetic = HermeticStorage.storageConfigAt(HermeticStorage.uncreatableRootIn(tempDir), false);
        var small = StorageConfig.storageConfig((long) BLOCK_BYTES * MEMORY_BLOCKS,
                                                hermetic.diskMaxBytes(),
                                                hermetic.diskPath(),
                                                hermetic.snapshotPath(),
                                                hermetic.snapshotMutationThreshold(),
                                                hermetic.snapshotMaxInterval(),
                                                hermetic.snapshotRetentionCount(),
                                                hermetic.walPath(),
                                                false,
                                                true);
        var everySecond = TimeoutsConfig.timeoutsConfig()
                                        .withStorageMaintenance(new TimeoutsConfig.StorageMaintenanceTimeouts(timeSpan(1).seconds()));

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
                               .configProvider(Option.some(org.pragmatica.config.ConfigurationProvider.builder().build()))
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(Map.of(ARTIFACTS, small))
                               .build()
                               .withTimeouts(everySecond);
    }
}
