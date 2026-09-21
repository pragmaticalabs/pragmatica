// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityControlledLifecycleTest {
    private static final NodeId CORE = new NodeId("core");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final java.util.Set<String> queryFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean deleteFails;
    private Option<Promise<InstanceInfo>> createOutcome = Option.none();
    private final java.util.concurrent.atomic.AtomicReference<List<InstanceInfo>> inventory = new java.util.concurrent.atomic.AtomicReference<>(List.of());
    private final NodeLifecycleManager provider = new NodeLifecycleManager() {
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            creates.incrementAndGet();
            assertThat(ledger().allocated()).isEqualTo(1);
            return createOutcome.or(() -> Causes.cause("Provider timed out after accepting request").promise());
        }
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return instancesForNode(node, source); }
        @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return terminateNode(node); }
        @Override public Promise<Unit> terminateNode(NodeId node) {
            deletes.incrementAndGet();
            return deleteFails ? Causes.cause("temporary provider failure").promise() : Promise.unitPromise();
        }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) {
            return Promise.success(inventory.get());
        }
        @Override public Promise<Unit> restartNode(NodeId node) { return Promise.unitPromise(); }
        @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
        @Override public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) { return org.pragmatica.lang.Result.success("binding"); }
        @Override public boolean isCloudManaged() { return true; }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) {
            lookups.incrementAndGet();
            return queryFailures.contains(node.id()) ? Causes.cause("transient inventory failure").promise() : Promise.success(inventory.get());
        }
    };
    private final CapacityControlledLifecycle lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
        commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 1);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private void initialize(int allocated) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue("""
                config_version = "1.0.0"
                [cluster]
                name = "test"
                version = "1.0.0"
                [source.east]
                type = "forge"
                [source.east.core]
                count = 3
                [source.east.worker]
                count = 0
                """, "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        seed(new KVCommand.LeaderTransaction<>(AetherKey.CapacityLedgerKey.INSTANCE, java.util.UUID.randomUUID().toString(), LEADER, List.of(), java.util.List.of(new KVCommand.Mutation<>(AetherKey.CapacityLedgerKey.INSTANCE, Option.none(), org.pragmatica.lang.Option.some(new AetherValue.CapacityLedgerValue(allocated, 1, true))))));
    }

    private AetherValue.CapacityLedgerValue ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, AetherValue.CapacityLedgerValue.class).unwrap();
    }

    private ProvisionSpec spec(String node, String source) {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", "worker",
            ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), "worker", SourceName.sourceName(source).unwrap(), node)).unwrap();
    }

    @Test
    void ambiguousCreate_retainsGlobalCapacityAcrossSourcesAndLeaderInstances() {
        initialize(0);
        assertThat(lifecycle.provisionNode(spec("first", "east")).await().isFailure()).isTrue();
        assertThat(lifecycle.provisionNode(spec("second", "west")).await().isFailure()).isTrue();
        var recovered = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 1);
        assertThat(recovered.provisionNode(spec("first", "east")).await().isFailure()).isTrue();
        assertThat(creates.get()).isEqualTo(1);
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    @Test
    void unresolvedIdentityCannotBeReusedEvenWithSpareFleetCapacity() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();
        var recovered = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 10);
        var failure = recovered.provisionNode(spec("first", "east")).await().fold(cause -> cause.message(), _ -> "success");
        assertThat(failure).contains("Existing capacity reservation");
        assertThat(creates.get()).isEqualTo(1);
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    @Test
    void confirmedProviderAbsenceRemovesStalePlacementAlongsideReservation() {
        initialize(1);
        var node = new NodeId("dead-worker");
        var key = new AetherKey.CapacityReservationKey(node);
        seed(new KVCommand.LeaderTransaction<>(key, "seed-observed", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(
                new AetherValue.CapacityReservationValue("east", "binding", "worker", AetherValue.CapacityReservationPhase.OBSERVED))))));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(node), new AetherValue.NodePlacementValue("east", Option.none(), "instance")));
        lifecycle.instancesForNode(node, SourceName.sourceName("east").unwrap()).await().unwrap();
        assertThat(store.get(key).isEmpty()).isTrue();
        assertThat(store.get(new AetherKey.NodePlacementKey(node)).isEmpty()).isTrue();
        assertThat(ledger().allocated()).isZero();
    }

    @Test
    void emptyInventory_doesNotReleaseAnUncertainCreate() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();
        lifecycle.instancesForNode(new NodeId("first"), SourceName.sourceName("east").unwrap()).await().unwrap();
        assertThat(ledger().allocated()).isEqualTo(1);
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("first"))).isPresent()).isTrue();
    }

    @Test
    void confirmedAbsenceOfPreviouslyObservedNode_releasesExactlyOneSlot() {
        initialize(1);
        var key = new AetherKey.CapacityReservationKey(new NodeId("first"));
        seed(new KVCommand.LeaderTransaction<>(key, java.util.UUID.randomUUID().toString(), LEADER, List.of(), java.util.List.of(new KVCommand.Mutation<>(key, Option.none(), org.pragmatica.lang.Option.some(new AetherValue.CapacityReservationValue("east", "binding", "worker",
            AetherValue.CapacityReservationPhase.OBSERVED))))));
        lifecycle.instancesForNode(new NodeId("first"), SourceName.sourceName("east").unwrap()).await().unwrap();
        lifecycle.instancesForNode(new NodeId("first"), SourceName.sourceName("east").unwrap()).await().unwrap();
        assertThat(ledger().allocated()).isZero();
        assertThat(store.get(key).isEmpty()).isTrue();
    }
    @Test
    void intendedCoreRole_isCommittedBeforeProviderDispatchAndSurvivesUncertainty() {
        initialize(0);
        var coreSpec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", "core",
            ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), "core",
                SourceName.sourceName("east").unwrap(), "new-core")).unwrap();
        lifecycle.provisionNode(coreSpec).await();
        var reservation = store.getTyped(new AetherKey.CapacityReservationKey(new NodeId("new-core")),
            AetherValue.CapacityReservationValue.class).unwrap();
        assertThat(reservation.intendedRole()).isEqualTo("core");
        assertThat(reservation.phase()).isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
        inventory.set(List.of(observedCore("new-core")));
        lifecycle.instancesForNode(new NodeId("new-core"), SourceName.sourceName("east").unwrap()).await().unwrap();
        var observed = store.getTyped(new AetherKey.CapacityReservationKey(new NodeId("new-core")),
            AetherValue.CapacityReservationValue.class).unwrap();
        assertThat(observed.intendedRole()).isEqualTo("core");
        assertThat(observed.phase()).isEqualTo(AetherValue.CapacityReservationPhase.OBSERVED);
    }

    @Test
    void discoveredProviderCoreLabel_doesNotCreateCoreAdmissionAuthority() {
        initialize(0);
        inventory.set(List.of(observedCore("unreserved")));
        lifecycle.instancesForNode(new NodeId("unreserved"), SourceName.sourceName("east").unwrap()).await().unwrap();
        var observed = store.getTyped(new AetherKey.CapacityReservationKey(new NodeId("unreserved")),
            AetherValue.CapacityReservationValue.class).unwrap();
        assertThat(observed.intendedRole()).isEmpty();
        assertThat(observed.phase()).isEqualTo(AetherValue.CapacityReservationPhase.OBSERVED);
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    @Test
    void failedDeleteRemainsDiscoverableAfterControllerRestart() {
        initialize(0);
        inventory.set(List.of(observedCore("first")));
        lifecycle.instancesForNode(new NodeId("first"), SourceName.sourceName("east").unwrap()).await().unwrap();
        deleteFails = true;
        assertThat(lifecycle.terminateNode(new NodeId("first")).await().isFailure()).isTrue();
        var reservation = store.getTyped(new AetherKey.CapacityReservationKey(new NodeId("first")),
            AetherValue.CapacityReservationValue.class).unwrap();
        assertThat(reservation.phase()).isEqualTo(AetherValue.CapacityReservationPhase.RETIRING);
        var restarted = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 1);
        deleteFails = false;
        inventory.set(List.of());
        restarted.terminateNode(new NodeId("first")).await().unwrap();
        assertThat(deletes.get()).isEqualTo(2);
        assertThat(ledger().allocated()).isZero();
    }

    @Test
    void continuingInventoryReleasesObservedAbsenceButKeepsUncertainCreate() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue("""
                config_version = "1.0.0"
                [cluster]
                name = "test"
                version = "1.0.0"
                [source.east]
                type = "forge"
                [source.east.core]
                count = 3
                """, "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(ledger().allocated()).isEqualTo(1);
        inventory.set(List.of(observedCore("first")));
        lifecycle.reconcileInventory().await().unwrap();
        inventory.set(List.of());
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(ledger().allocated()).isZero();
    }

    @Test
    void uncertainCreateCannotBeReleasedByAnEmptyDeleteConfirmation() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();
        assertThat(lifecycle.terminateNode(new NodeId("first")).await().isFailure()).isTrue();
        assertThat(deletes.get()).isZero();
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    @Test
    void busySourceDefersBeforeAnotherReservationOrProviderCall() {
        initialize(0);
        var held = Promise.<InstanceInfo>promise();
        createOutcome = Option.some(held);
        var first = lifecycle.provisionNode(spec("first", "east"));
        assertThat(lifecycle.provisionNode(spec("second", "east")).await().isFailure()).isTrue();
        assertThat(creates.get()).isEqualTo(1);
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("second"))).isEmpty()).isTrue();
        held.fail(Causes.cause("ambiguous result"));
        assertThat(first.await().isFailure()).isTrue();
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    @Test
    void boundedAbsencePassRotatesPastFailuresInsteadOfStarvingLaterAllocations() {
        initialize(64);
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue("""
                config_version = "1.0.0"
                [cluster]
                name = "test"
                version = "1.0.0"
                [source.east]
                type = "forge"
                [source.east.core]
                count = 3
                """, "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        for (int index = 0; index < 64; index++) {
            var node = new NodeId("node-%02d".formatted(index));
            var key = new AetherKey.CapacityReservationKey(node);
            seed(new KVCommand.LeaderTransaction<>(key, node.id(), LEADER, List.of(),
                List.of(new KVCommand.Mutation<>(key, Option.none(), Option.some(
                    new AetherValue.CapacityReservationValue("east", "binding", "worker", AetherValue.CapacityReservationPhase.OBSERVED))))));
            if (index < 32) queryFailures.add(node.id());
        }
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(lookups.get()).isEqualTo(32);
        assertThat(ledger().allocated()).isEqualTo(64);
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(lookups.get()).isEqualTo(64);
        assertThat(ledger().allocated()).isEqualTo(32);
        queryFailures.clear();
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(ledger().allocated()).isZero();
    }

    @Test
    void restoredLedgerWithoutConfigurationCannotAdmitCreates() {
        initialize(0);
        seed(new KVCommand.Remove<>(AetherKey.ClusterConfigKey.CURRENT));
        assertThat(lifecycle.provisionNode(spec("first", "east")).await().isFailure()).isTrue();
        assertThat(creates.get()).isZero();
        assertThat(ledger().allocated()).isZero();
    }

    private static InstanceInfo observedCore(String node) {
        return new InstanceInfo(new org.pragmatica.aether.environment.InstanceId("instance-" + node),
            org.pragmatica.aether.environment.InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND,
            java.util.Map.of("aether-role", "core"), Option.some(node), Option.none());
    }

}
