// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.cluster.metrics.MetricObservation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
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

/// #588 — pins the two `AetherNode.assembleNode` INSTALL SITES that make a dead worker leave the pong
/// roster (`ClusterSyncCollector.allMetrics()`, the node list `GET /api/v1/cluster/status` serves),
/// through a real booted node. Both were previously reachable only from the Heavy
/// `TerminatedWorkerGhostTest`, which `ci.yml` excludes via `-Dfailsafe.excludedGroups=Heavy` — so
/// every unit suite stayed green with either wiring deleted (round 1's M2 measured exactly that: 0
/// reds across 1148 + 1438 tests).
///
/// The site — the `WorkerLeaveDecision` → `metricsCollector::removeNode` route entry. A worker's death
/// travels on that channel, not on `MembershipDecision.NodeRemoved`, which is what left it listed as
/// `derivedStatus: UNKNOWN` forever.
///
/// The roster is seeded through the collector's own pong ingress, which is what a live peer's pong
/// does; the test asserts the seed landed FIRST, so a run that pruned nothing because it listed
/// nothing cannot read as a pass.
///
/// NOT pinned here: the round-2 `membershipFsm.onNeverJoinedDeath(...)` install site. Its arm is
/// pinned in `MembershipFsmTest.NeverJoinedDeath`, but reaching the LAMBDA from a booted node needs a
/// second QUIC peer that completes a handshake and then never goes SWIM-healthy — the FSM's OBSERVED
/// ingress is the SWIM observation tap and the QUIC peer-state listener, neither routable through
/// `AetherNode.route`. `SwimHintInstallBootTest` records the same boundary for its own third site.
class WorkerRosterPruneBootTest {
    private static final NodeId WORKER = NodeId.nodeId("node-worker-ghost").unwrap();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration PRUNE_BOUND = Duration.ofSeconds(10);

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

    /// The routed `WorkerLeaveDecision` reaches `metricsCollector.removeNode`. With the route
    /// entry deleted the worker stays in `allMetrics()` for the whole bound.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void routedWorkerLeaveDecision_removesTheWorkerFromThePongRoster() {
        node = bootedNode();
        seedRosterWithWorker();

        node.route(WorkerLeaveDecision.workerLeaveDecision(WORKER, stamp()));

        await().atMost(PRUNE_BOUND)
               .untilAsserted(() -> assertThat(node.metricsCollector().allMetrics())
                   .as("a worker's death travels on WorkerLeaveDecision — the pong roster must consume it")
                   .doesNotContainKey(WORKER));
    }

    private void seedRosterWithWorker() {
        node.membershipFsm().onMemberDescriptor(NodeInfo.nodeInfo(WORKER,
            nodeAddress("localhost", freePort()).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "worker")));
        assertThat(node.membershipFsm().isTrackedAndNotDead(WORKER)).isTrue();
        node.metricsCollector().onClusterSyncPong(new ClusterSyncPong(WORKER, new MetricObservation(0L, System.nanoTime(), System.currentTimeMillis(), Map.of("cpu", 0.5)), 0L, 0L, 0L, "", java.util.List.of(), java.util.List.of(), java.util.List.of(), org.pragmatica.lang.Option.none()));

        assertThat(node.metricsCollector().allMetrics())
            .as("control: the worker IS in the pong roster before the departure, or nothing below is examined")
            .containsKey(WORKER);
    }

    private static HlcTimestamp stamp() {
        return new HlcTimestamp(HlcTimestamp.pack(System.currentTimeMillis(), 0), WORKER);
    }

    private AetherNode bootedNode() {
        var booted = AetherNode.aetherNode(minimalConfig(), () -> {})
                               .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                               .unwrap();

        booted.start()
              .await(START_BOUND)
              .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        return booted;
    }

    /// The #858 single-node boot fixture, as `SwimHintInstallBootTest` uses it: `self` in `coreNodes`
    /// (TopologyObserver requires it), mutual self-signed QUIC TLS, management and app HTTP off.
    private static AetherNodeConfig minimalConfig() {
        var self = NodeId.nodeId("worker-roster-prune-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

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
                               .build();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
