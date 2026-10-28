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
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.metrics.deployment.ReconciliationAdjustment;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
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

/// H-3: pins the `AetherNode` WIRING of the reconciliation event into the cluster-event aggregator —
/// `ReconciliationAdjustment → eventAggregator::onReconciliationAdjustment` in `collectRouteEntries`. The aggregator's
/// mapping is pinned by `ClusterEventAggregatorTest`, which hand-calls the handler, so a deleted route entry, or one
/// registered for another class, left every node test green.
///
/// A real self-forming single-node cluster (the #858 boot shape). A single node never reaches consensus, so the
/// cluster-events stream config never becomes visible and the emit cannot land in the stream. Every path it can take
/// logs instead: the replay and not-owner suppressions print the event, whose summary names the artifact; the ownerless
/// drop and the failed publish print its type, SCALE_UP. A bare node has no slices and so no other SCALE_UP producer,
/// and the control shows none before the event is routed, so the line appears only if the routed event reached
/// `onReconciliationAdjustment`.
class ReconciliationAdjustmentRouteWiringBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration STEP_BOUND = Duration.ofSeconds(20);
    private static final Artifact ARTIFACT = Artifact.artifact("org.example.h3:reconciled-" + UUID.randomUUID()
                                                               + ":1.0.0")
                                                     .unwrap();
    private static final String SCALE_UP = "SCALE_UP";

    private AetherNode node;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(ClusterEventAggregator.class, Level.DEBUG, false);
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        capture.close();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void reconciliationAdjustment_reachesTheAggregator_throughTheNodesRouter() {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        assertThat(capture.messages()).as("control: nothing has raised this artifact's SCALE_UP before it is routed")
                                       .noneMatch(this::isOurScaleUp);

        node.route(ReconciliationAdjustment.reconciliationAdjustment(ARTIFACT, 1, 2));

        await().atMost(STEP_BOUND)
               .untilAsserted(() -> assertThat(capture.messages())
                   .as("a ReconciliationAdjustment routed on the node's bus must reach the aggregator's handler")
                   .anyMatch(this::isOurScaleUp));
    }

    private boolean isOurScaleUp(String line) {
        return line.contains(ARTIFACT.asString()) || line.contains(SCALE_UP);
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes`, mutual self-signed QUIC TLS, management
    /// and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("reconciliation-route-boot-" + UUID.randomUUID()).unwrap();
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
