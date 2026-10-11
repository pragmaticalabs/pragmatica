// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.cluster.metrics.MetricObservation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;


/// #1717 — pins the `AetherNode` wiring `membershipFsm.setOnePlaneWorkerEviction(!configuredWorker(config))` through real
/// booted nodes. A core may evict a worker on one death plane because it holds evidence sources (governor reports,
/// worker admission) that veto a wrong signal; a worker node holds none for its peer workers, so a one-sided partition
/// longer than the backstop window would declare a live peer dead and raise a false CRITICAL. Every unit pin drives the
/// flag by hand; deleting or inverting the wiring left them all green.
class OnePlaneEvictionWiringBootTest {
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
    void coreNode_allowsOnePlaneWorkerEviction() {
        node = bootedNode(Map.of());

        assertThat(node.membershipFsm().onePlaneWorkerEviction())
            .as("a core holds the evidence sources that veto a wrong single signal").isTrue();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void workerNode_requiresBothPlanes() {
        // A worker with no reachable core never completes start(); the wiring happens at assembly, which is what is pinned.
        node = assembled(Map.of(NodeInfo.LABEL_ROLE, "worker"));

        assertThat(node.membershipFsm().onePlaneWorkerEviction())
            .as("a worker node has no evidence source for its peer workers: both planes stay required").isFalse();
    }

    private AetherNode assembled(Map<String, String> selfLabels) {
        return AetherNode.aetherNode(minimalConfig(tempDir, selfLabels), () -> {})
                         .onFailure(cause -> fail("assembly must succeed: " + cause.message()))
                         .unwrap();
    }

    private AetherNode bootedNode(Map<String, String> selfLabels) {
        var booted = assembled(selfLabels);

        booted.start()
              .await(START_BOUND)
              .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        return booted;
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot, Map<String, String> selfLabels) {
        var self = NodeId.nodeId("one-plane-wiring-boot-" + UUID.randomUUID()).unwrap();
        var address = nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap();
        var selfInfo = selfLabels.isEmpty()
                       ? NodeInfo.nodeInfo(self, address)
                       : NodeInfo.nodeInfo(self, address, selfLabels);

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
