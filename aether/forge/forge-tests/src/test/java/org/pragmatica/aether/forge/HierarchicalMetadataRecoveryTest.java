// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.metadata.WorkerMetadataMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Four clients recover scoped projections together after metadata-only response loss. This is
/// bounded local runtime evidence, not a physical socket reconnect or a 10K throughput benchmark.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalMetadataRecoveryTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private EmberCluster cluster;

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(5, 31400, 31500, 31600, "metadata-recovery");
        LifecycleAwait.settled("start metadata recovery cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll void stop() {
        Option.option(cluster).onPresent(value -> {
            value.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
            LifecycleAwait.bestEffort("stop metadata recovery cluster", value, value.stop());
        });
    }

    @Test void twoCommunitiesRepairTheirScopedMetadataAfterSimultaneousResponseBlackout() {
        var workers = new ArrayList<NodeId>();
        for (var source : java.util.List.of("east", "west")) {
            for (int index = 0; index < 2; index++) {
                var worker = LifecycleAwait.nodeSettled("admit " + source + " worker", cluster,
                    cluster.addNode(Map.of(NodeInfo.LABEL_ROLE, "worker", NodeInfo.LABEL_SOURCE, source)));
                workers.add(worker);
                await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> directive(worker).isPresent());
            }
        }
        var assignments = workers.stream().collect(java.util.stream.Collectors.toMap(worker -> worker,
            worker -> directive(worker).unwrap().communityId()));
        assertThat(assignments.values().stream().distinct().toList()).hasSize(2);
        var nodes = workers.stream().map(worker -> cluster.getNode(worker.id()).unwrap()).toList();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> sampleReadiness(workers, org.pragmatica.aether.metrics.NodeReportedState.READY));
        var dropped = new AtomicInteger();
        nodes.forEach(node -> node.setInboundFaultFilter((_, message) -> {
            if (message instanceof WorkerMetadataMessage.Manifest || message instanceof WorkerMetadataMessage.Chunk) {
                dropped.incrementAndGet();
                return false;
            }
            return true;
        }));
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> dropped.get() >= workers.size());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> sampleReadiness(workers, org.pragmatica.aether.metrics.NodeReportedState.SYNCING));
        assertThat(nodes).allMatch(node -> !node.connectedPeerIds().isEmpty());
        assertThat(workers).allMatch(worker -> cluster.getNode(worker.id()).isPresent());
        nodes.forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> sampleReadiness(workers, org.pragmatica.aether.metrics.NodeReportedState.READY));
        nodes.forEach(node -> {
            assertThat(node.kvStore().getTyped(new AetherKey.ActivationDirectiveKey(node.self()),
                AetherValue.ActivationDirectiveValue.class).map(AetherValue.ActivationDirectiveValue::communityId).unwrap())
                .isEqualTo(assignments.get(node.self()));
            workers.stream().filter(worker -> !assignments.get(worker).equals(assignments.get(node.self())))
                .forEach(foreign -> assertThat(node.kvStore().getTyped(new AetherKey.ActivationDirectiveKey(foreign),
                    AetherValue.ActivationDirectiveValue.class).isEmpty()).as("foreign assignment absent on %s", node.self()).isTrue());
        });
        assertThat(leader().membershipFsm().coreCountedMembers()).hasSize(5).doesNotContainAnyElementsOf(workers);
    }

    /// These forming communities need no governor. Explicit authenticated core requests sample
    /// worker readiness independently of periodic governor polling and the metadata-only blackout.
    /// The replies traverse the real transport and collector; no readiness state is injected.
    private boolean sampleReadiness(java.util.List<NodeId> workers,
                                    org.pragmatica.aether.metrics.NodeReportedState expected) {
        var core = leader();
        workers.forEach(worker -> core.route(new org.pragmatica.consensus.net.NetworkServiceMessage.Send(worker,
            new org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing(core.self(), Map.of(), 0, 0, 0,
                java.util.Set.of(), java.util.Set.of(), Map.of(), java.util.Set.of(), false, false))));
        return workers.stream().allMatch(worker -> core.metricsCollector().reportedStates().get(worker) == expected);
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private Option<AetherValue.ActivationDirectiveValue> directive(NodeId worker) {
        return leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(worker), AetherValue.ActivationDirectiveValue.class);
    }
}
