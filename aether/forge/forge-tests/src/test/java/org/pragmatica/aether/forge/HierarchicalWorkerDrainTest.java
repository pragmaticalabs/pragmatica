// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
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

/// A real remote invocation holds worker drain until actual completion. This tests self-departure,
/// not provider termination: manually admitted workers have no provider capacity reservation.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalWorkerDrainTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(10).seconds();
    private static final int BASE = 30400;
    private static final int APP = 30600;
    private EmberCluster cluster;
    private HttpServer barrier;
    private final Promise<Unit> entered = Promise.promise();
    private final Promise<Unit> release = Promise.promise();

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(3, BASE, 30500, APP, "worker-drain");
        LifecycleAwait.settled("start worker workload cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll void stop() {
        release.succeed(Unit.unit());
        Option.option(barrier).onPresent(server -> server.stop(0));
        Option.option(cluster).onPresent(value -> LifecycleAwait.bestEffort("stop worker workload cluster", value, value.stop()));
    }

    @Test void heldExecutionCompletesBeforeWorkerSelfDeparture() {
        var workers = java.util.stream.IntStream.range(0, 3)
            .mapToObj(_ -> LifecycleAwait.nodeSettled("admit workload worker", cluster, cluster.addWorkerNode())).toList();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> workers.stream()
            .allMatch(id -> cluster.getNode(id.id()).filter(AetherNode::isReady).isPresent()));
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        var node = leader();
        var initialLeader = node.self();
        var leaderStatus = cluster.status().nodes().stream().filter(status -> status.id().equals(initialLeader.id())).findFirst().orElseThrow();
        var blueprint = """
            id = "forge.test:worker-drain:1.0.0"
            [[slices]]
            artifact = "%s"
            instances = 1
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
            assertThat(active).hasSize(1).allMatch(workers::contains);
        });
        var core = cluster.status().nodes().stream().filter(status -> !workers.stream().anyMatch(worker -> worker.id().equals(status.id()))).findFirst().orElseThrow();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + (APP + core.port() - BASE) + "/echo/worker-proof"))
            .timeout(REQUEST.duration()).GET().build();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var response = jdkHttpOperations().sendString(request).await(REQUEST).unwrap();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("worker-proof");
        });
        var serving = workers.stream().filter(worker -> leader().kvStore()
            .getTyped(new AetherKey.NodeArtifactKey(worker, artifact), AetherValue.NodeArtifactValue.class)
            .filter(value -> value.state() == SliceState.ACTIVE).isPresent()).findFirst().orElseThrow();
        var workerStatus = cluster.status().nodes().stream().filter(status -> status.id().equals(serving.id())).findFirst().orElseThrow();
        barrier = Result.lift(() -> HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)).unwrap();
        barrier.createContext("/release", exchange -> {
            entered.succeed(Unit.unit());
            var body = release.await(TimeSpan.timeSpan(30).seconds()).fold(_ -> "expired", _ -> "released").getBytes(StandardCharsets.UTF_8);
            Result.lift(() -> {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return Unit.unit();
            });
        });
        barrier.start();
        var heldRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + (APP + workerStatus.port() - BASE)
            + "/hold/" + barrier.getAddress().getPort())).timeout(TimeSpan.timeSpan(40).seconds().duration()).GET().build();
        var held = jdkHttpOperations().sendString(heldRequest);
        assertThat(entered.await(REQUEST).isSuccess()).as("actual worker method entered external barrier").isTrue();
        assertThat(cluster.getNode(serving.id()).unwrap().inFlightRequestTracker().count()).as("serving worker accounts for held execution").isPositive();
        var drainLeader = leader().self();
        var drainPort = cluster.status().nodes().stream().filter(status -> status.id().equals(drainLeader.id())).findFirst().orElseThrow().mgmtPort();
        var drain = HttpRequest.newBuilder(URI.create("http://localhost:" + drainPort + "/api/v1/nodes/drain/" + serving.id()))
            .timeout(REQUEST.duration()).POST(HttpRequest.BodyPublishers.noBody()).build();
        var commanded = jdkHttpOperations().sendString(drain).await(REQUEST).unwrap();
        assertThat(commanded.statusCode()).isBetween(200, 299);
        var refusedRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + (APP + workerStatus.port() - BASE) + "/echo/refused-during-drain"))
            .timeout(REQUEST.duration()).GET().build();
        await().atMost(REQUEST.millis(), TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var refused = jdkHttpOperations().sendString(refusedRequest).await(REQUEST).unwrap();
            assertThat(refused.statusCode()).isBetween(400, 599).isNotIn(404, 408, 504);
            assertThat(refused.body()).containsIgnoringCase("drain");
        });
        assertThat(held.isResolved()).as("drain must not complete an executing invocation").isFalse();
        assertThat(cluster.getNode(serving.id()).isPresent()).as("worker retained until execution quiesces").isTrue();
        release.succeed(Unit.unit());
        var completed = held.await(REQUEST).unwrap();
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(completed.body()).contains("released");
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.getNode(serving.id()).isEmpty());
        assertThat(leader().membershipFsm().coreCountedMembers()).hasSize(3).doesNotContainAnyElementsOf(workers);
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
}
