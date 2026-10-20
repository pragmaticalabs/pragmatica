// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.node.lifecycle.NodeState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// What the booted-node promotion tests share: a single-node config whose `artifacts` memory tier holds exactly
/// [#MEMORY_BLOCKS] blocks and whose storage maintenance ticks every second (the floor the validator allows), the
/// fill/read helpers, and a bounded wait. `realDisk` gives `artifacts` a writable disk tier under `root`; without it the
/// disk tier degrades to memory + DHT, as in every other hermetic boot test.
final class PromotionBootFixture {
    static final String ARTIFACTS = "artifacts";
    static final int BLOCK_BYTES = 1024;
    static final int MEMORY_BLOCKS = 8;
    static final int HOT_READS = 51;
    static final long BOUND_MS = 60_000;

    private PromotionBootFixture() {}

    static AetherNodeConfig config(Path root, boolean realDisk) {
        var self = NodeId.nodeId("promotion-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());
        var hermetic = HermeticStorage.storageConfigAt(HermeticStorage.uncreatableRootIn(root), false);
        var artifacts = StorageConfig.storageConfig((long) BLOCK_BYTES * MEMORY_BLOCKS,
                                                    hermetic.diskMaxBytes(),
                                                    realDisk
                                                    ? root.resolve("blocks").toString()
                                                    : hermetic.diskPath(),
                                                    realDisk
                                                    ? root.resolve("snapshots").toString()
                                                    : hermetic.snapshotPath(),
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
                               .storageConfig(Map.of(ARTIFACTS, artifacts))
                               .build()
                               .withTimeouts(everySecond);
    }

    static void awaitActive(AetherNode node) {
        await(() -> node.nodeLifecycle().currentState() == NodeState.ACTIVE, "a single-node boot reaches ACTIVE");
    }

    /// Polls `condition` until it holds or [#BOUND_MS] passes, then asserts it.
    static void await(BooleanSupplier condition, String description) {
        var deadline = System.currentTimeMillis() + BOUND_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep(100);
        }

        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    static boolean isResidentInMemory(StorageFactory.StorageSetup setup, BlockId id) {
        return setup.metadataStore().getLifecycle(id).map(lc -> lc.presentIn().contains(TierLevel.MEMORY)).or(false);
    }

    static List<BlockId> fillMemoryTier(StorageFactory.StorageSetup setup) {
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

    /// Reads `id` `times` times through the instance. The access is counted inline by the read (#2326), so the block's
    /// `accessCount` is complete when the last `await()` returns; no wait is needed before a tick.
    static void readRepeatedly(StorageFactory.StorageSetup setup, BlockId id, int times) {
        for (var i = 0; i < times; i++) {
            setup.instance()
                 .get(id)
                 .await()
                 .onFailure(cause -> fail("read failed: " + cause.message()))
                 .onSuccess(opt -> assertThat(opt.isPresent()).as("the block must be readable from its slow tier").isTrue());
        }
    }

    private static byte[] distinctBlock(int index) {
        var block = new byte[BLOCK_BYTES];

        for (var i = 0; i < block.length; i++) {
            block[i] = (byte) ((index * 29 + i) % 251);
        }

        return block;
    }
}
