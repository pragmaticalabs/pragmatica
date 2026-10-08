// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1543 B3 (v-2008): a replacement's operator events are raised on the node that owns the cluster-events partition, from the
/// committed record as it is applied. The gating FUNCTION is pinned by `NodeReplacementEnvTest`; this pins the line in `AetherNode`
/// that hands it the node's REAL owner check and the node's REAL warning sink. A single booted node is the owner of the one-partition
/// stream, so a committed pairing applied to its index must reach its event aggregator as `node-replacement-started`. Deleting the
/// wiring, or gating it on a constant, leaves the unit pins green and the events silent.
class NodeReplacementOwnerGateBootTest {
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final String STARTED = "node-replacement-started";

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
    @Timeout(value = 120, unit = SECONDS)
    void aPairingAppliedOnTheOwner_reachesTheEventAggregator_asStarted() {
        node = bootedNode();
        var pairings = ((NodeReplacementWiring.Wired) node.nodeReplacementService()).pairings();

        assertThat(startedSeen()).as("control: nothing replacement-shaped before a pairing is applied").isFalse();

        // The owner check binds late (the stream controller is built after the node assembles): apply a fresh pairing until the
        // gate opens. Each pairing is a distinct original, so the aggregator's per-subject throttle never hides the next one.
        var applied = new AtomicInteger();

        await().atMost(60, SECONDS).alias("the cluster-events owner raised node-replacement-started for an applied pairing").until(() -> {
            if (!startedSeen()) {
                var original = new NodeId("original-" + applied.incrementAndGet());

                pairings.put(new AetherKey.NodeReplacementKey(original),
                             new AetherValue.NodeReplacementValue(new NodeId("replacement-" + applied.get()),
                                                                  "core",
                                                                  AetherValue.NodeReplacementPhase.PROVISIONING,
                                                                  0L));
            }

            return startedSeen();
        });
    }

    private boolean startedSeen() {
        return node.eventAggregator()
                   .lastRaisedOperatorWarning()
                   .filter(ClusterEvent.OperatorWarning.class::isInstance)
                   .map(ClusterEvent.OperatorWarning.class::cast)
                   .filter(warning -> STARTED.equals(warning.details().get("code")))
                   .isPresent();
    }

    private AetherNode bootedNode() {
        var self = NodeId.nodeId("owner-gate-boot-" + UUID.randomUUID()).unwrap();
        var booted = AetherNode.aetherNode(minimalConfig(tempDir, self), () -> {})
                               .onFailure(cause -> fail("assembly must succeed: " + cause.message()))
                               .unwrap();

        booted.start().await(START_BOUND).onFailure(cause -> fail("start() must succeed: " + cause.message()));

        return booted;
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot, NodeId self) {
        var address = nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, address);

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
