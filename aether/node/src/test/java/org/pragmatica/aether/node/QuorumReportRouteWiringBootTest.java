// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.topology.ObserverResumedNotification;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.testlogging.LogCapture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #2936, PR #2940 ruling: pins the `AetherNode` WIRING of `ObserverResumedNotification -> routeObserverResumedToReport`
/// in `collectRouteEntries`. `QuorumReportRouteTest` hand-calls the route method, so a deleted entry, or one registered
/// for another class, would leave every other test green — and a configured observer would never report its role.
///
/// A real self-forming single-node cluster (the #858 boot shape). The routed notification reaches the node's own
/// `QuorumLossDetector`, whose report change is logged; the control shows no `OBSERVER` report before it is routed.
class QuorumReportRouteWiringBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String DETECTOR_LOGGER = QuorumLossDetector.class.getName();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration STEP_BOUND = Duration.ofSeconds(20);
    private static final String OBSERVER_REPORT = "-> OBSERVER";

    private AetherNode node;
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = LogCapture.capture(DETECTOR_LOGGER, Level.INFO, false);
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        logs.close();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void observerResumed_reachesTheQuorumReport_throughTheNodesRouter() {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        assertThat(logs.messages()).as("control: no observer report before the notification is routed")
                                   .noneMatch(line -> line.contains(OBSERVER_REPORT));

        node.route(ObserverResumedNotification.observerResumed(node.self()));

        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(logs.messages())
                   .as("an ObserverResumedNotification routed on the node's bus must reach the quorum report")
                   .anyMatch(line -> line.contains(OBSERVER_REPORT)));
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes`, mutual self-signed QUIC TLS, management
    /// and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("quorum-report-route-boot-" + UUID.randomUUID()).unwrap();
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
                                .build();
    }
}
