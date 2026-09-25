// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Real worker metadata, deployment and remote HTTP invocation; no fake slice bridge or actuator.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalWorkerWorkloadTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(10).seconds();
    private static final int BASE = 29400;
    private static final int APP = 29600;
    private EmberCluster cluster;

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(3, BASE, 29500, APP, "worker-workload");
        LifecycleAwait.settled("start worker workload cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll void stop() {
        Option.option(cluster).onPresent(value -> LifecycleAwait.bestEffort("stop worker workload cluster", value, value.stop()));
    }

    @Test void workersOnlyEchoExecutesThroughCoreHttpRouter() {
        var workers = java.util.stream.IntStream.range(0, 3)
            .mapToObj(_ -> LifecycleAwait.nodeSettled("admit workload worker", cluster, cluster.addWorkerNode())).toList();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> workers.stream()
            .allMatch(id -> cluster.getNode(id.id()).filter(AetherNode::isReady).isPresent()));
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        var node = leader();
        var initialLeader = node.self();
        var leaderStatus = cluster.status().nodes().stream().filter(status -> status.id().equals(initialLeader.id())).findFirst().orElseThrow();
        var blueprint = """
            id = "forge.test:worker-workload:1.0.0"
            [[slices]]
            artifact = "%s"
            instances = 3
            """.formatted(TestArtifacts.ECHO_SLICE);
        var applyRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + leaderStatus.mgmtPort() + "/api/v1/blueprints"))
            .header("Content-Type", "application/toml").timeout(REQUEST.duration())
            .POST(HttpRequest.BodyPublishers.ofString(blueprint)).build();
        var applied = jdkHttpOperations().sendString(applyRequest).await(REQUEST).unwrap();
        assertThat(applied.body()).contains("\"status\":\"applied\"");
        var key = AetherKey.SliceTargetKey.sliceTargetKey(artifact.base());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> leader().kvStore().getTyped(key, AetherValue.SliceTargetValue.class).isPresent());
        node = leader();
        var before = node.kvStore().getTyped(key, AetherValue.SliceTargetValue.class).unwrap();
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id,
            node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(before.withPlacement("WORKERS_ONLY")))));
        assertThat(node.<Object>apply(List.of(transaction)).await(BUDGET).unwrap())
            .anyMatch(result -> result instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id) && accepted.accepted());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var active = cluster.allNodes().stream().filter(candidate -> leader().kvStore()
                .getTyped(new AetherKey.NodeArtifactKey(candidate.self(), artifact), AetherValue.NodeArtifactValue.class)
                .filter(value -> value.state() == SliceState.ACTIVE).isPresent()).map(AetherNode::self).toList();
            assertThat(active).hasSize(3).allMatch(workers::contains);
        });
        var core = cluster.status().nodes().stream().filter(status -> !workers.stream().anyMatch(worker -> worker.id().equals(status.id()))).findFirst().orElseThrow();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + (APP + core.port() - BASE) + "/echo/worker-proof"))
            .timeout(REQUEST.duration()).GET().build();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var response = jdkHttpOperations().sendString(request).await(REQUEST).unwrap();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("worker-proof");
        });
        assertThat(leader().membershipFsm().coreCountedMembers()).hasSize(3).doesNotContainAnyElementsOf(workers);
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
}
