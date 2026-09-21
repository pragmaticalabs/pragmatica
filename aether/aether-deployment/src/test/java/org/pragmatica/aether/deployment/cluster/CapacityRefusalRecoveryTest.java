// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityRefusalRecoveryTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId OLD = new NodeId("old");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.pool]
        type = "forge"
        zones = ["old", "new"]
        [source.pool.core]
        count = 3
        [source.pool.worker]
        count = 1
        [community.stable]
        target_size = 1
        [community.stable.placement.destination]
        source = "pool"
        zone = "new"
        """;
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Promise<List<Object>> process(List<KVCommand<AetherKey>> commands) { return Promise.success(store.process(store.createBatch(commands))); }
    private List<Object> processNow(List<KVCommand<AetherKey>> commands) { return store.process(store.createBatch(commands)); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private AetherValue.CapacityLedgerValue ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, AetherValue.CapacityLedgerValue.class).unwrap();
    }

    private void seedLedger(int allocated) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.LeaderTransaction<>(AetherKey.CapacityLedgerKey.INSTANCE, java.util.UUID.randomUUID().toString(), LEADER, List.of(),
            List.of(new KVCommand.Mutation<>(AetherKey.CapacityLedgerKey.INSTANCE, Option.none(),
                Option.some(new AetherValue.CapacityLedgerValue(allocated, 1, true))))));
    }

    private static ProvisionSpec spec(String node, String source) {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", "worker",
            ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), "worker", SourceName.sourceName(source).unwrap(), node)).unwrap();
    }

    private static InstanceInfo instance(String node) {
        return new InstanceInfo(new InstanceId("instance-" + node), InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND,
            Map.of("aether-role", "worker"), Option.some(node), Option.some("new"));
    }

    /// Provider fake: counts creates; outcome configurable.
    private static NodeLifecycleManager provider(AtomicInteger creates, Function<ProvisionSpec, Promise<InstanceInfo>> outcome) {
        return new NodeLifecycleManager() {
            @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) { creates.incrementAndGet(); return outcome.apply(spec); }
            @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
            @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return Promise.success(List.of()); }
            @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) { return Promise.success(List.of()); }
            @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter) { return Promise.success(List.of()); }
            @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) { return Promise.success(List.of()); }
            @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return Promise.unitPromise(); }
            @Override public Promise<Unit> terminateNode(NodeId node) { return Promise.unitPromise(); }
            @Override public Promise<Unit> restartNode(NodeId node) { return Promise.unitPromise(); }
            @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
            @Override public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) { return org.pragmatica.lang.Result.success("binding"); }
            @Override public boolean isCloudManaged() { return true; }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // P1: shared reservations — two concurrent reservations for the LAST slot; exactly one wins.
    // The interleaving is forced through A's apply hook: B's whole provision runs to completion
    // between A's ledger read and A's transaction submission (the classic lost-update window).
    // ---------------------------------------------------------------------------------------------
    private static boolean isReservation(List<KVCommand<AetherKey>> commands) {
        return commands.stream().anyMatch(c -> c instanceof KVCommand.LeaderTransaction<?, ?> tx && tx.mutations().stream().anyMatch(m ->
            m.key() instanceof AetherKey.CapacityReservationKey && m.expected().isEmpty()));
    }

    @Test
    void competingReservationsForLastSlot_exactlyOneWinsViaLedgerCas() {
        seedLedger(0);
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT, new AetherValue.ClusterConfigValue(CONFIG, "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        var creates = new AtomicInteger();
        var providerB = provider(creates, s -> Promise.success(instance(s.context().nodeId().unwrap())));
        var lifecycleB = CapacityControlledLifecycle.capacityControlledLifecycle(providerB, CORE, store, this::process, () -> true, () -> 1);
        var injected = new AtomicBoolean();
        var providerA = provider(creates, s -> Promise.success(instance(s.context().nodeId().unwrap())));
        var lifecycleA = CapacityControlledLifecycle.capacityControlledLifecycle(providerA, CORE, store, commands -> {
            if (isReservation(commands) && injected.compareAndSet(false, true)) {
                // B slips in between A's ledger read and A's reservation commit.
                assertThat(lifecycleB.provisionNode(spec("node-b", "east")).await().isSuccess()).isTrue();
            }
            return process(commands);
        }, () -> true, () -> 1);

        var a = lifecycleA.provisionNode(spec("node-a", "east")).await();

        assertThat(a.isFailure()).as("A must lose the CAS on the last slot").isTrue();
        Object cause = a.fold(c -> c, _ -> null);
        assertThat(cause).isEqualTo(CapacityControlledLifecycle.AdmissionFailure.CAPACITY_UNAVAILABLE);
        assertThat(creates.get()).as("exactly one provider create").isEqualTo(1);
        assertThat(ledger().allocated()).isEqualTo(1);
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("node-b"))).isPresent()).isTrue();
        assertThat(store.get(new AetherKey.CapacityReservationKey(new NodeId("node-a"))).isEmpty()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // Definitive no-create evidence survives contended accounting and controller restart.
    private final Set<NodeId> ready = new HashSet<>();
    private final List<String> effects = new ArrayList<>();
    private final List<CommunityPlacementOperationValue> escalations = new ArrayList<>();

    private void initializeReconcilerWorld() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(CONFIG, "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(OLD), new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "stable", "")));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(OLD), new AetherValue.NodePlacementValue("pool", Option.some("old"), "old-instance")));
        ready.add(OLD);
        seed(new KVCommand.LeaderTransaction<>(AetherKey.CapacityLedgerKey.INSTANCE, java.util.UUID.randomUUID().toString(), LEADER, List.of(),
            List.of(new KVCommand.Mutation<>(AetherKey.CapacityLedgerKey.INSTANCE, Option.none(),
                Option.some(new AetherValue.CapacityLedgerValue(1, 1, true))))));
    }

    private CommunityPlacementOperationValue current() {
        return store.getTyped(new AetherKey.CommunityPlacementOperationKey("stable"), CommunityPlacementOperationValue.class).unwrap();
    }

    private static boolean isRelease(List<KVCommand<AetherKey>> commands, int before) {
        return commands.stream().anyMatch(c -> c instanceof KVCommand.LeaderTransaction<?, ?> tx && tx.mutations().stream().anyMatch(m ->
            m.key().equals(AetherKey.CapacityLedgerKey.INSTANCE) && m.replacement().filter(v -> ((AetherValue.CapacityLedgerValue) v).allocated() < before).isPresent()));
    }

    private CommunityPlacementReconciler reconcilerOver(NodeLifecycleManager lifecycle) {
        var actuator = new CommunityPlacementReconciler.Actuator() {
            @Override public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) { return org.pragmatica.lang.Result.success("binding"); }
            @Override public Promise<Unit> create(CommunityPlacementOperationValue op) {
                effects.add("create");
                var ctx = ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), "worker", SourceName.sourceName(op.targetSource()).unwrap(), op.targetNode().id());
                var s = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", "worker", ctx).unwrap();
                return lifecycle.provisionNode(s, op.sourceBinding()).mapToUnit();
            }
            @Override public Promise<Boolean> retirementSafe(CommunityPlacementOperationValue op) { return Promise.success(true); }
            @Override public Promise<Unit> drain(CommunityPlacementOperationValue op) { effects.add("drain"); return Promise.unitPromise(); }
            @Override public Promise<Unit> terminate(CommunityPlacementOperationValue op) { effects.add("terminate"); return Promise.unitPromise(); }
            @Override public Promise<Boolean> previousInstanceExists(CommunityPlacementOperationValue op) { return Promise.success(true); }
        };
        return CommunityPlacementReconciler.communityPlacementReconciler(CORE, store, this::process, () -> true, ready::contains,
            () -> Set.of(CORE, OLD), () -> 10, actuator, escalations::add, (n, c) -> {}, org.pragmatica.lang.io.TimeSpan.timeSpan(60_000).millis());
    }

    @Test
    void definitiveRefusalSurvivesConflictingReleaseAndControllerRestart() {
        initializeReconcilerWorld();
        var creates = new AtomicInteger();
        var refusing = provider(creates, request -> EnvironmentError.capacityUnavailable("new", new RuntimeException("refused")).promise());
        var lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(refusing, CORE, store, commands -> {
            var before = ledger();
            if (isRelease(commands, before.allocated())) {
                seed(new KVCommand.LeaderTransaction<>(AetherKey.CapacityLedgerKey.INSTANCE, java.util.UUID.randomUUID().toString(), LEADER, List.of(),
                    List.of(new KVCommand.Mutation<>(AetherKey.CapacityLedgerKey.INSTANCE, Option.some(before),
                        Option.some(new AetherValue.CapacityLedgerValue(before.allocated(), before.version() + 1, true))))));
            }
            return process(commands);
        }, () -> true, () -> 10);
        var reconciler = reconcilerOver(lifecycle);
        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        var target = current().targetNode();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(store.getTyped(new AetherKey.CapacityReservationKey(target), AetherValue.CapacityReservationValue.class)
            .unwrap().phase()).isEqualTo(AetherValue.CapacityReservationPhase.RELEASED);
        var recovered = CapacityControlledLifecycle.capacityControlledLifecycle(refusing, CORE, store, this::process, () -> true, () -> 10);
        recovered.reconcileInventory().await().unwrap();
        assertThat(store.get(new AetherKey.CapacityReservationKey(target)).isEmpty()).isTrue();
        assertThat(ledger().allocated()).isEqualTo(1);
        assertThat(creates.get()).isEqualTo(1);
        assertThat(store.get(new AetherKey.CommunityPlacementAvailabilityKey("stable", "pool", Option.some("new"))).isPresent()).isTrue();
    }

    @Test
    void successorConsumesDurableRefusalEvenWhenCreateCompletionWasLost() {
        initializeReconcilerWorld();
        var creates = new AtomicInteger();
        var refusing = provider(creates, request -> EnvironmentError.capacityUnavailable("new", new RuntimeException("refused")).promise());
        var lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(refusing, CORE, store, this::process, () -> true, () -> 10);
        var reconciler = reconcilerOver(lifecycle);
        reconciler.reconcile().await().unwrap();
        var reserved = current();
        var requested = reserved.withPhase(PlacementOperationPhase.CREATE_REQUESTED, LEADER, "");
        var key = new AetherKey.CommunityPlacementOperationKey("stable");
        seed(new KVCommand.LeaderTransaction<>(key, "request", LEADER, List.of(), List.of(
            new KVCommand.Mutation<AetherKey, AetherValue>(key, Option.some(reserved), Option.some(requested)))));
        assertThat(lifecycle.provisionNode(spec(requested.targetNode().id(), "pool")).await().isFailure()).isTrue();
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.CREATE_REQUESTED);
        var recovered = CapacityControlledLifecycle.capacityControlledLifecycle(refusing, CORE, store, this::process, () -> true, () -> 10);
        reconcilerOver(recovered).reconcile().await().unwrap();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        recovered.reconcileInventory().await().unwrap();
        assertThat(store.get(new AetherKey.CapacityReservationKey(requested.targetNode())).isEmpty()).isTrue();
        assertThat(creates.get()).isEqualTo(1);
    }

    @Test
    void definitiveRefusalWithoutConflict_completesAndRecordsAvailability() {
        initializeReconcilerWorld();
        var creates = new AtomicInteger();
        var refusing = provider(creates, s -> EnvironmentError.capacityUnavailable("new", new RuntimeException("412 resource_unavailable")).promise());
        var lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(refusing, CORE, store, this::process, () -> true, () -> 10);
        var reconciler = reconcilerOver(lifecycle);

        reconciler.reconcile().await().unwrap();
        reconciler.reconcile().await().unwrap();
        var target = current().targetNode();

        assertThat(creates.get()).isEqualTo(1);
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        lifecycle.reconcileInventory().await().unwrap();
        assertThat(store.get(new AetherKey.CapacityReservationKey(target)).isEmpty()).isTrue();
        assertThat(store.get(new AetherKey.CommunityPlacementAvailabilityKey("stable", "pool", Option.some("new"))).isPresent()).isTrue();
        assertThat(ledger().allocated()).isEqualTo(1);
        assertThat(escalations).hasSize(1);
    }

}
