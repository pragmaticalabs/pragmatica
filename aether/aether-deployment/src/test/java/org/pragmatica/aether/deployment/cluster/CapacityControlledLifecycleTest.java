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

/// Every test here either returns at once or waits on a promise the test itself controls: a ceiling or guard that is missing must fail the test, not hang it.
@org.junit.jupiter.api.Timeout(20)
class CapacityControlledLifecycleTest {
    private static final NodeId CORE = new NodeId("core");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicInteger listings = new AtomicInteger();
    private final java.util.Set<String> queryFailures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Option<Promise<InstanceInfo>> createOutcome = Option.none();
    private java.util.function.Function<SourceName, String> bindingOf = _ -> "binding";
    /// What the provider's listing answers while a fleet inventory is being initialized; a test holds it pending to keep the initialization in flight.
    private final java.util.concurrent.atomic.AtomicReference<Promise<List<InstanceInfo>>> listing = new java.util.concurrent.atomic.AtomicReference<>(Promise.success(List.of()));
    /// What the provider lists for a source that has instances of its own; a source absent here answers with `listing`.
    /// Sources whose provider listing fails.
    private final java.util.Set<String> failingSources = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, List<InstanceInfo>> sourceInstances = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<List<InstanceInfo>> inventory = new java.util.concurrent.atomic.AtomicReference<>(List.of());
    private final NodeLifecycleManager provider = new NodeLifecycleManager() {
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            creates.incrementAndGet();
            assertThat(ledger().allocated()).as("the reservation is committed before the provider is asked").isGreaterThanOrEqualTo(creates.get());
            return createOutcome.or(() -> Causes.cause("Provider timed out after accepting request").promise());
        }
        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return instancesForNode(node, source); }
        @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return terminateNode(node); }
        @Override public Promise<Unit> terminateNode(NodeId node) { return Promise.unitPromise(); }
        @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
        @Override public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) { return org.pragmatica.lang.Result.success(bindingOf.apply(source)); }
        @Override public boolean isCloudManaged() { return true; }
        @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) {
            lookups.incrementAndGet();
            return queryFailures.contains(node.id()) ? Causes.cause("transient inventory failure").promise() : Promise.success(inventory.get());
        }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            listings.incrementAndGet();
            return listing.get();
        }
        @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) {
            listings.incrementAndGet();
            if (failingSources.contains(source.value())) {
                return Causes.<List<InstanceInfo>>cause(source.value() + " credentials rejected").promise();
            }
            return Option.option(sourceInstances.get(source.value())).fold(listing::get, Promise::success);
        }
    };
    private final CapacityControlledLifecycle lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
        commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 1);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private static final String EAST_TOML = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "forge"
        [source.east.core]
        count = 3
        """;

    private void commitEastConfig(String toml) {
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
    }

    /// A committed configuration is replaced by a leader transaction (a plain put is only the first commit).
    private void replaceEastConfig(String toml) {
        var key = AetherKey.ClusterConfigKey.CURRENT;
        var before = store.get(key);
        seed(new KVCommand.LeaderTransaction<>(key, java.util.UUID.randomUUID().toString(), LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(key, before, Option.some(
                new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(), 3, 3, "forge", 2, 0))))));
    }

    private void initialize(int allocated) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        commitEastConfig(EAST_TOML);
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

    private void observeReservation(String node, String role, AetherValue.CapacityReservationPhase phase) {
        var key = new AetherKey.CapacityReservationKey(new NodeId(node));
        seed(new KVCommand.LeaderTransaction<>(key, node, LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.none(), Option.some(
                new AetherValue.CapacityReservationValue("east", "binding", role, phase))))));
    }

    /// #1404 / D1: what the leader reconciler counts as capacity. An uncertain create is DISPATCHED, a provider-listed one OBSERVED,
    /// and a released one is not capacity at all; roles are told apart.
    @Test
    void allocatedNodes_reportsEveryUnreleasedReservationOfTheRoleWithItsPhase() {
        initialize(0);
        observeReservation("dispatched-core", "core", AetherValue.CapacityReservationPhase.DISPATCHED);
        observeReservation("observed-core", "core", AetherValue.CapacityReservationPhase.OBSERVED);
        observeReservation("released-core", "core", AetherValue.CapacityReservationPhase.RELEASED);
        observeReservation("a-worker", "worker", AetherValue.CapacityReservationPhase.OBSERVED);

        assertThat(lifecycle.allocatedNodes("core")).containsOnly(
            Map.entry(new NodeId("dispatched-core"), AetherValue.CapacityReservationPhase.DISPATCHED),
            Map.entry(new NodeId("observed-core"), AetherValue.CapacityReservationPhase.OBSERVED));
        assertThat(lifecycle.allocatedNodes("worker")).containsOnlyKeys(new NodeId("a-worker"));
    }

    /// F6 (s25), the removal half: provider-confirmed absence deletes the dead worker's NodePlacement in the transaction that releases its slot.
    @Test
    void confirmedProviderAbsenceRemovesStalePlacementAlongsideReservation() {
        initialize(1);
        var node = new NodeId("dead-worker");
        var key = new AetherKey.CapacityReservationKey(node);
        observeReservation("dead-worker", "worker", AetherValue.CapacityReservationPhase.OBSERVED);
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(node), new AetherValue.NodePlacementValue("east", Option.none(), "instance")));

        lifecycle.instancesForNode(node, SourceName.sourceName("east").unwrap()).await().unwrap();

        assertThat(store.get(key).isEmpty()).isTrue();
        assertThat(store.get(new AetherKey.NodePlacementKey(node)).isEmpty()).isTrue();
        assertThat(ledger().allocated()).isZero();
    }

    /// A refused create's RELEASED evidence is cleaned up through the ledger without a placement to remove.
    @Test
    void releaseOfRefusalEvidence_leavesAnUnrelatedPlacementAlone() {
        initialize(1);
        var node = new NodeId("refused");
        observeReservation("refused", "worker", AetherValue.CapacityReservationPhase.RELEASED);
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(node), new AetherValue.NodePlacementValue("east", Option.none(), "instance")));

        lifecycle.reconcileRefusals().await().unwrap();

        assertThat(store.get(new AetherKey.CapacityReservationKey(node)).isEmpty()).isTrue();
        assertThat(store.get(new AetherKey.NodePlacementKey(node)).isPresent()).isTrue();
    }

    /// The continuing inventory adopts what the provider lists and, once the provider has listed a node and then stops listing it, releases
    /// its slot. A DISPATCHED reservation (the create may have happened) is never released by an empty listing.
    @Test
    void continuingInventory_releasesObservedAbsenceButKeepsUncertainCreate() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();

        lifecycle.reconcileInventory().await().unwrap();
        assertThat(ledger().allocated()).as("an empty listing does not release a create that may have happened").isEqualTo(1);
        assertThat(lookups.get()).as("an uncertain create is not even looked up for absence").isZero();
        assertThat(store.getTyped(new AetherKey.CapacityReservationKey(new NodeId("first")), AetherValue.CapacityReservationValue.class)
            .unwrap().phase()).isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);

        listing.set(Promise.success(List.of(observedCore("first"))));
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(store.getTyped(new AetherKey.CapacityReservationKey(new NodeId("first")), AetherValue.CapacityReservationValue.class)
            .unwrap().phase()).as("the listing adopts the create").isEqualTo(AetherValue.CapacityReservationPhase.OBSERVED);

        listing.set(Promise.success(List.of()));
        inventory.set(List.of());
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(ledger().allocated()).as("observed once, then confirmed absent").isZero();
    }

    /// Absence is confirmed against the listing of the allocation's own source: a node another source holds is not looked up, and a node the
    /// listing shows is not looked up either.
    @Test
    void continuingInventory_confirmsOnlyTheListedSourcesUnlistedAllocations() {
        initialize(2);
        observeReservation("listed", "worker", AetherValue.CapacityReservationPhase.OBSERVED);
        var elsewhere = new AetherKey.CapacityReservationKey(new NodeId("elsewhere"));
        seed(new KVCommand.LeaderTransaction<>(elsewhere, "elsewhere", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(elsewhere, Option.none(), Option.some(
                new AetherValue.CapacityReservationValue("west", "binding", "worker", AetherValue.CapacityReservationPhase.OBSERVED))))));
        listing.set(Promise.success(List.of(observedCore("listed"))));

        lifecycle.reconcileInventory().await().unwrap();

        assertThat(lookups.get()).as("neither the listed node nor the other source's node is looked up").isZero();
        assertThat(ledger().allocated()).isEqualTo(2);
    }

    /// Sources reconcile in isolated lanes: one whose listing fails leaves its allocations for the next pass and does not stop the sources after it.
    @Test
    void continuingInventory_anUnavailableSourceDoesNotStopTheNextSource() {
        initialize(1);
        seed(new KVCommand.LeaderTransaction<>(AetherKey.ClusterConfigKey.CURRENT, "two-sources", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(AetherKey.ClusterConfigKey.CURRENT, store.get(AetherKey.ClusterConfigKey.CURRENT), Option.some(
                new AetherValue.ClusterConfigValue(Option.some(EAST_TOML + """
                    [source.west]
                    type = "forge"
                    [source.west.worker]
                    count = 1
                    """), "test", "1.0.0", List.of(), 3, 3, "forge", 2, 0))))));
        var gone = new AetherKey.CapacityReservationKey(new NodeId("gone"));
        seed(new KVCommand.LeaderTransaction<>(gone, "gone", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(gone, Option.none(), Option.some(
                new AetherValue.CapacityReservationValue("west", "binding", "worker", AetherValue.CapacityReservationPhase.OBSERVED))))));
        failingSources.add("east");
        listing.set(Promise.success(List.of()));

        lifecycle.reconcileInventory().await().unwrap();

        assertThat(store.get(gone).isEmpty()).as("west's absent allocation was confirmed and released although east's listing failed").isTrue();
        assertThat(ledger().allocated()).isZero();
    }

    /// A failed or partial listing establishes no absence.
    @Test
    void continuingInventory_aFailedListingReleasesNothing() {
        initialize(1);
        observeReservation("first", "worker", AetherValue.CapacityReservationPhase.OBSERVED);
        listing.set(Causes.cause("provider unavailable").promise());

        lifecycle.reconcileInventory().await();

        assertThat(ledger().allocated()).isEqualTo(1);
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("first"))).isPresent()).isTrue();
    }

    @Test
    void continuingInventory_doesNothingWithoutLeadership() {
        initialize(1);
        observeReservation("first", "worker", AetherValue.CapacityReservationPhase.OBSERVED);
        var follower = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> false, () -> 1);

        follower.reconcileInventory().await().unwrap();

        assertThat(listings.get()).isZero();
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    /// A pass already in flight is not stacked: the provider is not asked twice while a listing is outstanding.
    @Test
    void continuingInventory_stacksNoSecondPassWhileOneIsOutstanding() {
        initialize(0);
        var pending = Promise.<List<InstanceInfo>>promise();
        listing.set(pending);

        var first = lifecycle.reconcileInventory();
        var before = listings.get();
        lifecycle.reconcileInventory().await().unwrap();

        assertThat(listings.get()).isEqualTo(before);
        pending.succeed(List.of());
        first.await().unwrap();
    }

    /// Source admission is bounded: one create per source binding at a time. The second request defers before it reserves capacity or
    /// reaches the provider, with the typed pre-dispatch refusal.
    @Test
    void busySource_defersBeforeAnotherReservationOrProviderCall() {
        initialize(0);
        var wide = roomy(2, org.pragmatica.aether.config.cluster.SourceProfile.DEFAULT_REPLACEMENT_CEILING);
        var held = Promise.<InstanceInfo>promise();
        createOutcome = Option.some(held);
        var first = wide.provisionNode(spec("first", "east"));

        var refused = wide.provisionNode(spec("second", "east")).await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.CAPACITY_UNAVAILABLE));
        assertThat(creates.get()).as("capacity for a second create exists; the busy source alone refused it").isEqualTo(1);
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("second"))).isEmpty()).isTrue();
        held.fail(Causes.cause("ambiguous result"));
        assertThat(first.await().isFailure()).isTrue();
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    /// At most thirty-two missing allocations are confirmed per pass, and the start rotates, so allocations whose lookup keeps failing
    /// cannot starve the ones behind them.
    @Test
    void boundedAbsencePass_rotatesPastFailuresInsteadOfStarvingLaterAllocations() {
        initialize(64);
        for (int index = 0; index < 64; index++) {
            var node = "node-%02d".formatted(index);
            observeReservation(node, "worker", AetherValue.CapacityReservationPhase.OBSERVED);
            if (index < 32) queryFailures.add(node);
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

    /// A ledger restored without the committed configuration it was inventoried under cannot admit creates.
    @Test
    void restoredLedgerWithoutConfiguration_cannotAdmitCreates() {
        initialize(0);
        seed(new KVCommand.Remove<>(AetherKey.ClusterConfigKey.CURRENT));

        var refused = lifecycle.provisionNode(spec("first", "east")).await();

        refused.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("admitted without configuration"));
        refused.onFailure(cause -> assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.INVENTORY_UNAVAILABLE));
        assertThat(creates.get()).isZero();
        assertThat(ledger().allocated()).isZero();
    }

    private static final String WEST_BLOCK = """
        [source.west]
        type = "forge"
        [source.west.worker]
        count = 1
        """;

    /// Inventory is per source (#2142): a configuration commit that names a source nobody has listed yet is inventoried before the next
    /// admission reserves, while an admission under an unchanged configuration reuses the inventory taken for the one before it.
    @Test
    void configurationNamingAnUninventoriedSource_isInventoriedAgainBeforeAdmission() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();
        var afterFirst = listings.get();

        lifecycle.provisionNode(spec("second", "east")).await();
        assertThat(listings.get()).as("same configuration: the inventory taken for the first admission serves the second").isEqualTo(afterFirst);

        replaceEastConfig(EAST_TOML + WEST_BLOCK);
        lifecycle.provisionNode(spec("third", "east")).await();

        assertThat(listings.get()).as("the newly named source is inventoried before admission").isGreaterThan(afterFirst);
    }

    /// The other half: a committed change that names no new source leaves nothing uninventoried, so it costs no listing.
    @Test
    void configurationChangeNamingNoNewSource_isNotInventoriedAgain() {
        initialize(0);
        var wide = roomy(5, org.pragmatica.aether.config.cluster.SourceProfile.DEFAULT_REPLACEMENT_CEILING);
        wide.provisionNode(spec("first", "east")).await();
        var afterFirst = listings.get();

        replaceEastConfig(EAST_TOML.replace("count = 3", "count = 4"));
        wide.provisionNode(spec("second", "east")).await();

        assertThat(listings.get()).as("every committed source was already inventoried").isEqualTo(afterFirst);
        assertThat(creates.get()).isEqualTo(2);
    }

    /// A failure to inventory is the typed pre-dispatch refusal, not an opaque cause.
    @Test
    void failedAdmissionInventory_isTheTypedRefusal() {
        initialize(0);
        replaceEastConfig(EAST_TOML.replace("count = 3", "count = 5"));
        listing.set(Causes.cause("provider unavailable").promise());

        var refused = lifecycle.provisionNode(spec("first", "east")).await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.INVENTORY_UNAVAILABLE));
        assertThat(creates.get()).isZero();
    }

    private CapacityControlledLifecycle roomy(int limit, org.pragmatica.lang.io.TimeSpan ceiling) {
        return CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> limit, ceiling);
    }

    /// At most four source bindings create at once in a process; the fifth defers before it reserves or reaches the provider, and a
    /// slot freed by a completed create is available again.
    @Test
    void admission_isBoundedToFourSourceBindingsAtOnce() {
        initialize(0);
        bindingOf = source -> "binding-" + source.value();
        var wide = roomy(10, org.pragmatica.aether.config.cluster.SourceProfile.DEFAULT_REPLACEMENT_CEILING);
        var held = Promise.<InstanceInfo>promise();
        createOutcome = Option.some(held);
        var pending = new java.util.ArrayList<Promise<InstanceInfo>>();
        for (var name : List.of("s1", "s2", "s3", "s4")) pending.add(wide.provisionNode(spec("node-" + name, name)));

        var refused = wide.provisionNode(spec("node-s5", "s5")).await();

        assertThat(creates.get()).isEqualTo(4);
        refused.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a fifth binding was admitted"));
        refused.onFailure(cause -> assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.CAPACITY_UNAVAILABLE));
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("node-s5"))).isEmpty()).isTrue();
        held.fail(Causes.cause("ambiguous result"));
        pending.forEach(Promise::await);
        createOutcome = Option.none();
        assertThat(wide.provisionNode(spec("node-s5", "s5")).await().isFailure()).as("reached the provider, which timed out").isTrue();
        assertThat(creates.get()).isEqualTo(5);
    }

    /// A completed create frees its binding: the next admission on the same binding reaches the provider.
    @Test
    void completedCreate_freesItsSourceBinding() {
        initialize(0);
        var wide = roomy(2, org.pragmatica.aether.config.cluster.SourceProfile.DEFAULT_REPLACEMENT_CEILING);

        wide.provisionNode(spec("first", "east")).await();
        wide.provisionNode(spec("second", "east")).await();

        assertThat(creates.get()).isEqualTo(2);
    }

    /// A create the provider never answers does not hold its binding for ever: at the ceiling it fails and the binding is free.
    @Test
    void hungCreate_releasesItsSourceBindingAtTheCeiling() {
        initialize(0);
        var wide = roomy(2, org.pragmatica.lang.io.TimeSpan.timeSpan(100).millis());
        createOutcome = Option.some(Promise.<InstanceInfo>promise());

        var hung = wide.provisionNode(spec("first", "east")).await();
        createOutcome = Option.none();
        wide.provisionNode(spec("second", "east")).await();

        assertThat(hung.isFailure()).isTrue();
        assertThat(creates.get()).as("the second admission reached the provider: the binding was free").isEqualTo(2);
    }

    /// An inventory the provider never answers does not hold the initialization for ever: at the ceiling it fails and the next
    /// admission initializes again instead of being told that initialization is in progress.
    @Test
    void hungInventory_releasesTheInitializationAtTheCeiling() {
        initialize(0);
        var wide = roomy(2, org.pragmatica.lang.io.TimeSpan.timeSpan(100).millis());
        listing.set(Promise.<List<InstanceInfo>>promise());

        assertThat(wide.provisionNode(spec("first", "east")).await().isFailure()).isTrue();
        listing.set(Promise.success(List.of()));
        var second = wide.provisionNode(spec("second", "east")).await();

        assertThat(creates.get()).as("the second admission initialized the inventory and reached the provider").isEqualTo(1);
        second.onFailure(cause -> assertThat(cause).isNotInstanceOf(FleetInventoryInitializing.class));
    }

    /// A configuration committed while the inventory was being taken is not the configuration it was taken under: the admission is refused
    /// before it reserves.
    @Test
    void configurationCommittedDuringInventory_refusesTheAdmission() {
        initialize(0);
        var pending = Promise.<List<InstanceInfo>>promise();
        listing.set(pending);

        var first = lifecycle.provisionNode(spec("first", "east"));
        replaceEastConfig(EAST_TOML.replace("count = 3", "count = 4"));
        pending.succeed(List.of());
        var refused = first.await();

        refused.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("admitted under a stale inventory"));
        refused.onFailure(cause -> assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.CAPACITY_UNAVAILABLE));
        assertThat(creates.get()).isZero();
        assertThat(ledger().allocated()).isZero();
    }

    /// The reservation commit witnesses the configuration it was admitted under: a configuration committed between the admission check and
    /// the commit rejects the transaction, so no reservation is written and the provider is not asked.
    @Test
    void configurationCommittedBeforeTheReservationCommit_rejectsTheReservation() {
        initialize(0);
        var raced = new java.util.concurrent.atomic.AtomicBoolean();
        var racing = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store, commands -> {
            if (commands.stream().anyMatch(this::reservesCapacity) && raced.compareAndSet(false, true)) {
                replaceEastConfig(EAST_TOML.replace("count = 3", "count = 4"));
            }
            return Promise.success(store.process(store.createBatch(commands)));
        }, () -> true, () -> 1);

        var refused = racing.provisionNode(spec("first", "east")).await();

        assertThat(raced.get()).as("the race was staged").isTrue();
        refused.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("reserved under a stale configuration"));
        refused.onFailure(cause -> assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.CAPACITY_UNAVAILABLE));
        assertThat(creates.get()).isZero();
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("first"))).isEmpty()).isTrue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean reservesCapacity(KVCommand command) {
        return command instanceof KVCommand.LeaderTransaction transaction
               && ((List<KVCommand.Mutation<AetherKey, AetherValue>>) transaction.mutations()).stream()
                                                                                               .anyMatch(mutation -> mutation.key() instanceof AetherKey.CapacityReservationKey);
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

    private static String operatorConfig(String source) {
        return """
            config_version = "1.0.0"
            [cluster]
            name = "test"
            version = "1.0.0"
            [source.%s]
            type = "cloud"
            provider = "hetzner"
            credentials = "token"
            region = "region"
            [source.%s.core]
            count = 3
            instance_type = "small"
            """.formatted(source, source);
    }

    private long configVersion = 2L;

    private void commitOperatorConfig() {
        commitOperatorConfig("east");
    }

    private void commitOperatorConfig(String source) {
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 new AetherValue.ClusterConfigValue(Option.some(operatorConfig(source)), "test", "1.0.0", List.of(), 3, 3, "", configVersion++, 0L)));
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

    /// Leader loss: a source listed while this instance is not the active leader is returned unobserved, so it must NOT
    /// be marked inventoried; once leadership is back the source is listed and counted before admission.
    @Test
    void sourceListedWithoutLeadership_isInventoriedAgainOnceLeaderAndNeverAdmittedUncounted() {
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        var twoSlots = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), leading::get, () -> 2);

        commitSourceConfig();
        twoSlots.provisionNode(spec("seeded", SourceName.DEFAULT.value())).await();
        sourceInstances.put("east", List.of(observedCore("preexisting")));
        commitOperatorConfig();
        leading.set(false);
        var offLeader = twoSlots.provisionNode(spec("second", "east")).await();
        leading.set(true);
        var refusal = twoSlots.provisionNode(spec("third", "east")).await();

        assertThat(offLeader.isFailure()).as("nothing is admitted while not the leader").isTrue();
        assertThat(creates.get()).as("no create was dispatched past the limit").isEqualTo(1);
        assertThat(ledger().allocated()).as("the pre-existing instance was counted once leader again").isEqualTo(2);
        assertThat(refusal.isFailure()).isTrue();
    }

    /// Even a source with nothing on it is not "inventoried" by a listing taken while not the active leader: nothing was
    /// observed under a term, so the source is listed again when leadership returns.
    @Test
    void emptySourceListedWithoutLeadership_isListedAgainOnceLeader() {
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        var twoSlots = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), leading::get, () -> 2);

        commitSourceConfig();
        twoSlots.provisionNode(spec("seeded", SourceName.DEFAULT.value())).await();
        commitOperatorConfig();
        var before = listings.get();
        leading.set(false);
        twoSlots.provisionNode(spec("second", "east")).await();
        leading.set(true);
        twoSlots.provisionNode(spec("third", "east")).await();

        assertThat(listings.get() - before).as("east listed once off-leader and once again as leader").isEqualTo(2);
    }

    /// Termination must not depend on listing an unrelated, newly committed source.
    @Test
    void terminate_succeeds_whenANewlyCommittedSourceCannotBeListed() {
        commitSourceConfig();
        lifecycle.provisionNode(spec("seeded", SourceName.DEFAULT.value())).await();
        commitOperatorConfig();
        failingSources.add("east");
        var node = new NodeId("seeded");

        var terminated = lifecycle.terminateNode(node, SourceName.DEFAULT).await();

        assertThat(terminated.isSuccess()).as("terminate: %s", terminated).isTrue();
        assertThat(listings.get()).as("east was never listed for a termination").isEqualTo(1);
    }

    /// The binding-checked overload carries the same contract: it must not list an unrelated, newly committed source.
    @Test
    void terminateWithBinding_succeeds_whenANewlyCommittedSourceCannotBeListed() {
        commitSourceConfig();
        lifecycle.provisionNode(spec("seeded", SourceName.DEFAULT.value())).await();
        commitOperatorConfig();
        failingSources.add("east");
        var node = new NodeId("seeded");

        var terminated = lifecycle.terminateNode(node, SourceName.DEFAULT, "binding").await();

        assertThat(terminated.isSuccess()).as("terminate with binding: %s", terminated).isTrue();
        assertThat(listings.get()).as("east was never listed for a termination").isEqualTo(1);
    }

    /// A source removed from the configuration and later committed again is listed again: instances created on it in
    /// between were never counted.
    @Test
    void sourceRemovedAndReAdded_isListedAgain() {
        var tenSlots = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 10);

        commitSourceConfig();
        tenSlots.provisionNode(spec("seeded", SourceName.DEFAULT.value())).await();
        commitOperatorConfig("east");
        tenSlots.provisionNode(spec("e1", "east")).await();
        commitOperatorConfig("west");
        tenSlots.provisionNode(spec("w1", "west")).await();
        sourceInstances.put("east", List.of(observedCore("created-while-east-was-absent")));
        commitOperatorConfig("east");
        tenSlots.provisionNode(spec("e2", "east")).await();

        assertThat(ledger().allocated()).as("seeded, e1, w1, the late instance, e2").isEqualTo(5);
    }

    /// The inventory mark can be lost after every source was listed (the commit is refused). The retry must still reach
    /// the mark: sources all seen is not the same as the ledger being complete.
    @Test
    void lostCompletionMark_isRetriedEvenWhenEverySourceWasAlreadyListed() {
        var refuseNext = new java.util.concurrent.atomic.AtomicBoolean(true);
        var flaky = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> refuseNext.getAndSet(false)
                        ? Promise.success(List.<Object>of())
                        : Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 2);

        commitSourceConfig();
        var first = flaky.provisionNode(spec("first", SourceName.DEFAULT.value())).await();
        var second = flaky.provisionNode(spec("second", SourceName.DEFAULT.value())).await();

        assertThat(first.isFailure()).as("the first attempt lost the completion mark").isTrue();
        assertThat(ledger().inventoryComplete()).as("the retry completed it: %s", second).isTrue();
        assertThat(creates.get()).as("and the retry was admitted").isEqualTo(1);
    }

    /// Termination needs only a ledger that is inventoried at all: a configuration committed afterwards, naming a source that cannot be
    /// listed, does not block the termination of a node.
    @Test
    void terminate_succeeds_whenTheConfigurationCommittedSinceCannotBeInventoried() {
        initialize(1);
        observeReservation("x", "worker", AetherValue.CapacityReservationPhase.OBSERVED);
        replaceEastConfig(EAST_TOML + WEST_BLOCK);
        failingSources.add("east");
        failingSources.add("west");

        var result = lifecycle.terminateNode(new NodeId("x"), SourceName.sourceName("east").unwrap()).await();

        assertThat(result.isSuccess()).as("terminate: %s", result).isTrue();
        assertThat(listings.get()).as("no source was listed for a termination").isZero();
    }

    /// A new leader instance (an empty inventoried set) terminates without listing the fleet first.
    @Test
    void terminate_onANewLeaderInstance_doesNotListTheFleet() {
        initialize(1);
        observeReservation("x", "worker", AetherValue.CapacityReservationPhase.OBSERVED);
        var fresh = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 1);

        var result = fresh.terminateNode(new NodeId("x"), SourceName.sourceName("east").unwrap()).await();

        assertThat(result.isSuccess()).as("terminate: %s", result).isTrue();
        assertThat(listings.get()).as("provider listings caused by one termination on a fresh instance").isZero();
    }

    /// Leadership lost between the reservation commit and the provider call: the create provably never reached the provider, so its
    /// DISPATCHED reservation is not an uncertain allocation. It cannot be released without the lost leadership, so it is released through
    /// the refusal path once the instance leads again - and not before.
    @Test
    void leadershipLostBeforeDispatch_releasesTheReservationOnceLeaderAgain() {
        initialize(0);
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        var key = new AetherKey.CapacityReservationKey(new NodeId("first"));
        var lost = new java.util.concurrent.atomic.AtomicBoolean();
        var losing = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store, commands -> {
            var results = store.process(store.createBatch(commands));
            if (commands.stream().anyMatch(this::reservesCapacity) && lost.compareAndSet(false, true)) {
                leading.set(false);
            }
            return Promise.success(results);
        }, leading::get, () -> 1);

        var outcome = losing.provisionNode(spec("first", "east")).await();

        assertThat(outcome.isFailure()).isTrue();
        assertThat(creates.get()).as("the provider was never asked to create").isZero();
        losing.reconcileRefusals().await().unwrap();
        assertThat(store.get(key).isPresent()).as("not released while this instance is not the leader").isTrue();
        leading.set(true);
        losing.reconcileInventory().await().unwrap();
        losing.reconcileRefusals().await().unwrap();
        assertThat(losing.allocatedNodes("worker")).as("a create that never reached the provider is not capacity").isEmpty();
        assertThat(store.get(key).isEmpty()).isTrue();
        assertThat(ledger().allocated()).isZero();
    }

    /// Control for the one above: a create that did reach the provider and timed out stays an uncertain allocation, whatever
    /// the reconciliation passes do.
    @Test
    void createReachingTheProvider_staysUncertain_throughRefusalReconciliation() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();

        lifecycle.reconcileInventory().await().unwrap();
        lifecycle.reconcileRefusals().await().unwrap();

        assertThat(creates.get()).isEqualTo(1);
        assertThat(lifecycle.allocatedNodes("worker")).containsOnlyKeys(new NodeId("first"));
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    /// Absence is confirmed only for allocations of the binding the listing was taken from: an allocation bound to another binding of
    /// the same source is not looked up at all.
    @Test
    void continuingInventory_doesNotLookUpAnAllocationBoundToAnotherBinding() {
        initialize(1);
        var other = new AetherKey.CapacityReservationKey(new NodeId("old"));
        seed(new KVCommand.LeaderTransaction<>(other, "old", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(other, Option.none(), Option.some(
                new AetherValue.CapacityReservationValue("east", "old-binding", "worker", AetherValue.CapacityReservationPhase.OBSERVED))))));
        listing.set(Promise.success(List.of()));

        lifecycle.reconcileInventory().await().unwrap();

        assertThat(lookups.get()).as("the other binding's allocation is not looked up").isZero();
        assertThat(store.get(other).isPresent()).isTrue();
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    private static final NodeId SUCCESSOR = new NodeId("successor");

    /// A lifecycle whose leadership drops right after each of the next `flips` reservation commits.
    private CapacityControlledLifecycle losingLeadership(java.util.concurrent.atomic.AtomicBoolean leading, AtomicInteger flips) {
        return CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store, commands -> {
            var results = store.process(store.createBatch(commands));
            if (commands.stream().anyMatch(this::reservesCapacity) && flips.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                leading.set(false);
            }
            return Promise.success(results);
        }, leading::get, () -> 1);
    }

    /// Another node leads from term 2 on, with a lifecycle of its own over the same store.
    private CapacityControlledLifecycle successor(java.util.List<String> events) {
        return successor(events, commands -> Promise.success(store.process(store.createBatch(commands))));
    }

    private static final long GRACE_MS = 1_000L;
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);

    /// A lifecycle with a stepped clock and a one-second create ceiling, which is also the grace an earlier-term allocation is given.
    private CapacityControlledLifecycle clocked(NodeId self, java.util.function.BooleanSupplier leading,
                                                java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply) {
        return CapacityControlledLifecycle.capacityControlledLifecycle(provider, self, store, apply, leading, () -> 1,
            org.pragmatica.lang.io.TimeSpan.timeSpan(GRACE_MS).millis(), now::get);
    }

    private CapacityControlledLifecycle successor(java.util.List<String> events,
                                                  java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SUCCESSOR, 2)));
        var next = clocked(SUCCESSOR, () -> true, apply);
        next.setOperatorWarningSink(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(warning -> events.add(warning.code().code())));
        return next;
    }

    private AetherValue.CapacityReservationPhase phaseOf(String node) {
        return store.getTyped(new AetherKey.CapacityReservationKey(new NodeId(node)), AetherValue.CapacityReservationValue.class)
                    .unwrap().phase();
    }

    /// An allocation dispatched before this leader gained leadership is not released by the first pass that fails to see its instance:
    /// the new leader needs three consecutive misses AND the grace since it first found the allocation unmatched (a slow cloud boot is
    /// never released mid-boot). Then it releases it, with the no-create evidence and an operator event.
    @Test
    void dispatchOfAnEarlierTerm_isReleasedByAnotherLeaderOnlyAfterConsecutiveMissesAndTheGrace() {
        initialize(0);
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        var abandoned = losingLeadership(leading, new AtomicInteger(1)).provisionNode(spec("first", "east")).await();
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var next = successor(events);

        assertThat(abandoned.isFailure()).isTrue();
        assertThat(creates.get()).as("the provider was never asked to create").isZero();
        for (int pass = 1; pass < CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS; pass++) {
            next.reconcileInventory().await().unwrap();
            assertThat(phaseOf("first")).as("pass %d: still uncertain", pass).isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
            assertThat(ledger().allocated()).isEqualTo(1);
        }
        next.reconcileInventory().await().unwrap();
        assertThat(phaseOf("first")).as("three misses inside the grace: a slow boot is not released").isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
        now.addAndGet(GRACE_MS + 1);
        next.reconcileInventory().await().unwrap();

        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("first"))).isEmpty()).isTrue();
        assertThat(ledger().allocated()).isZero();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !events.isEmpty());
        assertThat(events).containsExactly("capacity-dispatch-released");
    }

    /// An in-flight boot that shows up while the confirmation runs is matched to its allocation and never released.
    @Test
    void dispatchOfAnEarlierTerm_whoseInstanceAppearsDuringTheConfirmation_isAdoptedNotReleased() {
        initialize(0);
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        losingLeadership(leading, new AtomicInteger(1)).provisionNode(spec("first", "east")).await();
        var events = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var next = successor(events);

        next.reconcileInventory().await().unwrap();
        next.reconcileInventory().await().unwrap();
        inventory.set(List.of(observedCore("first")));
        for (int pass = 0; pass < 2 * CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS; pass++) {
            next.reconcileInventory().await().unwrap();
        }

        assertThat(phaseOf("first")).isEqualTo(AetherValue.CapacityReservationPhase.OBSERVED);
        assertThat(ledger().allocated()).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    /// A create this leader dispatched itself, in its own term, is in flight: it is never confirmed absent, however many passes run.
    @Test
    void dispatchOfTheCurrentTerm_isNeverConfirmedAbsent() {
        initialize(0);
        lifecycle.provisionNode(spec("first", "east")).await();

        for (int pass = 0; pass < 2 * CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS; pass++) {
            lifecycle.reconcileInventory().await().unwrap();
        }

        assertThat(phaseOf("first")).as("dispatched by this leader in this term: in flight, not abandoned").isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
        assertThat(ledger().allocated()).isEqualTo(1);
        assertThat(lookups.get()).isZero();
    }

    /// An earlier term's allocation is examined only after the new leader has inventoried every committed source: a source it could not
    /// list yet may be holding the instance.
    @Test
    void dispatchOfAnEarlierTerm_isNotExaminedWhileACommittedSourceIsUninventoried() {
        initialize(0);
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        losingLeadership(leading, new AtomicInteger(1)).provisionNode(spec("first", "east")).await();
        replaceEastConfig(EAST_TOML + WEST_BLOCK);
        failingSources.add("west");
        var next = successor(new java.util.concurrent.CopyOnWriteArrayList<>());

        for (int pass = 0; pass < 2 * CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS; pass++) {
            next.reconcileInventory().await().unwrap();
        }

        assertThat(phaseOf("first")).isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
        assertThat(lookups.get()).as("no lookup while west is uninventoried").isZero();
    }

    /// A same-leader re-commit advances the view sequence while the node keeps leading: its own in-flight create is not an earlier term's.
    @Test
    void sameLeaderRecommit_doesNotMakeItsOwnInFlightCreateEarlierTerm() {
        initialize(0);
        var own = clocked(CORE, () -> true, commands -> Promise.success(store.process(store.createBatch(commands))));
        own.provisionNode(spec("first", "east")).await();
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 2)));
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 3)));

        for (int pass = 0; pass < 2 * CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS; pass++) {
            now.addAndGet(2 * GRACE_MS);
            own.reconcileInventory().await().unwrap();
        }

        assertThat(phaseOf("first")).isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
        assertThat(ledger().allocated()).isEqualTo(1);
    }

    /// Leadership lost and regained (A -> B -> A): what the node dispatched before is now an earlier leadership's, and goes through the
    /// confirmation.
    @Test
    void leadershipRegained_makesWhatItDispatchedBeforeAnEarlierTermsDispatch() {
        initialize(0);
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        var again = clocked(CORE, leading::get, commands -> Promise.success(store.process(store.createBatch(commands))));
        again.provisionNode(spec("first", "east")).await();
        leading.set(false);
        again.reconcileInventory().await().unwrap();
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 3)));
        leading.set(true);

        for (int pass = 1; pass < CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS; pass++) {
            now.addAndGet(2 * GRACE_MS);
            again.reconcileInventory().await().unwrap();
            assertThat(phaseOf("first")).as("pass %d", pass).isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
        }
        now.addAndGet(2 * GRACE_MS);
        again.reconcileInventory().await().unwrap();

        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("first"))).isEmpty()).isTrue();
    }

    /// "Consecutive": a sighting between misses restarts the count, even when the adoption commit of that sighting is refused.
    @Test
    void earlierTermMisses_mustBeConsecutive_aSightingRestartsTheCount() {
        initialize(0);
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        losingLeadership(leading, new AtomicInteger(1)).provisionNode(spec("first", "east")).await();
        var refuseAdoption = new java.util.concurrent.atomic.AtomicBoolean();
        var next = successor(new java.util.concurrent.CopyOnWriteArrayList<>(),
            commands -> refuseAdoption.get() && commands.stream().anyMatch(this::reservesCapacity)
                        ? Promise.success(List.<Object>of())
                        : Promise.success(store.process(store.createBatch(commands))));

        now.addAndGet(2 * GRACE_MS);
        next.reconcileInventory().await().unwrap();
        inventory.set(List.of(observedCore("first")));
        refuseAdoption.set(true);
        now.addAndGet(2 * GRACE_MS);
        next.reconcileInventory().await().unwrap();
        refuseAdoption.set(false);
        inventory.set(List.of());
        for (int pass = 1; pass < CapacityControlledLifecycle.ABSENCE_CONFIRMATIONS; pass++) {
            now.addAndGet(2 * GRACE_MS);
            next.reconcileInventory().await().unwrap();
            assertThat(phaseOf("first")).as("miss %d since the sighting", pass).isEqualTo(AetherValue.CapacityReservationPhase.DISPATCHED);
        }
        now.addAndGet(2 * GRACE_MS);
        next.reconcileInventory().await().unwrap();

        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("first"))).isEmpty()).isTrue();
    }

    /// Fail-safe: only a readable configuration whose sources were all inventoried counts as "every source inventoried". A configuration
    /// that cannot be read (committed or changed while a pass runs) does not, so nothing earlier is examined against it.
    @Test
    void allSourcesInventoried_isFalseForAnUnreadableConfiguration_andForASourceNotYetListed() {
        initialize(0);
        assertThat(lifecycle.allSourcesInventoried()).as("east not listed yet").isFalse();
        lifecycle.provisionNode(spec("first", "east")).await();
        assertThat(lifecycle.allSourcesInventoried()).as("east listed by the admission").isTrue();

        replaceEastConfig("this is [not valid toml");
        assertThat(store.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class).unwrap().tomlContent().or("")).as("staged").contains("not valid");
        assertThat(org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser.parse("this is [not valid toml").isFailure()).as("parse").isTrue();
        assertThat(lifecycle.allSourcesInventoried()).as("the committed configuration cannot be read").isFalse();
    }

    /// The release step is fenced by leadership: losing it between the no-create evidence and the release leaves the evidence in place,
    /// and the ledger counted, until the instance leads again.
    @Test
    void releaseOfAnAbandonedDispatch_isFencedByLeadership() {
        initialize(0);
        var leading = new java.util.concurrent.atomic.AtomicBoolean(true);
        var flips = new AtomicInteger(1);
        var losing = losingLeadership(leading, flips);
        losing.provisionNode(spec("first", "east")).await();
        leading.set(true);
        flips.set(1);

        losing.reconcileRefusals().await().unwrap();

        assertThat(phaseOf("first")).as("evidence recorded, release fenced").isEqualTo(AetherValue.CapacityReservationPhase.RELEASED);
        assertThat(ledger().allocated()).isEqualTo(1);
        leading.set(true);
        losing.reconcileRefusals().await().unwrap();
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("first"))).isEmpty()).isTrue();
        assertThat(ledger().allocated()).isZero();
    }

    /// Termination on a ledger that was never inventoried inventories first: the branch rc5 keeps for a restored, incomplete ledger.
    @Test
    void terminate_onALedgerNeverInventoried_inventoriesFirst() {
        commitSourceConfig();
        lifecycle.provisionNode(spec("seeded", SourceName.DEFAULT.value())).await();
        var current = ledger();
        seed(new KVCommand.LeaderTransaction<>(AetherKey.CapacityLedgerKey.INSTANCE, java.util.UUID.randomUUID().toString(), LEADER, List.of(),
            List.of(new KVCommand.Mutation<AetherKey, AetherValue>(AetherKey.CapacityLedgerKey.INSTANCE, Option.some(current),
                Option.some(new AetherValue.CapacityLedgerValue(current.allocated(), current.version() + 1, false))))));
        var fresh = CapacityControlledLifecycle.capacityControlledLifecycle(provider, CORE, store,
            commands -> Promise.success(store.process(store.createBatch(commands))), () -> true, () -> 1);
        var before = listings.get();

        var terminated = fresh.terminateNode(new NodeId("seeded"), SourceName.DEFAULT).await();

        assertThat(terminated.isSuccess()).as("terminate: %s", terminated).isTrue();
        assertThat(listings.get()).as("the source was listed before the termination").isGreaterThan(before);
        assertThat(ledger().inventoryComplete()).isTrue();
    }

    private static InstanceInfo observedCore(String node) {
        return new InstanceInfo(new org.pragmatica.aether.environment.InstanceId("instance-" + node),
            org.pragmatica.aether.environment.InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND,
            java.util.Map.of("aether-role", "core"), Option.some(node), Option.none());
    }

}
