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
    /// Ledger allocation the provider expects to see committed when a create reaches it.
    private final AtomicInteger allocatedAtCreate = new AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicReference<List<InstanceInfo>> inventory = new java.util.concurrent.atomic.AtomicReference<>(List.of());
    private final java.util.concurrent.atomic.AtomicReference<Promise<List<InstanceInfo>>> listed = new java.util.concurrent.atomic.AtomicReference<>(Promise.success(List.of()));
    private static final String OPERATOR_TOML = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "cloud"
        provider = "hetzner"
        credentials = "east-token"
        region = "east-region"
        [source.east.core]
        count = 5
        instance_type = "small"
        """;
    private final NodeLifecycleManager provider = new NodeLifecycleManager() {
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            creates.incrementAndGet();
            assertThat(ledger().allocated()).isEqualTo(allocatedAtCreate.get());
            return Causes.cause("Provider timed out after accepting request").promise();
        }
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return instancesForNode(node, source); }
        @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return terminateNode(node); }
        @Override public Promise<Unit> terminateNode(NodeId node) { return Promise.unitPromise(); }
        @Override public Promise<Unit> restartNode(NodeId node) { return Promise.unitPromise(); }
        @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
        @Override public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) { return org.pragmatica.lang.Result.success("binding"); }
        @Override public boolean isCloudManaged() { return true; }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) { return Promise.success(inventory.get()); }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) { return listed.get(); }
    };
    private final NodeLifecycleManager lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
        commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 1);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private void initialize(int allocated) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.LeaderTransaction<>(AetherKey.CapacityLedgerKey.INSTANCE, java.util.UUID.randomUUID().toString(), LEADER, List.of(), java.util.List.of(new KVCommand.Mutation<>(AetherKey.CapacityLedgerKey.INSTANCE, Option.none(), org.pragmatica.lang.Option.some(new AetherValue.CapacityLedgerValue(allocated, 1, true))))));
    }

    private AetherValue.CapacityLedgerValue ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, AetherValue.CapacityLedgerValue.class).unwrap();
    }

    private ProvisionSpec spec(String node, String source) {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", "worker",
            ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), "worker", SourceName.sourceName(source).unwrap(), node)).unwrap();
    }

    /// #1551: on a self-bootstrapped cluster the committed config is the BootstrapModule seed
    /// (`tomlContent=""`). Fleet inventory has no operator sources to list, so the reservation proceeds to the
    /// provider instead of failing the parse and blocking every provision — and completeness is NOT recorded,
    /// so a later operator config is still inventoried (see the next test).
    @Test
    void provisionNode_bootstrapSeedConfig_dispatchesWithoutRecordingInventoryComplete() {
        seedOnlyCluster();

        lifecycle.provisionNode(spec("new-core", "default")).await();

        assertThat(creates.get()).as("the provider create must be reached").isEqualTo(1);
        assertThat(ledger().inventoryComplete()).isFalse();
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    /// #1551: a seed-only cluster later receives an operator config whose cloud source already runs an
    /// instance. That instance must be inventoried and counted before any further capacity decision, and a
    /// reservation attempted while the inventory is still running must refuse, exactly as at boot.
    @Test
    void provisionNode_operatorConfigReplacesSeed_inventoriesExistingInstancesBeforeReserving() {
        var pendingInventory = Promise.<List<InstanceInfo>>promise();
        listed.set(pendingInventory);
        var wide = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 10);
        seedOnlyCluster();
        wide.provisionNode(spec("seed-era", "default")).await();
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 AetherValue.ClusterConfigValue.clusterConfigValue(OPERATOR_TOML, "test", "1.0.0",
                                                                                   List.of(new AetherValue.TopologyEntry("east", "core", 5)),
                                                                                   3, 9, "cloud", 2L)));
        assertThat(ledger().inventoryComplete()).as("arming: the seed era never recorded completeness").isFalse();

        var first = wide.provisionNode(spec("after-config", "east"));
        var raced = wide.provisionNode(spec("raced", "east")).await();

        assertThat(raced.isFailure()).as("a reservation while the operator inventory runs must refuse: %s", raced).isTrue();
        assertThat(creates.get()).as("nothing dispatched while the inventory is pending").isEqualTo(1);

        allocatedAtCreate.set(3);
        pendingInventory.succeed(List.of(observedCore("existing-east")));
        first.await();

        assertThat(ledger().inventoryComplete()).isTrue();
        assertThat(store.getTyped(new AetherKey.CapacityReservationKey(new NodeId("existing-east")),
                                  AetherValue.CapacityReservationValue.class).map(AetherValue.CapacityReservationValue::phase))
            .as("the source's pre-existing instance is counted").isEqualTo(Option.some(AetherValue.CapacityReservationPhase.OBSERVED));
        assertThat(ledger().allocated()).as("seed-era reservation + observed instance + new reservation").isEqualTo(3);
        assertThat(creates.get()).isEqualTo(2);
    }

    private void seedOnlyCluster() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 AetherValue.ClusterConfigValue.clusterConfigValue("", "test", "1.0.0",
                                                                                   List.of(new AetherValue.TopologyEntry("", "core", 5)),
                                                                                   3, 9, "bootstrap-seed", 1L)));
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

    private static InstanceInfo observedCore(String node) {
        return new InstanceInfo(new org.pragmatica.aether.environment.InstanceId("instance-" + node),
            org.pragmatica.aether.environment.InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND,
            java.util.Map.of("aether-role", "core"), Option.some(node), Option.none());
    }

}
