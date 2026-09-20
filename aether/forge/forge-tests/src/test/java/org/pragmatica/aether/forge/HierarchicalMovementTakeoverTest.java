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

/// Hold drain delivery, kill the active core leader, then resume the same committed movement.
/// Provisioning and retirement remain production effects; the fault preserves two core voters.
/// Sources are unzoned: Ember has no provider-native zone observation contract.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalMovementTakeoverTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private EmberCluster cluster;

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(3, 29400, 29500, 29600, "movement");
        LifecycleAwait.settled("start movement cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader()
            .flatMap(cluster::getNode).filter(node -> node.kvStore().get(AetherKey.ClusterConfigKey.CURRENT).isPresent()).isPresent());
    }

    @AfterAll void stop() {
        Option.option(cluster).onPresent(value -> {
            value.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
            LifecycleAwait.bestEffort("stop movement cluster", value, value.stop());
        });
    }

    @Test void leaderLossDuringPendingDrainPreservesOperationAndCompletesRetirement() {
        changeDestination("east");
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() ->
            assertThat(operation().filter(value -> value.phase() == AetherValue.PlacementOperationPhase.COMPLETE).isPresent())
                .as("initial provisioning: %s", operation()).isTrue());
        var original = operation().unwrap().targetNode();
        assertAssignmentAndSource(original, "east");
        assertThat(cluster.getNode(original.id()).isPresent()).isTrue();

        var originalProcess = cluster.getNode(original.id()).unwrap();
        originalProcess.setInboundFaultFilter((_, message) -> !(message instanceof org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing));
        changeDestination("west");
        await().atMost(BUDGET.duration()).until(() -> operation().filter(value -> value.targetSource().equals("west")
            && value.phase() == AetherValue.PlacementOperationPhase.DRAIN_REQUESTED).isPresent());
        var interrupted = operation().unwrap();
        var failedLeader = leader().self();
        LifecycleAwait.settled("kill movement leader during drain", cluster, cluster.killNode(failedLeader.id(), false));
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().filter(id -> !id.equals(failedLeader)).isPresent());
        await().atMost(BUDGET.duration()).until(() -> operation().filter(value -> value.operationId().equals(interrupted.operationId())
            && value.issuer().leader().equals(leader().self())).isPresent());
        assertThat(cluster.getNode(original.id()).isPresent()).isTrue();
        originalProcess.setInboundFaultFilter((_, _) -> true);
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).untilAsserted(() ->
            assertThat(operation().filter(value -> value.targetSource().equals("west")
                && value.phase() == AetherValue.PlacementOperationPhase.COMPLETE).isPresent())
                .as("replacement creation, drain acknowledgement and retirement: %s", operation()).isTrue());
        var moved = operation().unwrap();
        assertThat(moved.operationId()).isEqualTo(interrupted.operationId());
        assertThat(moved.targetNode()).isEqualTo(interrupted.targetNode());
        assertThat(moved.previousNode()).isEqualTo(Option.some(original));
        assertThat(moved.targetNode()).isNotEqualTo(original);
        assertAssignmentAndSource(moved.targetNode(), "west");
        assertThat(cluster.getNode(original.id()).isEmpty()).isTrue();
        assertThat(leader().kvStore().get(new AetherKey.CapacityReservationKey(original)).isEmpty()).isTrue();
        assertThat(leader().coreNodeIds()).hasSize(3).doesNotContain(original, moved.targetNode());
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
        var value = new AetherValue.ClusterConfigValue(policy(destination), "movement", "1.0.0",
            List.of(new AetherValue.TopologyEntry("default", "core", 3), new AetherValue.TopologyEntry("east", "worker", 0),
                new AetherValue.TopologyEntry("west", "worker", 0)), 3, 3, "forge", before.configVersion() + 1, System.currentTimeMillis());
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
            name = "movement"
            version = "1.0.0"
            [source.default]
            type = "forge"
            [source.default.core]
            count = 3
            [source.east]
            type = "forge"
            [source.east.worker]
            count = 0
            [source.west]
            type = "forge"
            [source.west.worker]
            count = 0
            [community.stable]
            target_size = 1
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
