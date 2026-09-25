// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

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
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Only operator placement intent is submitted by the test. Creation, observed source facts,
/// assignment, quiescent drain acknowledgement and provider retirement use production machinery.
/// Sources are unzoned: Ember has no provider-native zone observation contract.
/// Five cores plus three workers and one movement overlap fit Ember's ten slots. Active-replica
/// sampling and HTTP before/after are explicit evidence; this does not claim continuous zero outage.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalLoadedMovementTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(300).seconds();
    private EmberCluster cluster;

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(5, 32400, 32500, 32600, "loaded-movement");
        LifecycleAwait.settled("start movement cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader()
            .flatMap(cluster::getNode).filter(node -> node.kvStore().get(AetherKey.ClusterConfigKey.CURRENT).isPresent()).isPresent());
    }

    @AfterAll void stop() {
        Option.option(cluster).onPresent(value -> LifecycleAwait.bestEffort("stop movement cluster", value, value.stop()));
    }

    @Test void loadedCommunityMovesAllThreeWorkersWithoutObservedLossOfActiveReplica() {
        changeDestination("east");
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> membersInSource("east").size() == 3
            && leader().kvStore().getTyped(new AetherKey.CommunityKey("stable"), AetherValue.CommunityValue.class)
                .filter(value -> value.state() == org.pragmatica.aether.slice.kvstore.CommunityState.ACTIVE).isPresent());
        var originals = membersInSource("east");
        var artifact = org.pragmatica.aether.artifact.Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        deployWorkersOnlyEcho(artifact);
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> activeWorkers(artifact).size() == 3 && noActiveCore(artifact));
        assertEcho();
        changeDestination("west");
        var samples = new java.util.concurrent.atomic.AtomicInteger();
        await().alias("community movement convergence").pollInterval(250, TimeUnit.MILLISECONDS).atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> {
            var sample = samples.incrementAndGet();
            assertThat(activeWorkers(artifact)).as("sampled make-before-break during %s", operation()).isNotEmpty();
            if (sample % 120 == 0) {
                System.out.println("Movement progress: west=" + membersInSource("west") + ", operation=" + operation());
            }
            return membersInSource("west").size() == 3
                && originals.stream().allMatch(node -> cluster.getNode(node.id()).isEmpty())
                && operation().filter(value -> value.phase() == AetherValue.PlacementOperationPhase.COMPLETE).isPresent();
        });
        assertThat(samples.get()).isPositive();
        assertThat(membersInSource("east")).isEmpty();
        assertThat(membersInSource("west")).allSatisfy(node -> assertAssignmentAndSource(node, "west"));
        originals.forEach(node -> assertThat(leader().kvStore().get(new AetherKey.CapacityReservationKey(node)).isEmpty()).isTrue());
        assertThat(activeWorkers(artifact)).allMatch(membersInSource("west")::contains);
        assertThat(noActiveCore(artifact)).isTrue();
        assertEcho();
        assertThat(leader().membershipFsm().coreCountedMembers()).hasSize(5).doesNotContainAnyElementsOf(membersInSource("west"));
    }

    private List<NodeId> membersInSource(String source) {
        return cluster.allNodes().stream().map(AetherNode::self)
            .filter(node -> leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(node), AetherValue.ActivationDirectiveValue.class)
                .filter(value -> value.communityId().equals("stable")).isPresent())
            .filter(node -> leader().kvStore().getTyped(new AetherKey.NodePlacementKey(node), AetherValue.NodePlacementValue.class)
                .filter(value -> value.sourceName().equals(source)).isPresent()).toList();
    }

    private boolean noActiveCore(org.pragmatica.aether.artifact.Artifact artifact) {
        return leader().membershipFsm().coreCountedMembers().stream().noneMatch(node -> leader().kvStore()
            .getTyped(new AetherKey.NodeArtifactKey(node, artifact), AetherValue.NodeArtifactValue.class)
            .filter(value -> value.state() == org.pragmatica.aether.slice.SliceState.ACTIVE).isPresent());
    }

    private List<NodeId> activeWorkers(org.pragmatica.aether.artifact.Artifact artifact) {
        return cluster.allNodes().stream().map(AetherNode::self)
            .filter(node -> leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(node), AetherValue.ActivationDirectiveValue.class)
                .filter(value -> value.role().equals(AetherValue.ActivationDirectiveValue.WORKER)).isPresent())
            .filter(node -> leader().kvStore().getTyped(new AetherKey.NodeArtifactKey(node, artifact), AetherValue.NodeArtifactValue.class)
                .filter(value -> value.state() == org.pragmatica.aether.slice.SliceState.ACTIVE).isPresent()).toList();
    }

    private void deployWorkersOnlyEcho(org.pragmatica.aether.artifact.Artifact artifact) {
        var node = leader();
        var port = cluster.status().nodes().stream().filter(status -> status.id().equals(node.self().id())).findFirst().orElseThrow().mgmtPort();
        var blueprint = """
            id = "forge.test:loaded-movement:1.0.0"
            [[slices]]
            artifact = "%s"
            instances = 3
            """.formatted(TestArtifacts.ECHO_SLICE);
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/blueprints"))
            .header("Content-Type", "application/toml").timeout(TimeSpan.timeSpan(10).seconds().duration())
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(blueprint)).build();
        assertThat(org.pragmatica.http.JdkHttpOperations.jdkHttpOperations().sendString(request)
            .await(TimeSpan.timeSpan(15).seconds()).unwrap().body()).contains("\"status\":\"applied\"");
        var key = AetherKey.SliceTargetKey.sliceTargetKey(artifact.base());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> leader().kvStore().getTyped(key, AetherValue.SliceTargetValue.class).isPresent());
        var targetLeader = leader();
        var before = targetLeader.kvStore().getTyped(key, AetherValue.SliceTargetValue.class).unwrap();
        var id = UUID.randomUUID().toString();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, id,
            targetLeader.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap(), List.of(),
            List.of(new KVCommand.Mutation<>(key, Option.some(before), Option.some(before.withPlacement("WORKERS_ONLY")))));
        assertThat(targetLeader.<Object>apply(List.of(transaction)).await(BUDGET).unwrap())
            .anyMatch(result -> result instanceof KVCommand.TransactionResult accepted && accepted.transactionId().equals(id) && accepted.accepted());
    }

    private void assertEcho() {
        var core = leader().self();
        var status = cluster.status().nodes().stream().filter(node -> node.id().equals(core.id())).findFirst().orElseThrow();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + (32600 + status.port() - 32400) + "/echo/moved-worker"))
            .timeout(TimeSpan.timeSpan(10).seconds().duration()).GET().build();
        await().atMost(TimeSpan.timeSpan(30).seconds().millis(), TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var response = org.pragmatica.http.JdkHttpOperations.jdkHttpOperations().sendString(request).await(TimeSpan.timeSpan(15).seconds()).unwrap();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("moved-worker");
        });
    }

    private void assertAssignmentAndSource(NodeId node, String source) {
        assertThat(leader().kvStore().getTyped(new AetherKey.ActivationDirectiveKey(node),
            AetherValue.ActivationDirectiveValue.class).unwrap().communityId()).isEqualTo("stable");
        assertThat(leader().kvStore().getTyped(new AetherKey.NodePlacementKey(node),
            AetherValue.NodePlacementValue.class).unwrap().sourceName()).isEqualTo(source);
    }

    private void changeDestination(String destination) {
        var node = leader();
        var before = node.kvStore().getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class).unwrap();
        var value = new AetherValue.ClusterConfigValue(policy(destination), "loaded-movement", "1.0.0",
            List.of(new AetherValue.TopologyEntry("default", "core", 5), new AetherValue.TopologyEntry("east", "worker", 0),
                new AetherValue.TopologyEntry("west", "worker", 0)), 5, 5, "forge", before.configVersion() + 1, System.currentTimeMillis());
        var id = UUID.randomUUID().toString();
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(AetherKey.ClusterConfigKey.CURRENT,
            id, authority, List.of(), List.of(new KVCommand.Mutation<>(AetherKey.ClusterConfigKey.CURRENT, Option.some(before), Option.some(value))));
        var result = node.<Object>apply(List.of(transaction)).await(BUDGET).unwrap();
        assertThat(result).anyMatch(outcome -> outcome instanceof KVCommand.TransactionResult accepted
            && accepted.transactionId().equals(id) && accepted.accepted());
    }

    private static String policy(String destination) {
        return """
            config_version = "1.0.0"
            [cluster]
            name = "loaded-movement"
            version = "1.0.0"
            [source.default]
            type = "forge"
            [source.default.core]
            count = 5
            [source.east]
            type = "forge"
            [source.east.worker]
            count = 0
            [source.west]
            type = "forge"
            [source.west.worker]
            count = 0
            [community.stable]
            target_size = 3
            [community.stable.placement.destination]
            source = "%s"
            """.formatted(destination);
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private Option<AetherValue.CommunityPlacementOperationValue> operation() {
        return leader().kvStore().getTyped(new AetherKey.CommunityPlacementOperationKey("stable"),
            AetherValue.CommunityPlacementOperationValue.class);
    }
}
