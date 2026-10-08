package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.CapacityControlledLifecycle;
import org.pragmatica.aether.deployment.cluster.ActionResult;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.deployment.cluster.NodeAction;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
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

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 E2 (v-2042 M1): an EXTERNAL replacement's reservation is counted into the fleet ledger when it is written and returned when
/// the replacement is rolled back before its node ever arrived, against the REAL lifecycle's refusal reconciliation. Net effect on
/// the ledger: zero; the reservation is gone, so the id is no longer admissible; and the slot is returned exactly once.
class NodeReplacementCapacityLedgerTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId FRESH = new NodeId("fresh-1");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void transaction(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction(AetherKey.CapacityLedgerKey.INSTANCE, java.util.UUID.randomUUID().toString(), LEADER, List.of(), mutations))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Promise<List<Object>> process(List<KVCommand<AetherKey>> commands) {
        return Promise.success(store.process(store.createBatch(commands)));
    }

    private CapacityLedgerValue ledger() {
        return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seedLedger(int allocated) {
        store.process(store.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER))));
        transaction(List.of(new KVCommand.Mutation<>(AetherKey.CapacityLedgerKey.INSTANCE, Option.none(), Option.some(new CapacityLedgerValue(allocated, 1, true)))));
    }

    @Test
    void externalReplacementThatNeverArrives_returnsExactlyTheSlotItCounted() {
        seedLedger(4);
        var key = new AetherKey.CapacityReservationKey(FRESH);

        transaction(NodeReplacementWiring.admissionMutations(true, "core", FRESH, "pool", Option.some(ledger())));
        assertThat(ledger().allocated()).as("acquire counts the slot").isEqualTo(5);
        assertThat(store.getTyped(key, CapacityReservationValue.class).unwrap().phase()).isEqualTo(CapacityReservationPhase.DISPATCHED);

        var rolledBack = new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.ROLLED_BACK, 0L, "pool", "", NodeReplacementValue.MODE_EXTERNAL, 0, "join deadline", 3L);

        transaction(NodeReplacementWiring.settleReservation(rolledBack, store.getTyped(key, CapacityReservationValue.class)));
        assertThat(store.getTyped(key, CapacityReservationValue.class).unwrap().phase()).isEqualTo(CapacityReservationPhase.RELEASED);

        var lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(provider(), CORE, store, this::process, () -> true, () -> 10);

        lifecycle.reconcileRefusals().await().unwrap();
        lifecycle.reconcileRefusals().await().unwrap();

        assertThat(ledger().allocated()).as("the slot comes back once, never twice").isEqualTo(4);
        assertThat(store.get(key).isEmpty()).as("and the id is no longer admissible").isTrue();
    }

    /// v-2042 M1(c): a reservation written while the ledger could not count it (here: no ledger at all) must settle WITHOUT
    /// returning a slot, even when a ledger exists by the time it settles. It is dropped, the counter is not touched.
    @Test
    void aReservationThatWasNeverCounted_isDroppedWithoutReturningASlot() {
        store.process(store.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER))));
        var key = new AetherKey.CapacityReservationKey(FRESH);

        transaction(NodeReplacementWiring.admissionMutations(true, "core", FRESH, "pool", Option.none()));
        assertThat(store.getTyped(key, CapacityReservationValue.class).unwrap().sourceBinding()).as("marked as uncounted").isEqualTo(NodeReplacementWiring.UNCOUNTED);
        seedLedger(4);

        var rolledBack = new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.ROLLED_BACK, 0L, "pool", "", NodeReplacementValue.MODE_EXTERNAL, 0, "join deadline", 3L);

        transaction(NodeReplacementWiring.settleReservation(rolledBack, store.getTyped(key, CapacityReservationValue.class)));
        CapacityControlledLifecycle.capacityControlledLifecycle(provider(), CORE, store, this::process, () -> true, () -> 10).reconcileRefusals().await().unwrap();

        assertThat(ledger().allocated()).as("nothing was taken, nothing is returned").isEqualTo(4);
        assertThat(store.get(key).isEmpty()).as("and the id is no longer admissible").isTrue();
    }

    /// v-2042 M1(a): a replacement that reaches DONE hands its reservation to the lifecycle as OBSERVED (what it writes when it sees
    /// the instance), so retiring the node later returns the counted slot and drops the reservation: the id stops being admissible.
    @Test
    void aReplacementThatReachedDone_returnsItsSlotAndItsIdWhenTheNodeIsRetired() {
        seedLedger(4);
        var key = new AetherKey.CapacityReservationKey(FRESH);

        transaction(NodeReplacementWiring.admissionMutations(true, "core", FRESH, "pool", Option.some(ledger())));
        var done = new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.DONE, 0L, "pool", "", NodeReplacementValue.MODE_EXTERNAL, 0, "", 5L);

        transaction(NodeReplacementWiring.settleReservation(done, store.getTyped(key, CapacityReservationValue.class)));
        assertThat(store.getTyped(key, CapacityReservationValue.class).unwrap().phase()).isEqualTo(CapacityReservationPhase.OBSERVED);
        assertThat(ledger().allocated()).as("the live node holds its slot").isEqualTo(5);

        var lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(provider(), CORE, store, this::process, () -> true, () -> 10);

        lifecycle.terminateNode(FRESH, org.pragmatica.aether.environment.SourceName.sourceName("pool").unwrap()).await().unwrap();

        assertThat(ledger().allocated()).as("retiring it returns the slot, once").isEqualTo(4);
        assertThat(store.get(key).isEmpty()).as("and its id is no longer admissible").isTrue();
    }

    private static NodeLifecycleManager provider() {
        return new NodeLifecycleManager() {
            @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) { return Causes.cause("unused").promise(); }
            @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return Causes.cause("unused").promise(); }
            @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return Promise.success(List.of()); }
            @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) { return Promise.success(List.of()); }
            @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter) { return Promise.success(List.of()); }
            @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) { return Promise.success(List.of()); }
            @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return Promise.unitPromise(); }
            @Override public Promise<Unit> terminateNode(NodeId node) { return Promise.unitPromise(); }
            @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
            @Override public org.pragmatica.lang.Result<String> sourceBinding(SourceName source) { return org.pragmatica.lang.Result.success(""); }
            @Override public boolean isCloudManaged() { return true; }
        };
    }
}
