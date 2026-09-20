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

/// A definitive preferred-source refusal drives the real committed fallback workflow.
/// Only the provider refusal is injected; backup allocation and readiness use actual Ember nodes.
/// Sources are unzoned: observed location must remain absent rather than echo requested policy.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HierarchicalCapacityFallbackTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private EmberCluster cluster;
    private final java.util.concurrent.atomic.AtomicInteger preferredAttempts = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger backupCreates = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<org.pragmatica.consensus.NodeId> refusedNode = new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeAll void start() {
        cluster = EmberCluster.emberCluster(3, 36900, 37000, 37100, "fallback");
        cluster.withComputeProviderDecorator(this::refusePreferred);
        LifecycleAwait.settled("start fallback cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS).until(() -> cluster.currentLeader()
            .flatMap(cluster::getNode).filter(node -> node.kvStore().get(AetherKey.ClusterConfigKey.CURRENT).isPresent()).isPresent());
    }

    @AfterAll void stop() {
        Option.option(cluster).onPresent(value -> LifecycleAwait.bestEffort("stop movement cluster", value, value.stop()));
    }

    @Test void definitivePreferredRefusalCreatesAllowedBackupThroughSharedCapacityLedger() {
        changeDestination("east");
        await().atMost(BUDGET.duration()).untilAsserted(() ->
            assertThat(operation().filter(value -> value.targetSource().equals("west")
                && value.phase() == AetherValue.PlacementOperationPhase.COMPLETE).isPresent())
                .as("real provider fallback completion: %s", operation()).isTrue());
        var completed = operation().unwrap();
        assertThat(preferredAttempts.get()).isPositive();
        assertThat(backupCreates.get()).isEqualTo(1);
        assertAssignmentAndSource(completed.targetNode(), "west");
        var placement = leader().kvStore().getTyped(new AetherKey.NodePlacementKey(completed.targetNode()),
            AetherValue.NodePlacementValue.class).unwrap();
        assertThat(placement.observedZone().isEmpty()).isTrue(); // Native Ember fact: no zone.
        assertThat(placement.providerInstanceId()).isEqualTo(completed.targetNode().id());
        assertThat(cluster.getNode(completed.targetNode().id()).filter(AetherNode::isReady).isPresent()).isTrue();
        var refusal = leader().kvStore().getTyped(new AetherKey.CommunityPlacementAvailabilityKey("stable", "east", Option.none()),
            AetherValue.CommunityPlacementAvailabilityValue.class).unwrap();
        assertThat(refusal.refusedNode()).isEqualTo(refusedNode.get());
        assertThat(cluster.getNode(refusedNode.get().id()).isEmpty()).isTrue();
        assertThat(leader().kvStore().get(new AetherKey.CapacityReservationKey(refusedNode.get())).isEmpty()).isTrue();
        var reservation = leader().kvStore().getTyped(new AetherKey.CapacityReservationKey(completed.targetNode()),
            AetherValue.CapacityReservationValue.class).unwrap();
        assertThat(reservation.sourceName()).isEqualTo("west");
        assertThat(reservation.sourceBinding()).isEqualTo(completed.sourceBinding());
        assertThat(reservation.phase()).isEqualTo(AetherValue.CapacityReservationPhase.OBSERVED);
        assertThat(leader().kvStore().getTyped(new AetherKey.CapacityLedgerKey(),
            AetherValue.CapacityLedgerValue.class).unwrap().allocated()).isEqualTo(4);
        assertThat(leader().coreNodeIds()).hasSize(3).doesNotContain(completed.targetNode());
    }

    private org.pragmatica.aether.environment.ComputeProvider refusePreferred(org.pragmatica.aether.environment.ComputeProvider delegate) {
        return new org.pragmatica.aether.environment.ComputeProvider() {
            @Override public org.pragmatica.aether.environment.ProviderDefaults providerDefaults() { return delegate.providerDefaults(); }
            @Override public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> createFrom(org.pragmatica.aether.environment.ProvisionRequest request) {
                if (request.context().sourceName().value().equals("east")) {
                    preferredAttempts.incrementAndGet();
                    refusedNode.set(new NodeId(request.context().nodeId().unwrap()));
                    return org.pragmatica.aether.environment.EnvironmentError.capacityUnavailable("",
                        new IllegalStateException("deterministic provider refusal before allocation")).promise();
                }
                if (request.context().sourceName().value().equals("west")) backupCreates.incrementAndGet();
                return delegate.createFrom(request);
            }
            @Override public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> terminate(org.pragmatica.aether.environment.InstanceId id) { return delegate.terminate(id); }
            @Override public org.pragmatica.lang.Promise<List<org.pragmatica.aether.environment.InstanceInfo>> listInstances() { return delegate.listInstances(); }
            @Override public org.pragmatica.lang.Promise<org.pragmatica.aether.environment.InstanceInfo> instanceStatus(org.pragmatica.aether.environment.InstanceId id) { return delegate.instanceStatus(id); }
        };
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
        var value = new AetherValue.ClusterConfigValue(policy(destination), "fallback", "1.0.0",
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
            name = "fallback"
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
            weight = 10
            [community.stable.placement.backup]
            source = "west"
            weight = 1
            """.formatted(destination);
    }

    private AetherNode leader() { return cluster.currentLeader().flatMap(cluster::getNode).unwrap(); }
    private Option<AetherValue.CommunityPlacementOperationValue> operation() {
        return leader().kvStore().getTyped(new AetherKey.CommunityPlacementOperationKey("stable"),
            AetherValue.CommunityPlacementOperationValue.class);
    }
}
