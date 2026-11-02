// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
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
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
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

/// #2201 3.2 item 6, pinned through the REAL boot path: every node runs its own sealed-tier retention from node start to
/// node stop, not only while it leads. The unit and Ember tests drive passes through `enforceNow`, so none of them would
/// notice a node that never schedules its enforcer (it would silently never reclaim, and its disk would fill) or one that
/// leaves it running after stop. This single node never forms consensus, so it never leads: the schedule cannot come from
/// the leader toggle.
class RetentionScheduleBootTest {
    private static final long DISK_MAX_BYTES = 256L * 1024 * 1024;
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds());
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void aStartedNode_schedulesItsRetention_andStopClosesIt() {
        node = AetherNode.aetherNode(minimalConfig(), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(START_BOUND).onFailure(cause -> fail("start must succeed: " + cause.message()));

        var enforcer = retentionEnforcerOf(node);

        assertThat(enforcer.isScheduled()).as("a started node that does not lead has its retention scheduled").isTrue();

        node.stop()
            .await(timeSpan(10).seconds())
            .onFailure(cause -> fail("stop failed: " + cause.message()));
        node = null;

        assertThat(enforcer.isScheduled()).as("a stopped node's retention is closed").isFalse();
    }

    private static RetentionEnforcer retentionEnforcerOf(AetherNode node) {
        try {
            var accessor = node.getClass().getDeclaredMethod("streamRetentionEnforcer");

            accessor.setAccessible(true);

            return (RetentionEnforcer) accessor.invoke(node);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("AetherNode has no readable streamRetentionEnforcer component: " + e, e);
        }
    }

    private AetherNodeConfig minimalConfig() {
        var self = NodeId.nodeId("retention-schedule-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());
        // Everything on disk lands under tempDir: `content` and the stream data dir derive from this path.
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024,
                                                DISK_MAX_BYTES,
                                                tempDir.resolve("artifacts").toString(),
                                                tempDir.resolve("snapshots").toString(),
                                                1000,
                                                "60s",
                                                5,
                                                "",
                                                false);

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
                                .storageConfig(Map.of("artifacts", artifactsConfig))
                                .build();
    }
}
