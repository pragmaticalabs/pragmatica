// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.cluster.CapacityControlledLifecycle;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.MembershipLiveness;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner;
import org.pragmatica.aether.deployment.cluster.SourceComputeRegistry;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1543, v-2042: the confirmed reap against the REAL `SourceComputeRegistry`, the REAL `CapacityControlledLifecycle` and the REAL
/// topology manager, with reservations read back from the store. The earlier unit fakes answered every binding, which hid that the
/// registry refuses the bindings an EXTERNAL reservation carries (`""`, `external-uncounted`):
///
/// - an EXTERNAL replacement that is rolled back is confirmed by leaving the membership and its reservation is released, with NO
///   provider call;
/// - a provider-backed source whose listing fails, or lists nothing it ever listed, is never read as gone, and one that lists the
///   instance is terminated and confirmed.
class NodeReplacementRealRegistryReapTest {
    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.west]
        type = "cloud"
        provider = "hetzner"
        credentials = "west-token"
        region = "west-region"
        [source.west.core]
        count = 3
        instance_type = "small"
        """;
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId OLD = new NodeId("core-old");
    private static final NodeId FRESH = new NodeId("fresh-1");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final SourceName WEST = SourceName.sourceName("west").unwrap();

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicInteger lists = new AtomicInteger();
    private final AtomicInteger terminates = new AtomicInteger();
    private final AtomicReference<Promise<List<InstanceInfo>>> listing = new AtomicReference<>(Promise.success(List.of()));
    private final Map<NodeId, String> states = new java.util.concurrent.ConcurrentHashMap<>();
    private final NodeReplacementIndex index = NodeReplacementIndex.nodeReplacementIndex();
    private NodeLifecycleManager lifecycle;
    private NodeReplacementWiring.Wiring wiring;
    private String realBinding;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        var registry = SourceComputeRegistry.sourceComputeRegistry(() -> Option.some(new ClusterConfigValue(Option.some(CONFIG), "test", "1.0.0", List.of(), 3, 9, "test", 1L, 1L)),
                                                                   config -> Result.success(EnvironmentIntegration.withCompute(new CountingProvider())));

        realBinding = registry.binding(WEST).unwrap();
        store.process(store.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER))));
        put(AetherKey.ClusterConfigKey.CURRENT, new ClusterConfigValue(Option.some(CONFIG), "test", "1.0.0", List.of(), 3, 9, "test", 1L, 1L));
        var delegate = NodeLifecycleManager.nodeLifecycleManager(registry, _ -> Result.success(WEST), Option.none(), Option.none());

        lifecycle = CapacityControlledLifecycle.capacityControlledLifecycle(delegate, CORE, store, this::process, () -> true, () -> 10);
        var ctm = ctmOver(lifecycle);
        var fsm = mock(MembershipFsm.class);

        when(fsm.memberStates()).thenAnswer(call -> Map.copyOf(states));
        wiring = NodeReplacementWiring.wire(new NodeReplacementWiring.Inputs(CORE,
                                                                             () -> true,
                                                                             store,
                                                                             this::process,
                                                                             index,
                                                                             () -> fsm,
                                                                             Option::none,
                                                                             Option::none,
                                                                             java.util.Set::of,
                                                                             java.util.Set::of,
                                                                             node -> "",
                                                                             ctm,
                                                                             node -> Promise.success(NodeReplacementWiring.DrainOutcome.admitted()),
                                                                             node -> false,
                                                                             () -> "fresh",
                                                                             node -> false,
                                                                             java.util.Set::of,
                                                                             () -> 10,
                                                                             OperatorWarningSink.logOnly(),
                                                                             () -> 1_000L,
                                                                             NodeReplacementPlanner.Timings.parse("60000,60000,60000,60000,0,60000,60000")));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Promise<List<Object>> process(List<KVCommand<AetherKey>> commands) {
        return Promise.success(store.process(store.createBatch(commands)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void put(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction(key,
                                                                                java.util.UUID.randomUUID().toString(),
                                                                                LEADER,
                                                                                List.of(),
                                                                                List.of(new KVCommand.Mutation<>(key, Option.none(), Option.some(value)))))));
    }

    private ClusterTopologyManager ctmOver(NodeLifecycleManager lifecycleManager) {
        var snapshotSource = new GenerationSnapshotSource() {
            @Override public Option<MembershipView> currentMembershipView() { return Option.none(); }
            @Override public long observedRabiaTerm() { return 0L; }
        };
        var self = NodeInfo.nodeInfo(CORE, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(CORE, 3, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                lifecycleManager,
                                                                AutoHealConfig.DEFAULT,
                                                                DeploymentMap.deploymentMap(),
                                                                snapshotSource,
                                                                () -> Option.<ClusterConfigValue> none(),
                                                                commands -> Promise.success(List.<Object> of()),
                                                                () -> AetherValue.ClusterPhase.NORMAL,
                                                                _ -> {},
                                                                _ -> {},
                                                                Option::none,
                                                                MembershipLiveness.UNWIRED);

        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.activate();

        return ctm;
    }

    private void record(NodeReplacementValue record) {
        put(new AetherKey.NodeReplacementKey(OLD), record);
        index.put(new AetherKey.NodeReplacementKey(OLD), record);
    }

    private Option<CapacityReservationValue> reservation(NodeId node) {
        return store.getTyped(new AetherKey.CapacityReservationKey(node), CapacityReservationValue.class);
    }

    private Option<NodeReplacementValue> committed() {
        return store.getTyped(new AetherKey.NodeReplacementKey(OLD), NodeReplacementValue.class);
    }

    private static InstanceInfo instance(NodeId node, String id, InstanceStatus status) {
        return new InstanceInfo(InstanceId.instanceId(id).unwrap(), status, List.of(), InstanceType.ON_DEMAND, Map.of("aether.node-id", node.id()), Option.some(node.id()), Option.none());
    }

    /// An EXTERNAL replacement rolled back before its node arrived reaches ROLLED_BACK and returns its slot, and no provider call is
    /// made: the registry would refuse the reservation's binding and a listing of the operator's own node proves nothing.
    @Test
    void anExternalRollback_reachesRolledBack_andReleasesTheSlot_withoutAProviderCall() {
        put(new AetherKey.CapacityReservationKey(FRESH), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.DISPATCHED));
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.JOINING, 1L, "west", "", NodeReplacementValue.MODE_EXTERNAL, 0, "", 1L));

        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).as("the rollback is committed").isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(reservation(FRESH).unwrap().phase()).as("and the reservation is released in the same transaction").isEqualTo(CapacityReservationPhase.RELEASED);
        assertThat(lists.get() + terminates.get()).as("no provider list or terminate call for an EXTERNAL node").isZero();

        lifecycle.reconcileRefusals().await().unwrap();

        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).as("the slot is returned once").isEqualTo(4);
        assertThat(reservation(FRESH).isEmpty()).isTrue();
    }

    /// An external replacement whose node is still a member is not confirmed gone: nothing is committed until it leaves.
    @Test
    void anExternalRollback_waitsForTheNodeToLeaveTheMembership() {
        put(new AetherKey.CapacityReservationKey(FRESH), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.DISPATCHED));
        states.put(FRESH, "Member");
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.JOINING, 1L, "west", "", NodeReplacementValue.MODE_EXTERNAL, 0, "", 1L));

        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).as("still JOINING: the node has not left").isEqualTo(NodeReplacementPhase.JOINING);
        states.remove(FRESH);
        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
    }

    /// An EXTERNAL-joined node being retired (its reservation OBSERVED, binding "") is confirmed by leaving the membership, its
    /// reservation is released and its slot returned once, again with no provider call: it could never be reaped through the registry.
    @Test
    void aRetiredExternalJoinedNode_isConfirmedByDeparture_andItsReservationIsReleased() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));
        states.put(OLD, "Dead");
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.RETIRING_OLD, 999_999L, "west", "", NodeReplacementValue.MODE_CTM, 0, "", 1L));

        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(reservation(OLD).unwrap().phase()).isEqualTo(CapacityReservationPhase.RELEASED);
        assertThat(lists.get() + terminates.get()).as("no provider call").isZero();

        lifecycle.reconcileRefusals().await().unwrap();

        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).isEqualTo(4);
    }

    /// Ledger-less cluster: the reservation carries the uncounted marker, which the registry refuses just as it refuses `""`. A rolled-back
    /// EXTERNAL replacement reaches ROLLED_BACK without a provider call and the never-counted reservation is dropped (not released: a
    /// release would hand back a slot nobody took).
    @Test
    void anUncountedExternalRollback_isConfirmedByDeparture_andTheReservationIsDropped_withoutAProviderCall() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(4, 1, true));
        put(new AetherKey.CapacityReservationKey(FRESH), new CapacityReservationValue("west", NodeReplacementWiring.UNCOUNTED, "core", CapacityReservationPhase.DISPATCHED));
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.JOINING, 1L, "west", "", NodeReplacementValue.MODE_EXTERNAL, 0, "", 1L));

        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).isEqualTo(NodeReplacementPhase.ROLLED_BACK);
        assertThat(reservation(FRESH).isEmpty()).as("dropped in the same transaction").isTrue();
        assertThat(lists.get() + terminates.get()).as("no provider call").isZero();

        lifecycle.reconcileRefusals().await().unwrap();

        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).as("nothing was taken, nothing is returned").isEqualTo(4);
    }

    /// The retiring variant: an EXTERNAL-joined old node on an uncounted reservation is confirmed by departure and its reservation dropped; the
    /// ledger is untouched (a RELEASED marker would make the lifecycle decrement a slot that was never counted).
    @Test
    void aRetiredUncountedExternalNode_hasItsReservationDropped_andTheLedgerIsUntouched() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(4, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", NodeReplacementWiring.UNCOUNTED, "core", CapacityReservationPhase.DISPATCHED));
        states.put(OLD, "Dead");
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.RETIRING_OLD, 999_999L, "west", "", NodeReplacementValue.MODE_CTM, 0, "", 1L));

        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(reservation(OLD).isEmpty()).as("dropped, not marked released").isTrue();
        assertThat(lists.get() + terminates.get()).as("no provider call").isZero();

        lifecycle.reconcileRefusals().await().unwrap();

        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).isEqualTo(4);
    }

    /// A provider-backed source (real binding): a failed listing is not gone, an empty listing of an instance never listed is not gone,
    /// and an instance that is listed is terminated and confirmed; only then DONE.
    @Test
    void aProviderSource_isDoneOnlyAfterAListedInstanceIsTerminated_neverOnAFailedOrAnUnattributedListing() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(4, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", realBinding, "core", CapacityReservationPhase.OBSERVED));
        states.put(OLD, "Dead");
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.RETIRING_OLD, 999_999L, "west", "", NodeReplacementValue.MODE_CTM, 0, "", 1L));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).as("a failed listing is not 'gone'").isEqualTo(NodeReplacementPhase.RETIRING_OLD);
        assertThat(lists.get()).as("the real registry accepted the real binding and the provider WAS asked").isPositive();

        listing.set(Promise.success(List.of()));
        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).as("an empty listing of an instance never listed is not 'gone'").isEqualTo(NodeReplacementPhase.RETIRING_OLD);
        assertThat(terminates.get()).isZero();

        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(terminates.get()).as("a listed instance is terminated").isEqualTo(1);
        assertThat(committed().unwrap().phase()).isEqualTo(NodeReplacementPhase.DONE);
    }

    private final class CountingProvider implements ComputeProvider {
        @Override
        public Promise<org.pragmatica.aether.environment.InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("create").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId id) {
            terminates.incrementAndGet();
            listing.set(Promise.success(List.of()));

            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            lists.incrementAndGet();

            return listing.get();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId id) {
            return EnvironmentError.operationNotSupported("status").promise();
        }
    }
}
