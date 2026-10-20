// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTAntiEntropy;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.testlogging.LogCapture;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #420/#1136 — `DHTAntiEntropy.start()` had no caller, so the periodic repair cycle never ran on a
/// booted node and `[timeouts.dht] anti_entropy_interval` was read by nothing. The node now arms the
/// cycle with its other periodic work, on the configured interval. Pinned through a REAL self-forming
/// node: with the interval set to one second, `DHTAntiEntropy`'s round-complete DEBUG line appears
/// within a few seconds of `start()` resolving, and stops appearing after `stop()`. Red with the
/// `periodicTasks.defer(...)` for the cycle removed (no round is ever logged).
class AetherNodeAntiEntropyCycleBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String LOGGER_NAME = DHTAntiEntropy.class.getName();
    private static final String ROUND_MARKER = "DHT anti-entropy round:";

    private AetherNode node;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(LOGGER_NAME, Level.DEBUG, false);
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        capture.close();
        org.pragmatica.config.ConfigService.clear();
        org.pragmatica.aether.resource.ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 90, unit = SECONDS)
    void bootedNode_runsTheAntiEntropyCycle_onTheConfiguredInterval_andStopsWithTheNode() throws InterruptedException {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                          .onFailure(cause -> fail("construction must succeed: " + cause.message()))
                          .unwrap();

        assertThat(rounds()).as("a created-but-unstarted node performs no periodic work (#644)").isZero();

        node.start().await(timeSpan(30).seconds())
            .onFailure(cause -> fail("start() must resolve on the single-node formation: " + cause.message()));

        var deadline = System.nanoTime() + timeSpan(15).seconds().nanos();

        while (rounds() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }

        assertThat(rounds()).as("the cycle runs on the configured 1s interval after formation").isGreaterThanOrEqualTo(2);

        node.stop().await(timeSpan(10).seconds()).onFailure(cause -> fail("stop must succeed: " + cause.message()));
        node = null;
        var afterStop = rounds();

        Thread.sleep(2_500);

        assertThat(rounds()).as("no round fires after stop() — the cycle is cancelled with the node").isEqualTo(afterStop);
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("anti-entropy-cycle-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());
        var timeouts = TimeoutsConfig.timeoutsConfig()
                                     .withDht(new TimeoutsConfig.DhtTimeouts(timeSpan(30).seconds(), timeSpan(1).seconds()));

        return AetherNodeConfig.builder()
                               .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.DEFAULT).coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none()).configProvider(Option.none()).environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build()
                               .withTimeouts(timeouts);
    }

    private long rounds() {
        return capture.messages().stream().filter(m -> m.startsWith(ROUND_MARKER)).count();
    }
}
