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
    private final AtomicInteger listings = new AtomicInteger();
    /// What the provider's listing answers while a fleet inventory is being initialized; a test holds it pending to keep the initialization in flight.
    private final java.util.concurrent.atomic.AtomicReference<Promise<List<InstanceInfo>>> listing = new java.util.concurrent.atomic.AtomicReference<>(Promise.success(List.of()));
    /// What the provider lists for a source that has instances of its own; a source absent here answers with `listing`.
    private final Map<String, List<InstanceInfo>> sourceInstances = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<List<InstanceInfo>> inventory = new java.util.concurrent.atomic.AtomicReference<>(List.of());
    private final NodeLifecycleManager provider = new NodeLifecycleManager() {
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            creates.incrementAndGet();
            assertThat(ledger().allocated()).isEqualTo(creates.get());
            return Causes.cause("Provider timed out after accepting request").promise();
        }
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return instancesForNode(node, source); }
        @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return terminateNode(node); }
        @Override public Promise<Unit> terminateNode(NodeId node) { return Promise.unitPromise(); }
        @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
        @Override public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) { return org.pragmatica.lang.Result.success("binding"); }
        @Override public boolean isCloudManaged() { return true; }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) { return Promise.success(inventory.get()); }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) { return listing.get(); }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) {
            listings.incrementAndGet();
            return Option.option(sourceInstances.get(source.value())).fold(listing::get, Promise::success);
        }
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

    private void commitSourceConfig() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 new AetherValue.ClusterConfigValue(Option.none(), "test", "1.0.0", List.of(), 3, 3, "", 1L, 0L)));
    }

    /// A second provision that arrives while the first is still initializing the fleet inventory fails with the TYPED transient cause, which is
    /// what lets a caller that can wait (the replacement reconciler) defer instead of treating it as a refusal (#2098). Once the initialization
    /// finishes the same call is not refused for it.
    @Test
    void aProvisionDuringInventoryInitialization_failsWithTheTypedTransientCause() {
        commitSourceConfig();
        var pending = Promise.<List<InstanceInfo>>promise();

        listing.set(pending);
        var first = lifecycle.provisionNode(spec("first", "east"));
        var second = lifecycle.provisionNode(spec("second", "east")).await();

        assertThat(second.isFailure()).isTrue();
        second.onFailure(cause -> assertThat(cause).isInstanceOf(FleetInventoryInitializing.class));
        assertThat(creates.get()).as("nothing was dispatched while the inventory was being initialized").isZero();

        pending.succeed(List.of());
        first.await();
        var third = lifecycle.provisionNode(spec("third", "east")).await();

        assertThat(ledger().inventoryComplete()).isTrue();
        third.onFailure(cause -> assertThat(cause).isNotInstanceOf(FleetInventoryInitializing.class));
    }

    private static final String OPERATOR_CONFIG = """
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
        count = 3
        instance_type = "small"
        """;

    private void commitOperatorConfig() {
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 new AetherValue.ClusterConfigValue(Option.some(OPERATOR_CONFIG), "test", "1.0.0", List.of(), 3, 3, "", 2L, 0L)));
    }

    /// #2142: the seed completes the inventory for the one implicit local source. An operator configuration committed afterwards names a source
    /// whose pre-existing labelled instance was never listed; admitting on that source before listing it under-counts the fleet and buys a
    /// paid instance past the limit. The source must be inventoried before the ledger admits on it, so the existing instance fills the last slot.
    @Test
    void sourceCommittedAfterTheSeed_isInventoriedBeforeTheLedgerAdmitsOnIt() {
        var twoSlots = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 2);

        commitSourceConfig();
        twoSlots.provisionNode(spec("seeded", SourceName.DEFAULT.value())).await();
        assertThat(ledger().inventoryComplete()).as("the seed completed the inventory").isTrue();
        assertThat(ledger().allocated()).isEqualTo(1);

        sourceInstances.put("east", List.of(observedCore("preexisting")));
        commitOperatorConfig();
        var refusal = twoSlots.provisionNode(spec("second", "east")).await();

        assertThat(creates.get()).as("no create was dispatched past the limit").isEqualTo(1);
        assertThat(ledger().allocated()).as("the pre-existing instance was counted").isEqualTo(2);
        refusal.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("admitted past the limit"))
               .onFailure(cause -> assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.CAPACITY_UNAVAILABLE));
    }

    /// Positive control for the test above, and the cost bound of the fix: once a source is inventoried it is not listed again on later
    /// admissions, so the fix adds one listing per source, not one per provision.
    @Test
    void inventoriedSource_isNotListedAgainOnLaterAdmissions() {
        var twoSlots = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 5);

        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        commitOperatorConfig();
        twoSlots.provisionNode(spec("first", "east")).await();
        var afterFirst = listings.get();
        twoSlots.provisionNode(spec("second", "east")).await();

        assertThat(afterFirst).as("the committed source was listed on first admission").isEqualTo(1);
        assertThat(listings.get()).as("and not again on the second").isEqualTo(afterFirst);
        assertThat(creates.get()).isEqualTo(2);
    }

    private static InstanceInfo observedCore(String node) {
        return new InstanceInfo(new org.pragmatica.aether.environment.InstanceId("instance-" + node),
            org.pragmatica.aether.environment.InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND,
            java.util.Map.of("aether-role", "core"), Option.some(node), Option.none());
    }

}
