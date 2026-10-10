// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
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
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
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
    private final java.util.List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean lagTerminate = new java.util.concurrent.atomic.AtomicBoolean();
    /// The provider accepts a terminate but its listing never changes: the instance is still listed afterwards.
    private final java.util.concurrent.atomic.AtomicBoolean stuck = new java.util.concurrent.atomic.AtomicBoolean();
    /// When set, a terminate answers with this promise (an attempt in flight) after counting itself.
    private final AtomicReference<Promise<Unit>> terminateGate = new AtomicReference<>();
    private final AtomicInteger lists = new AtomicInteger();
    private final AtomicInteger terminates = new AtomicInteger();
    private final AtomicReference<Promise<List<InstanceInfo>>> listing = new AtomicReference<>(Promise.success(List.of()));
    private final Map<NodeId, String> states = new java.util.concurrent.ConcurrentHashMap<>();
    private final NodeReplacementIndex index = NodeReplacementIndex.nodeReplacementIndex();
    private NodeLifecycleManager lifecycle;
    private ClusterTopologyManager ctmUnderTest;
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

        ctmUnderTest = ctm;
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
        return newManager(lifecycleManager, false);
    }

    /// A real topology manager over the real lifecycle. `replayCapable` gives it a committed cluster name and a quorum-safe view, as a node that has
    /// just become leader has, so that its activation replay reads the provider.
    private ClusterTopologyManager newManager(NodeLifecycleManager lifecycleManager, boolean replayCapable) {
        return newManager(lifecycleManager, replayCapable, 60L, warnings);
    }

    /// `provisioningMillis` sizes every interval the manager derives (retry: a sixth, re-check: five, replay grace: one); `into` receives the
    /// operator events this manager raises, so that two managers (a leader and its successor) can be told apart.
    private ClusterTopologyManager newManager(NodeLifecycleManager lifecycleManager,
                                              boolean replayCapable,
                                              long provisioningMillis,
                                              java.util.List<String> into) {
        var snapshotSource = new GenerationSnapshotSource() {
            @Override public Option<MembershipView> currentMembershipView() { return Option.none(); }
            @Override public long observedRabiaTerm() { return 0L; }
        };
        var self = NodeInfo.nodeInfo(CORE, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(CORE, 3, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                lifecycleManager,
                                                                AutoHealConfig.DEFAULT.withProvisioningTimeout(timeSpan(provisioningMillis).millis()),
                                                                DeploymentMap.deploymentMap(),
                                                                snapshotSource,
                                                                () -> replayCapable
                                                                      ? Option.some(new ClusterConfigValue(Option.some(CONFIG), "test", "1.0.0", List.of(), 3, 9, "test", 1L, 1L))
                                                                      : Option.<ClusterConfigValue> none(),
                                                                commands -> Promise.success(List.<Object> of()),
                                                                () -> AetherValue.ClusterPhase.NORMAL,
                                                                _ -> {},
                                                                _ -> {},
                                                                Option::none,
                                                                MembershipLiveness.membershipLiveness(() -> replayCapable ? Set.of(CORE, new NodeId("c-2"), new NodeId("c-3")) : Set.of(),
                                                                                                      Set::of,
                                                                                                      node -> Option.option(states.get(node)).filter(state -> !"Dead".equals(state)).isPresent(),
                                                                                                      _ -> false,
                                                                                                      Set::of,
                                                                                                      () -> 3,
                                                                                                      _ -> Option.none()));

        ctm.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warning -> into.add(warning.code().code() + ":" + warning.subject() + ":" + warning.message())));
        ctm.setRetirementRefusal(_ -> Option.none());
        ctm.setHierarchyStateWriter(org.pragmatica.aether.deployment.cluster.HierarchyStateWriter.hierarchyStateWriter(() -> store.getTyped(LeaderKey.INSTANCE, LeaderValue.class),
                                                                                                                       key -> store.get(key),
                                                                                                                       this::process));
        ctm.setUnconfirmedMarks(() -> {
            var marks = new java.util.HashMap<NodeId, AetherValue.UnconfirmedTerminationValue>();

            store.forEach(AetherKey.UnconfirmedTerminationKey.class, AetherValue.UnconfirmedTerminationValue.class, (key, mark) -> marks.put(key.nodeId(), mark));

            return Map.copyOf(marks);
        });
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
        assertThat(reservation(FRESH).isEmpty()).as("and the reservation is returned with the reap, before the commit").isTrue();
        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).isEqualTo(4);
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
        assertThat(reservation(OLD).isEmpty()).as("returned with the reap").isTrue();
        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).isEqualTo(4);
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

    /// R4 (negative): a replacement the CTM dispatched but the provider has NEVER listed, followed by one empty listing, is not rolled back:
    /// an empty listing proves nothing about an instance that may simply not have appeared yet.
    @Test
    void aDispatchedReplacementNeverListed_isNotRolledBack_onOneEmptyListing() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        put(new AetherKey.CapacityReservationKey(FRESH), new CapacityReservationValue("west", realBinding, "core", CapacityReservationPhase.DISPATCHED));
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.PROVISIONING, 1L, "west", "", NodeReplacementValue.MODE_CTM, 0, "", 1L));
        listing.set(Promise.success(List.of()));

        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(committed().unwrap().phase()).as("still PROVISIONING: nothing confirms the dispatched instance is gone").isEqualTo(NodeReplacementPhase.PROVISIONING);
        assertThat(lists.get()).as("the provider WAS asked").isPositive();
        assertThat(terminates.get()).isZero();
    }

    /// R5: the drain backstop's reap of an EXTERNAL node (reservation without a provider binding) is a quiet success, with no provider
    /// call and no refusal; a provider-bound node is still terminated through the provider.
    @Test
    void terminatingAnExternalNode_makesNoProviderCall_andARefusalIsNotRaised() {
        put(new AetherKey.CapacityReservationKey(FRESH), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", realBinding, "core", CapacityReservationPhase.OBSERVED));
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));

        assertThat(lifecycle.terminateNode(FRESH).await().isSuccess()).as("external: nothing to terminate, no refusal").isTrue();
        assertThat(lifecycle.terminateNode(FRESH, WEST).await().isSuccess()).isTrue();
        assertThat(lists.get() + terminates.get()).as("no provider call for the external node").isZero();

        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        assertThat(lifecycle.terminateNode(OLD).await().isSuccess()).as("control: a provider-bound node is terminated").isTrue();
        assertThat(terminates.get()).isEqualTo(1);
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

    /// Ruling e9959fa6d(2) for the replacement wiring's own memory: the confirmation of a reap describes one incarnation of the id. The id is up again
    /// (a node restarted under it) and retired by a later replacement: its new instance is terminated, not skipped as "already reaped".
    @Test
    void aNodeSeenUpAgain_isReapedAgainByALaterReplacement_notSkippedAsAlreadyReaped() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(4, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", realBinding, "core", CapacityReservationPhase.OBSERVED));
        states.put(OLD, "Dead");
        record(new NodeReplacementValue(FRESH, "core", NodeReplacementPhase.RETIRING_OLD, 999_999L, "west", "", NodeReplacementValue.MODE_CTM, 0, "", 1L));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();
        assertThat(committed().unwrap().phase()).isEqualTo(NodeReplacementPhase.DONE);
        assertThat(terminates.get()).isEqualTo(1);

        // The id is up again, with a new instance and a new reservation, and a later replacement retires it.
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", realBinding, "core", CapacityReservationPhase.OBSERVED));
        states.put(OLD, "Member");
        joins(OLD);
        listing.set(Promise.success(List.of(instance(OLD, "i-2", InstanceStatus.RUNNING))));
        var later = new NodeReplacementValue(new NodeId("fresh-2"), "core", NodeReplacementPhase.RETIRING_OLD, 999_999L, "west", "", NodeReplacementValue.MODE_CTM, 0, "", 2L);

        replaceRecord(later);
        wiring.reconciler().reconcile().await();
        states.put(OLD, "Dead");
        wiring.reconciler().reconcile().await();
        wiring.reconciler().reconcile().await();

        assertThat(terminates.get()).as("the second incarnation's instance is terminated, not skipped as already reaped").isEqualTo(2);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void replaceRecord(NodeReplacementValue next) {
        var key = new AetherKey.NodeReplacementKey(OLD);
        var existing = store.getTyped(key, NodeReplacementValue.class);

        store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction(key,
                                                                                java.util.UUID.randomUUID().toString(),
                                                                                LEADER,
                                                                                List.of(),
                                                                                List.of(new KVCommand.Mutation<>(key, existing.map(v -> (AetherValue) v), Option.some((AetherValue) next)))))));
        index.put(key, next);
    }

    // ---- #2062: every retirement reap is a confirmed reap -----------------------------------------------------------------

    private void retire(NodeId node) {
        ctmUnderTest.drainNode(node, org.pragmatica.aether.deployment.cluster.DrainReason.OPERATOR_COMMAND).await();
    }

    /// Scale-down / operator drain of a bootstrap node that was never listed and has no reservation: the reap lists it (committing the
    /// observed reservation), terminates it at the provider and confirms by a second listing. Before the fix this was "Cannot terminate
    /// without a committed capacity source binding", logged and dropped.
    @Test
    void aBootstrapNodeWithNoReservation_isTerminatedAndConfirmed_whenItIsRetired() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        retire(OLD);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(terminates.get()).as("terminated at the provider").isEqualTo(1));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(reservation(OLD).isEmpty()).as("the listing committed the reservation, the confirmed termination released it").isTrue());
        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).as("observed (+1) then released (-1)").isEqualTo(3);
        assertThat(lists.get()).as("listed before and after the terminate").isGreaterThanOrEqualTo(2);
        assertThat(warnings).as("confirmed: no operator event").isEmpty();
    }

    /// A listing error is retried, never read as "gone": the node is terminated once the provider answers.
    @Test
    void aListingErrorOnRetirement_isRetried_notReadAsGone() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        retire(OLD);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> lists.get() >= 2);

        assertThat(terminates.get()).as("nothing terminated while the listing fails").isZero();

        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(terminates.get()).as("retried and terminated").isEqualTo(1));
    }

    /// After the bounded retries an operator event names the node and the cause, and its recovery follows a later confirmation.
    @Test
    void aReapThatCannotBeConfirmed_endsInAnOperatorEventNamingTheNode_andRecovers() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        retire(OLD);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("provider API down")));
        assertThat(terminates.get()).isZero();

        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        retire(OLD);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-confirmed:" + OLD.id())));
    }

    /// An EXTERNAL node (operator-started) retired through the same path makes no provider call; its reservation is returned.
    @Test
    void anExternalNodeRetiredThroughTheSharedReap_makesNoProviderCall_andReturnsItsCapacity() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));

        retire(OLD);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> assertThat(reservation(OLD).isEmpty()).isTrue());
        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).isEqualTo(4);
        assertThat(lists.get() + terminates.get()).as("no provider call").isZero();
    }

    private static void within(int seconds, Runnable assertion) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(seconds)).untilAsserted(assertion::run);
    }

    private boolean raised(String code) {
        return warnings.stream().anyMatch(w -> w.startsWith(code + ":" + OLD.id()));
    }

    private void joins(NodeId node) {
        ctmUnderTest.onMembershipDecision(MembershipDecision.nodeJoined(node, List.of(CORE, node)));
    }

    /// B1 (v-2062), per INCARNATION (v-2068 F2): NodeRemoved and the drain-grace backstop both reap a drained node. The second finds the instance
    /// already gone and is not re-asked: no provider call, and no "may still be running" event. The same id joining again is a new incarnation
    /// (a bootstrap index re-minted, a harness restarting a node under its id): its RUNNING instance is terminated and its reservation released.
    @Test
    void aSecondReapOfTheSameIncarnation_isNotReAsked_butARejoinedIdIsReapedAgain() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());
        var calls = lists.get() + terminates.get();

        retire(OLD);
        Thread.sleep(1500);

        assertThat(lists.get() + terminates.get()).as("the same incarnation is not re-asked").isEqualTo(calls);
        assertThat(warnings).as("confirmed once, never reported unconfirmed").noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));

        joins(OLD);
        states.put(OLD, "Dead");
        listing.set(Promise.success(List.of(instance(OLD, "i-2", InstanceStatus.RUNNING))));
        retire(OLD);

        within(10, () -> assertThat(terminates.get()).as("the second incarnation's instance is terminated").isEqualTo(2));
        within(10, () -> assertThat(reservation(OLD).isEmpty()).as("and its reservation released").isTrue());
        assertThat(warnings).noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
    }

    /// v-2068 N2, which fails at 4e68c278d: the same id, a second RUNNING instance, after the node joined again.
    @Test
    void aReusedNodeIdsRunningInstance_isTerminatedOnItsSecondRetirement_afterItJoinedAgain() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        retire(OLD);
        within(10, () -> assertThat(terminates.get()).isEqualTo(1));
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());

        joins(OLD);
        listing.set(Promise.success(List.of(instance(OLD, "i-2", InstanceStatus.RUNNING))));
        retire(OLD);

        within(10, () -> assertThat(terminates.get()).as("the second instance is terminated").isEqualTo(2));
    }

    /// v-2068 N1, which fails at 4e68c278d: an EXTERNAL id re-admitted after its first retirement (its new reservation committed) and retired again:
    /// the new reservation is released and its slot returned, whatever the manager remembers of the first incarnation.
    @Test
    void aReadmittedExternalId_isReleasedOnItsSecondRetirement_andItsSlotReturned() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));
        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));

        retire(OLD);

        within(10, () -> assertThat(reservation(OLD).isEmpty()).as("the second incarnation's reservation is released").isTrue());
        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).as("both slots returned").isEqualTo(3);
        assertThat(lists.get() + terminates.get()).as("no provider call, either incarnation").isZero();
    }

    /// v-2068 N1b, which fails at 4e68c278d: a re-admitted EXTERNAL node that is still a member is not confirmed gone (598476d86: confirmation is
    /// its departure from the membership).
    @Test
    void aReadmittedExternalId_thatIsStillAMember_isNotConfirmedGone() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));
        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));
        states.put(OLD, "Member");

        var result = ctmUnderTest.reapRetired(OLD, WEST, false).await();

        assertThat(result.isFailure()).as("a live external node is not confirmed gone").isTrue();
        assertThat(reservation(OLD).isPresent()).as("and keeps its reservation").isTrue();
    }

    /// A node confirmed gone that shows life BEFORE its join is seen (the join is a separate message) is not confirmed gone again, and is not
    /// terminated: the memory describes an incarnation that has ended.
    @Test
    void aConfirmedNodeThatShowsLifeAgain_isNeitherConfirmedGoneNorTerminated() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());
        var calls = terminates.get() + lists.get();
        listing.set(Promise.success(List.of(instance(OLD, "i-2", InstanceStatus.RUNNING))));
        states.put(OLD, "Member");

        var result = ctmUnderTest.reapRetired(OLD, WEST, true).await();

        assertThat(result.isFailure()).as("not confirmed gone while it shows life").isTrue();
        assertThat(terminates.get() + lists.get()).as("and no provider call").isEqualTo(calls);
    }

    /// B3: a provider whose delete is asynchronous (the listing empties 40 ms after the accepted terminate). Confirmed, whichever attempt
    /// the flip lands in, because the node is remembered as seen and terminated.
    @Test
    void aLaggingListingAfterAnAcceptedTerminate_isConfirmed_withNoEvent() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        lagTerminate.set(true);

        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).as("confirmed: the reservation is released").isTrue());
        Thread.sleep(1500);

        assertThat(terminates.get()).isGreaterThanOrEqualTo(1);
        assertThat(warnings).noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
    }

    /// B4: an EXTERNAL node reaped a second time (its reservation was deleted by the first): still no provider call on any path.
    @Test
    void aSecondReapOfAnExternalNode_makesNoProviderCall() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));

        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());
        retire(OLD);
        Thread.sleep(1500);

        assertThat(lists.get() + terminates.get()).as("no provider call, first or second reap").isZero();
        assertThat(warnings).isEmpty();
    }

    /// The release of a counted EXTERNAL reservation treats an empty ledger as inconsistent, exactly as the release of a provider reservation does
    /// (it used to clamp at zero and delete the reservation, hiding the discrepancy). Returning a slot is pinned by the EXTERNAL retirement tests.
    @Test
    void releasingACountedExternalReservation_againstAnEmptyLedger_isRefused_notClamped() {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(0, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));

        var refused = lifecycle.releaseExternal(OLD).await();

        assertThat(refused.isFailure()).as("an empty ledger cannot return a slot").isTrue();
        refused.onFailure(cause -> assertThat(cause.message()).contains("inconsistent"));
        assertThat(reservation(OLD).isPresent()).as("and the reservation is kept").isTrue();
    }

    /// B2: a STOPPED instance (Hetzner "off", AWS/GCP/Azure "stopped", Docker "exited") is terminated, with or without a reservation, and its
    /// reservation is released.
    @Test
    void aStoppedInstance_isTerminated_andItsReservationReleased() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(4, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", realBinding, "core", CapacityReservationPhase.OBSERVED));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.STOPPING))));

        retire(OLD);

        within(10, () -> assertThat(terminates.get()).as("a stopped VM still bills: terminated").isEqualTo(1));
        within(10, () -> assertThat(reservation(OLD).isEmpty()).as("reservation released").isTrue());
        assertThat(store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap().allocated()).isEqualTo(3);
    }

    @Test
    void aStoppedBootstrapNodeWithNoReservation_isTerminated() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.STOPPING))));

        retire(OLD);

        within(10, () -> assertThat(terminates.get()).isEqualTo(1));
    }

    /// B5: the refused-reap chain (a voter that never becomes retirable) ends in the operator event, not a log line.
    @Test
    void aRefusedReapThatNeverClears_endsInTheOperatorEvent() {
        retire(OLD);
        ctmUnderTest.setRetirementRefusal(_ -> Option.some("still an installed voter"));

        within(30, () -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("still an installed voter")));
        assertThat(terminates.get()).isZero();
    }

    /// M4: the recovery event fires only on a real confirmation: a further failed attempt after the unconfirmed event raises no recovery.
    @Test
    void theRecoveryEvent_firesOnlyOnARealConfirmation() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        retire(OLD);
        within(20, () -> assertThat(raised("instance-termination-unconfirmed")).isTrue());
        retire(OLD);
        Thread.sleep(2500);

        assertThat(raised("instance-termination-confirmed")).as("still failing: no recovery").isFalse();

        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        retire(OLD);

        within(15, () -> assertThat(raised("instance-termination-confirmed")).as("confirmed by a real listing + terminate").isTrue());
    }

    // ---- #2062: the manager owns the unconfirmed-termination lifecycle ---------------------------------------------------

    private static InstanceInfo labelled(NodeId node, String id) {
        return new InstanceInfo(InstanceId.instanceId(id).unwrap(),
                                InstanceStatus.RUNNING,
                                List.of(),
                                InstanceType.ON_DEMAND,
                                Map.of("aether.node-id", node.id(), "aether-cluster", "test", "aether-role", "core", "aether-source", "west"),
                                Option.some(node.id()),
                                Option.none());
    }

    /// (a) Every raiser goes through the manager: marking raises the event once, naming the node and the cause.
    @Test
    void markUnconfirmed_raisesTheEventOnce_namingTheNodeAndTheCause() {
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        ctmUnderTest.markUnconfirmed(OLD, "reaper says: provider unreachable");
        ctmUnderTest.markUnconfirmed(OLD, "a second raiser");

        within(5, () -> assertThat(warnings.stream().filter(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id())).toList())
                            .hasSize(1)
                            .allMatch(w -> w.contains("provider unreachable")));
        assertThat(warnings.stream().filter(w -> w.contains("a second raiser"))).isEmpty();
    }

    /// The provider listed the instance, accepted a terminate, and still listed it afterwards: the node is remembered as seen, and marked. The
    /// listing then FAILS (a re-check that cannot ask closes nothing), so the mark stays open until the test sets what the provider answers. (A mark
    /// whose instance was never listed is not re-checked by listing - see [#aMarkOfAnInstanceNeverListed_isNotRechecked_isNotClosedByTheSuccessor_andSaysSo].)
    private void markAfterSeeingTheInstance(String cause) throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        lagTerminate.set(true);
        assertThat(ctmUnderTest.reapRetired(OLD, WEST, false).await().isFailure()).as("terminate accepted, relisting still shows it").isTrue();
        Thread.sleep(200);
        lagTerminate.set(false);
        terminates.set(0);
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        lists.set(0);
        ctmUnderTest.markUnconfirmed(OLD, cause);
    }

    /// (b) A node marked from outside is re-checked by the active leader and the recovery fires on a real confirmation.
    @Test
    void aMarkedNode_isRecheckedByTheLeader_andConfirmedWhenItsInstanceIsTerminated() throws Exception {
        markAfterSeeingTheInstance("marked by another raiser");
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        within(15, () -> assertThat(raised("instance-termination-confirmed")).as("recovery on the leader's own re-check").isTrue());
        assertThat(terminates.get()).isEqualTo(1);
    }

    /// (b) The re-check is low-rate and bounded: one attempt per marked node per interval (five provisioning windows = 300 ms here).
    @Test
    void theRecheck_isLowRate_andNeverClearsOnAFailingListing() throws Exception {
        markAfterSeeingTheInstance("marked by another raiser");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        Thread.sleep(1700);

        assertThat(lists.get()).as("about one listing per 300 ms, not a hot loop").isBetween(2, 12);
        assertThat(raised("instance-termination-confirmed")).isFalse();
    }

    /// v-2068 N3, which fails at 4e68c278d (ruling e9959fa6d(1)): the re-check never terminates, or confirms gone, a node that shows life. A
    /// partitioned worker whose terminate failed while the provider was unreachable is back under its id; the retirement refusal admits workers
    /// unconditionally, so only the liveness check stands between the re-check and a live member. Control inside the run: the same mark, the node
    /// gone, IS terminated and confirmed - the re-check was running, and only the liveness stopped it.
    @Test
    void theRecheck_neverTerminatesOrConfirmsALiveMember() throws Exception {
        markAfterSeeingTheInstance("provider unreachable while it departed");
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        states.put(OLD, "Member");

        Thread.sleep(1500);

        assertThat(terminates.get()).as("a demonstrably live member is never terminated by the re-check").isZero();
        assertThat(raised("instance-termination-confirmed")).as("nor confirmed gone").isFalse();

        states.remove(OLD);

        within(15, () -> assertThat(raised("instance-termination-confirmed")).as("control: once it is gone the same re-check closes the mark").isTrue());
        assertThat(terminates.get()).as("control: and terminates it").isEqualTo(1);
    }

    private static final org.pragmatica.aether.deployment.cluster.DrainReason DRAIN = org.pragmatica.aether.deployment.cluster.DrainReason.OPERATOR_COMMAND;

    /// Ruling e9959fa6d(1) for the retry chain of every path but the drain-grace backstop (here a departure, NodeRemoved): a failed attempt is retried a
    /// sixth of a provisioning window later (1 s here); a node that shows life in between is not terminated by the retry, and it is not dropped in
    /// silence either (round 4): the operator is told once that it shows life after a failed reap, and the reap is parked for the next SWIM FAULTY.
    @Test
    void aGatedRetry_neverTerminatesANodeThatShowsLifeMeanwhile_andTellsTheOperator() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 6_000L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        manager.onMembershipDecision(MembershipDecision.nodeRemoved(OLD, List.of(CORE)));
        within(10, () -> assertThat(lists.get()).as("the first attempt failed").isGreaterThanOrEqualTo(1));
        states.put(OLD, "Member");
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        within(10, () -> assertThat(warnings).as("the operator is told").anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("shows life")));
        Thread.sleep(1500);

        assertThat(terminates.get()).as("the retry found life and stopped: nothing terminated").isZero();
        assertThat(warnings.stream().filter(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id())).count()).as("once").isEqualTo(1);

        states.remove(OLD);
        manager.onSwimFaulty(OLD);

        within(10, () -> assertThat(terminates.get()).as("parked, not forgotten: SWIM FAULTY re-arms the reap once the node is gone").isEqualTo(1));
        manager.deactivate();
    }

    /// Round 4, F3 (v-2068 N6): the drain-grace backstop reaps a drained node that did not exit - alive by definition. Its first attempt fails
    /// transiently; its retries are NOT liveness-gated, so the instance is terminated and confirmed.
    @Test
    void aDrainedZombieWhoseFirstReapFails_isRetriedAndConfirmed() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 3_000L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        manager.drainNode(OLD, DRAIN).await();
        within(10, () -> assertThat(lists.get()).as("the first attempt failed").isGreaterThanOrEqualTo(1));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        within(10, () -> assertThat(terminates.get()).as("the retry is not gated by the zombie's life").isGreaterThanOrEqualTo(1));
        within(10, () -> assertThat(reservation(OLD).isEmpty()).as("and the reap is confirmed").isTrue());
        assertThat(warnings).as("nothing to tell the operator").noneMatch(w -> w.contains(OLD.id()));
        manager.deactivate();
    }

    /// F3: a zombie whose reap never succeeds ends the bounded chain with the operator event, once - never silence.
    @Test
    void aDrainedZombieWhoseReapNeverSucceeds_endsInTheOperatorEvent_once() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 1_200L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        manager.drainNode(OLD, DRAIN).await();

        within(30, () -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("after 12 attempts") && w.contains("provider API down")));
        assertThat(lists.get()).as("the whole bounded chain ran, ungated").isGreaterThanOrEqualTo(13);
        Thread.sleep(800);

        assertThat(warnings.stream().filter(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id())).count()).isEqualTo(1);
        manager.deactivate();
    }

    /// The drain-grace chain's "ungated" mark must not outlive the chain: whichever way the chain ended (confirmed, out of attempts, cancelled by a
    /// rejoin), a LATER departure's retries are gated again. `ended` leaves the chain as the test needs it; the departure then fails its first
    /// attempt, the node shows life, and its retry must stop and tell the operator instead of terminating.
    private void assertALaterDepartureIsGated(ClusterTopologyManager manager, long baselineTerminates) throws Exception {
        manager.onMembershipDecision(MembershipDecision.nodeJoined(OLD, List.of(CORE, OLD)));
        states.remove(OLD);
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        var before = lists.get();
        manager.onMembershipDecision(MembershipDecision.nodeRemoved(OLD, List.of(CORE)));
        within(10, () -> assertThat(lists.get()).as("the departure's first attempt").isGreaterThan(before));
        states.put(OLD, "Member");
        listing.set(Promise.success(List.of(instance(OLD, "i-9", InstanceStatus.RUNNING))));

        within(10, () -> assertThat(warnings).as("gated: the operator is told it shows life").anyMatch(w -> w.contains("shows life")));
        Thread.sleep(500);

        assertThat(terminates.get()).as("and nothing was terminated by the retry").isEqualTo(baselineTerminates);
        manager.deactivate();
    }

    @Test
    void aLaterDeparture_isGatedAgain_afterAZombieChainConfirmed() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 1_200L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        manager.drainNode(OLD, DRAIN).await();
        within(10, () -> assertThat(reservation(OLD).isEmpty()).as("the zombie chain confirmed").isTrue());

        assertALaterDepartureIsGated(manager, terminates.get());
    }

    @Test
    void aLaterDeparture_isGatedAgain_afterAZombieChainRanOutOfAttempts() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 1_200L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        manager.drainNode(OLD, DRAIN).await();
        within(30, () -> assertThat(raised("instance-termination-unconfirmed")).as("the chain ended").isTrue());

        assertALaterDepartureIsGated(manager, terminates.get());
    }

    @Test
    void aLaterDeparture_isGatedAgain_afterAZombieChainWasCancelledByARejoin() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 1_200L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        manager.drainNode(OLD, DRAIN).await();
        within(10, () -> assertThat(lists.get()).isGreaterThanOrEqualTo(1));
        manager.onMembershipDecision(MembershipDecision.nodeJoined(OLD, List.of(CORE, OLD)));

        assertALaterDepartureIsGated(manager, terminates.get());
    }

    /// Round 5, F4 (v-2068 N8): the rejoin lands while the drained zombie's attempt is IN FLIGHT. That attempt then fails; its retry must not be
    /// re-armed against the NEW incarnation (the chain belongs to the incarnation it began under), so the new incarnation is neither terminated nor marked.
    @Test
    void aRejoinDuringAnInFlightZombieAttempt_neverTerminatesOrMarksTheNewIncarnation() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 3_000L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        Promise<List<InstanceInfo>> inFlight = Promise.promise();
        listing.set(inFlight);
        manager.drainNode(OLD, DRAIN).await();
        within(10, () -> assertThat(lists.get()).as("the zombie attempt is listing").isGreaterThanOrEqualTo(1));

        manager.onMembershipDecision(MembershipDecision.nodeJoined(OLD, List.of(CORE, OLD)));
        listing.set(Promise.success(List.of(instance(OLD, "i-2", InstanceStatus.RUNNING))));
        inFlight.fail(EnvironmentError.operationNotSupported("provider API down"));
        var before = lists.get();

        Thread.sleep(2500);

        assertThat(terminates.get()).as("the rejoined incarnation is never terminated by the old chain").isZero();
        assertThat(lists.get() - before).as("and the old chain made no further attempt").isZero();
        assertThat(warnings).as("nor marked").noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
        manager.deactivate();
    }

    /// The gated twin: a departure's attempt is in flight when the node joins again. Its failure must not re-arm a retry that then finds the new
    /// incarnation alive, parks it and marks it unconfirmed.
    @Test
    void aRejoinDuringAnInFlightGatedAttempt_doesNotMarkTheNewIncarnation() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 3_000L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        Promise<List<InstanceInfo>> inFlight = Promise.promise();
        listing.set(inFlight);
        manager.onMembershipDecision(MembershipDecision.nodeRemoved(OLD, List.of(CORE)));
        within(10, () -> assertThat(lists.get()).as("the departure's attempt is listing").isGreaterThanOrEqualTo(1));

        states.put(OLD, "Member");
        manager.onMembershipDecision(MembershipDecision.nodeJoined(OLD, List.of(CORE, OLD)));
        inFlight.fail(EnvironmentError.operationNotSupported("provider API down"));

        Thread.sleep(2500);

        assertThat(warnings).as("the new incarnation is not marked").noneMatch(w -> w.contains(OLD.id()));
        assertThat(terminates.get()).isZero();
        manager.deactivate();
    }

    /// The LAST attempt of a zombie chain is in flight when the node joins again. Its failure ends the chain with the operator event for the
    /// incarnation it began under; that event is not raised for the new incarnation.
    @Test
    void aRejoinDuringTheLastZombieAttempt_doesNotMarkTheNewIncarnation() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 600L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        manager.drainNode(OLD, DRAIN).await();
        Promise<List<InstanceInfo>> lastAttempt = Promise.promise();

        org.awaitility.Awaitility.await().pollInterval(java.time.Duration.ofMillis(5)).atMost(java.time.Duration.ofSeconds(30)).until(() -> {
            if (lists.get() >= 12) {
                listing.set(lastAttempt);
                return true;
            }
            return false;
        });
        within(10, () -> assertThat(lists.get()).as("the 13th and last attempt is in flight").isEqualTo(13));

        manager.onMembershipDecision(MembershipDecision.nodeJoined(OLD, List.of(CORE, OLD)));
        lastAttempt.fail(EnvironmentError.operationNotSupported("provider API down"));
        Thread.sleep(800);

        assertThat(warnings).as("the old chain's end does not mark the new incarnation").noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
        assertThat(persistedMark(OLD).isEmpty()).isTrue();
        manager.deactivate();
    }

    /// A reap that began under the previous incarnation and completes after the rejoin does not vouch for the new one: it must not enter the
    /// confirmed-reap memory, or a later reap of the new incarnation would be skipped.
    @Test
    void aReapThatBeganBeforeARejoin_doesNotVouchForTheNewIncarnation() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        Promise<List<InstanceInfo>> inFlight = Promise.promise();
        listing.set(inFlight);
        var reap = ctmUnderTest.reapRetired(OLD, WEST, false);
        within(10, () -> assertThat(lists.get()).isGreaterThanOrEqualTo(1));

        joins(OLD);
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        inFlight.succeed(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING)));
        reap.await();
        var terminatesBefore = terminates.get();

        listing.set(Promise.success(List.of(instance(OLD, "i-2", InstanceStatus.RUNNING))));
        var again = ctmUnderTest.reapRetired(OLD, WEST, false).await();

        assertThat(again.isSuccess()).isTrue();
        assertThat(terminates.get()).as("the new incarnation's instance is asked about and terminated, not skipped as already confirmed").isGreaterThan(terminatesBefore);
    }

    /// F3: a rejoin (a new incarnation) cancels the zombie's chain: no further attempt, no event.
    @Test
    void aRejoinMidChain_cancelsTheDrainedZombiesRetries() throws Exception {
        ctmUnderTest.deactivate();
        var manager = newManager(lifecycle, false, 3_000L, warnings);

        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        manager.drainNode(OLD, DRAIN).await();
        within(10, () -> assertThat(lists.get()).isGreaterThanOrEqualTo(1));

        manager.onMembershipDecision(MembershipDecision.nodeJoined(OLD, List.of(CORE, OLD)));
        listing.set(Promise.success(List.of(instance(OLD, "i-2", InstanceStatus.RUNNING))));
        var before = lists.get();

        Thread.sleep(2000);

        assertThat(lists.get() - before).as("no further attempt after the rejoin").isZero();
        assertThat(terminates.get()).isZero();
        assertThat(warnings).noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
        manager.deactivate();
    }

    /// Round 4, edge: an empty listing, then a listing that SHOWS the instance (so it is seen), and the attempts still fail. The mark is not the
    /// permanent "terminate it by hand" one: the provider did list the instance.
    @Test
    void aMark_afterAnEmptyListingAndThenAListedInstance_isASeenMark_notAnAbsentOne() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        states.put(OLD, "Member");
        assertThat(ctmUnderTest.reapRetired(OLD, WEST, false).await().isFailure()).as("empty and unseen").isTrue();
        stuck.set(true);
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        assertThat(ctmUnderTest.reapRetired(OLD, WEST, false).await().isFailure()).as("listed, terminate accepted, still listed").isTrue();

        ctmUnderTest.markUnconfirmed(OLD, "still listed");

        within(10, () -> assertThat(persistedMark(OLD).map(m -> m.seen() && !m.absent()).or(false)).as("seen, and not absent").isTrue());
        assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("confirms it by listing"));
        assertThat(warnings).noneMatch(w -> w.contains("by hand"));
    }

    /// Round 4, edge: a failing-listing mark that later has an empty listing AND a listed instance is not rewritten as the permanent warning when its
    /// re-check fails: the instance was seen.
    @Test
    void aFailingListingMark_thatWasSeenMeanwhile_isNotUpgradedToTheHandWarning() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        states.put(OLD, "Member");
        ctmUnderTest.markUnconfirmed(OLD, "provider unreachable");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());
        listing.set(Promise.success(List.of()));
        assertThat(ctmUnderTest.reapRetired(OLD, WEST, false).await().isFailure()).as("empty and unseen: absent in memory").isTrue();
        stuck.set(true);
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        assertThat(ctmUnderTest.reapRetired(OLD, WEST, false).await().isFailure()).as("then seen").isTrue();

        states.remove(OLD);
        Thread.sleep(1500);

        assertThat(persistedMark(OLD).map(AetherValue.UnconfirmedTerminationValue::absent).or(true)).as("the re-check failed but the instance was seen: not upgraded").isFalse();
        assertThat(warnings).noneMatch(w -> w.contains("by hand"));
    }

    /// Ruling e9959fa6d(3): a mark whose instance the provider never listed is not re-checked by listing - ever. It stays an operator warning,
    /// and the warning says the cluster cannot confirm it and the operator must verify and terminate it by hand. (A mark that WAS seen promises
    /// the cluster will confirm it by listing, and does not ask for a hand.)
    @Test
    void aMarkOfAnInstanceNeverListed_isNotRechecked_isNotClosedByTheSuccessor_andSaysSo() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        assertThat(ctmUnderTest.reapRetired(OLD, WEST, false).await().isFailure()).as("a listing SUCCEEDED and showed nothing: not confirmed gone").isTrue();
        ctmUnderTest.markUnconfirmed(OLD, "never listed");
        within(10, () -> assertThat(persistedMark(OLD).map(m -> !m.seen() && m.absent()).or(false)).as("seen=false, absent=true").isTrue());
        within(5, () -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id())
                                                         && w.contains("cannot confirm")
                                                         && w.contains("by hand")));
        var listsBefore = lists.get();

        Thread.sleep(1500);

        assertThat(lists.get() - listsBefore).as("five re-check intervals passed on the leader: no periodic listing").isZero();

        ctmUnderTest.deactivate();
        listing.set(Promise.success(List.of()));

        newManager(lifecycle, false);
        Thread.sleep(2500);

        assertThat(lists.get() - listsBefore).as("nor on the successor").isZero();
        assertThat(persistedMark(OLD).isPresent()).as("still open: nothing proves the instance is gone").isTrue();
        assertThat(raised("instance-termination-confirmed")).isFalse();
    }

    /// Amendment A: a listing that FAILED is not absence. A mark that only ever met failing listings is not the permanent operator warning: its text
    /// says the listing is failing, it keeps being re-checked at the normal cadence, and it is confirmed once the provider answers and the instance is
    /// gone. (The control is the test above: a listing that SUCCEEDED and showed nothing is permanent.)
    @Test
    void aMarkThatOnlyMetFailingListings_isRechecked_andConfirmedOnceTheProviderAnswers() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        retire(OLD);
        within(20, () -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id())));

        assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("a failing listing proves nothing") && !w.contains("by hand"));
        assertThat(persistedMark(OLD).map(m -> !m.seen() && !m.absent()).or(false)).as("not seen, and nothing proves it absent").isTrue();
        var before = lists.get();

        Thread.sleep(1500);

        assertThat(lists.get() - before).as("still re-checked at the normal cadence").isGreaterThanOrEqualTo(2);

        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        within(15, () -> assertThat(raised("instance-termination-confirmed")).as("confirmed once the provider answers").isTrue());
        assertThat(terminates.get()).isGreaterThanOrEqualTo(1);
        assertThat(persistedMark(OLD).isEmpty()).isTrue();
    }

    /// A failing-listing mark whose re-check finally gets a SUCCESSFUL listing that shows no instance becomes the permanent operator warning: the
    /// mark is rewritten, the event is raised again asking for a hand, and the re-checks stop.
    @Test
    void aFailingListingMark_becomesOperatorOnly_onceAListingSucceedsAndShowsNothing() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());
        ctmUnderTest.markUnconfirmed(OLD, "provider unreachable");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());

        listing.set(Promise.success(List.of()));

        within(10, () -> assertThat(persistedMark(OLD).map(AetherValue.UnconfirmedTerminationValue::absent).or(false)).as("rewritten as absent").isTrue());
        within(5, () -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("by hand")));
        var before = lists.get();

        Thread.sleep(1200);

        assertThat(lists.get() - before).as("and no longer re-checked").isZero();
    }

    /// The absence a listing showed belongs to the incarnation it listed: a node that joins again and then meets only failing listings is not the
    /// permanent operator warning its predecessor was.
    @Test
    void aRejoinedNode_doesNotInheritTheAbsenceItsPredecessorWasListedWith() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        assertThat(ctmUnderTest.reapRetired(OLD, WEST, false).await().isFailure()).as("a listing succeeded and showed nothing").isTrue();
        joins(OLD);
        states.put(OLD, "Dead");
        listing.set(EnvironmentError.operationNotSupported("provider API down").promise());

        retire(OLD);

        within(20, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());
        assertThat(persistedMark(OLD).map(AetherValue.UnconfirmedTerminationValue::absent).or(true)).as("only failures in this incarnation: not absent").isFalse();
    }

    @Test
    void aMarkOfAnInstanceThatWasListed_promisesTheClusterWillConfirmIt_andAsksForNoHand() throws Exception {
        markAfterSeeingTheInstance("the listing still showed the instance");

        within(5, () -> assertThat(warnings).anyMatch(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id()) && w.contains("confirms it by listing")));
        assertThat(warnings).noneMatch(w -> w.contains("by hand"));
    }

    /// (c) Ruling e9959fa6d(4): a manager that has just become leader does not mark what its activation replay finds listed - it terminates it. Marking
    /// is for an orphan whose terminate FAILED. A clean orphan reap raises neither event.
    @Test
    void aNewLeader_terminatesAnOrphanFromTheReplay_withoutRaisingAnUnconfirmedEvent() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(labelled(OLD, "i-1"))));

        var successor = newManager(lifecycle, true);

        within(20, () -> assertThat(terminates.get()).isEqualTo(1));
        within(10, () -> assertThat(reservation(OLD).isEmpty()).as("confirmed: the reservation is released").isTrue());
        Thread.sleep(500);

        assertThat(warnings).as("tried and confirmed: neither event").noneMatch(w -> w.contains(OLD.id()));
        assertThat(successor).isNotSameAs(ctmUnderTest);
    }

    /// Ruling e9959fa6d(4): UNCONFIRMED means "tried and not confirmed". An orphan whose terminate is still in flight is not marked; it is marked
    /// when its attempts fail (the provider accepted them and the instance is still listed).
    @Test
    void anOrphanFromTheReplay_isMarkedOnlyAfterItsTerminateAttemptsFail() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(labelled(OLD, "i-1"))));
        var gate = Promise.<Unit> promise();

        terminateGate.set(gate);
        newManager(lifecycle, true, 600L, warnings);
        within(20, () -> assertThat(terminates.get()).as("the terminate is in flight").isEqualTo(1));
        Thread.sleep(800);

        assertThat(raised("instance-termination-unconfirmed")).as("in flight is not 'tried and not confirmed'").isFalse();
        assertThat(persistedMark(OLD).isEmpty()).isTrue();

        stuck.set(true);
        terminateGate.set(null);
        gate.succeed(Unit.unit());

        within(30, () -> assertThat(raised("instance-termination-unconfirmed")).as("raised once the attempts have failed").isTrue());
        assertThat(terminates.get()).as("after more than one attempt").isGreaterThan(1);
    }

    private Option<AetherValue.UnconfirmedTerminationValue> persistedMark(NodeId node) {
        return store.getTyped(new AetherKey.UnconfirmedTerminationKey(node), AetherValue.UnconfirmedTerminationValue.class);
    }

    /// (c) Rulings e9959fa6d(4) and (5). The marks live in the replicated store: written when the node is marked, inherited by the next leader, which
    /// does NOT announce the open mark again (the previous leader's warning is still open), re-checks it at once and, the instance being gone, raises
    /// the recovery and deletes the mark. The recovery is raised by the successor, a node that never raised the warning it closes.
    @Test
    void aMark_survivesALeaderChange_andTheSuccessorClosesTheOldLeadersWarning() throws Exception {
        markAfterSeeingTheInstance("the listing still showed the instance");

        within(10, () -> assertThat(persistedMark(OLD).map(AetherValue.UnconfirmedTerminationValue::seen).or(false)).as("persisted, with the memory that it was seen").isTrue());
        within(5, () -> assertThat(raised("instance-termination-unconfirmed")).isTrue());

        ctmUnderTest.deactivate();
        // The instance disappears while no leader is looking (the provider's delete completed).
        listing.set(Promise.success(List.of()));

        var successorEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();

        newManager(lifecycle, false, 60_000L, successorEvents);

        within(5, () -> assertThat(successorEvents).as("the successor closed a mark it did not write, at once").anyMatch(w -> w.startsWith("instance-termination-confirmed:" + OLD.id())));
        within(5, () -> assertThat(persistedMark(OLD).isEmpty()).as("and deleted it").isTrue());
        assertThat(successorEvents).as("without announcing the open mark again").noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
        assertThat(warnings.stream().filter(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id())).count())
            .as("the old leader's warning is the only one")
            .isEqualTo(1);
    }

    /// Ruling e9959fa6d(4): an inherited mark is not re-raised - not at adoption, and not when the successor's own reap attempts then fail either.
    @Test
    void aSuccessorsFailingReap_doesNotRaiseAgainAMarkItInherited() throws Exception {
        markAfterSeeingTheInstance("the listing still showed the instance");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());
        ctmUnderTest.deactivate();
        stuck.set(true);
        listing.set(Promise.success(List.of(labelled(OLD, "i-1"))));
        var successorEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();

        newManager(lifecycle, true, 600L, successorEvents);

        within(30, () -> assertThat(terminates.get()).as("the replay's chain ran its retries out").isGreaterThanOrEqualTo(13));
        Thread.sleep(500);

        assertThat(successorEvents).as("the mark was inherited: nothing raised again").noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
    }

    /// An inherited mark carries the memory that the instance was seen into the successor's own reap, so that a drain on the successor, whose
    /// first listing is empty, is confirmed gone and closes the mark. (The at-once re-check is made to fail first, by an unreachable provider; the periodic one is 15 s away.)
    @Test
    void aSuccessorsOwnReap_remembersWhatTheInheritedMarkSaw() throws Exception {
        markAfterSeeingTheInstance("the listing still showed the instance");
        within(10, () -> assertThat(persistedMark(OLD).map(AetherValue.UnconfirmedTerminationValue::seen).or(false)).isTrue());
        ctmUnderTest.deactivate();
        var successorEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var successor = newManager(lifecycle, false, 3_000L, successorEvents);

        Thread.sleep(500);
        listing.set(Promise.success(List.of()));
        successor.drainNode(OLD, org.pragmatica.aether.deployment.cluster.DrainReason.OPERATOR_COMMAND).await();

        within(10, () -> assertThat(successorEvents).anyMatch(w -> w.startsWith("instance-termination-confirmed:" + OLD.id())));
        assertThat(persistedMark(OLD).isEmpty()).isTrue();
    }

    /// v-2068 checklist 3: adoption re-checks at once. The periodic re-check is 5 minutes away at this size (5 x 60 s) and the retry interval 10 s,
    /// so only an immediate re-check closes the mark inside two seconds.
    @Test
    void aSuccessor_rechecksAnInheritedMarkAtOnce_notAtTheNextPeriodicTick() throws Exception {
        markAfterSeeingTheInstance("the listing still showed the instance");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());
        ctmUnderTest.deactivate();
        listing.set(Promise.success(List.of()));

        newManager(lifecycle, false, 60_000L, warnings);

        within(2, () -> assertThat(persistedMark(OLD).isEmpty()).as("closed inside two seconds, long before any periodic tick").isTrue());
    }

    /// v-2068 checklist 3: the recovery of a marked EXTERNAL node by the re-check. EXTERNAL is re-checked by membership, not listing (so
    /// ruling (3) does not exempt it), only once the node has left, and with no provider call. Adopted by a successor whose periodic re-check is
    /// minutes away, so only the direct close can raise the event inside two seconds.
    @Test
    void aMarkedExternalNode_isClosedByTheRecheck_onceItHasLeft_withNoProviderCall() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(5, 1, true));
        put(new AetherKey.CapacityReservationKey(OLD), new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED));
        states.put(OLD, "Member");
        ctmUnderTest.markUnconfirmed(OLD, "release conflicted");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());
        Thread.sleep(1000);

        assertThat(reservation(OLD).isPresent()).as("a member keeps its reservation").isTrue();
        assertThat(raised("instance-termination-confirmed")).isFalse();

        ctmUnderTest.deactivate();
        states.remove(OLD);
        var successorEvents = new java.util.concurrent.CopyOnWriteArrayList<String>();

        newManager(lifecycle, false, 60_000L, successorEvents);

        within(2, () -> assertThat(successorEvents).anyMatch(w -> w.startsWith("instance-termination-confirmed:" + OLD.id())));
        assertThat(reservation(OLD).isEmpty()).as("its reservation is released").isTrue();
        assertThat(lists.get() + terminates.get()).as("no provider call").isZero();
    }

    /// A late failure marks a node whose reap another chain has already confirmed: the re-check closes it from the memory, with no provider call.
    @Test
    void aMarkRaisedAfterTheConfirmation_isClosedByTheRecheck_withoutAProviderCall() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));
        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());
        var calls = lists.get() + terminates.get();

        ctmUnderTest.markUnconfirmed(OLD, "a chain that lost the race");

        within(10, () -> assertThat(raised("instance-termination-confirmed")).isTrue());
        assertThat(lists.get() + terminates.get()).as("closed from the memory").isEqualTo(calls);
    }

    /// Ruling e9959fa6d(2): a node that joins is a new incarnation; the mark of the previous one is dropped, and the re-check never touches the
    /// member (no terminate).
    @Test
    void aNodeThatJoinsAgain_dropsTheMarkOfItsPreviousIncarnation() throws Exception {
        markAfterSeeingTheInstance("the listing still showed the instance");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());
        states.put(OLD, "Member");

        joins(OLD);

        within(5, () -> assertThat(persistedMark(OLD).isEmpty()).isTrue());
        within(5, () -> assertThat(raised("instance-termination-rejoined")).as("the open warning is closed with the rejoin resolution").isTrue());
        Thread.sleep(800);
        assertThat(terminates.get()).isZero();
        assertThat(raised("instance-termination-confirmed")).as("nothing was terminated: no confirmation").isFalse();
    }

    /// The dropped mark belongs to the previous incarnation: when the new one is retired and cannot be reaped, the operator is told again.
    @Test
    void aRejoinedNodeThatIsRetiredAgainAndCannotBeReaped_raisesTheEventAgain() throws Exception {
        markAfterSeeingTheInstance("the listing still showed the instance");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());

        joins(OLD);
        within(5, () -> assertThat(persistedMark(OLD).isEmpty()).isTrue());
        retire(OLD);

        within(20, () -> assertThat(warnings.stream().filter(w -> w.startsWith("instance-termination-unconfirmed:" + OLD.id())).count())
                             .as("once for each incarnation").isEqualTo(2));
    }

    @Test
    void aWorkerThatJoinsAgain_dropsTheMarkOfItsPreviousIncarnation() {
        ctmUnderTest.markUnconfirmed(OLD, "never listed");
        within(10, () -> assertThat(persistedMark(OLD).isPresent()).isTrue());

        ctmUnderTest.onWorkerJoin(new WorkerJoinDecision(OLD, "worker", HlcTimestamp.ZERO));

        within(5, () -> assertThat(persistedMark(OLD).isEmpty()).isTrue());
        within(5, () -> assertThat(raised("instance-termination-rejoined")).isTrue());
        assertThat(raised("instance-termination-confirmed")).isFalse();
    }

    private final class CountingProvider implements ComputeProvider {
        @Override
        public Promise<org.pragmatica.aether.environment.InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("create").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId id) {
            terminates.incrementAndGet();
            if (terminateGate.get() != null) {
                return terminateGate.get();
            }

            if (stuck.get()) {
                return Promise.unitPromise();
            }

            if (lagTerminate.get()) {
                org.pragmatica.lang.utils.SharedScheduler.schedule(() -> listing.set(Promise.success(List.of())), timeSpan(40).millis());
            } else {
                listing.set(Promise.success(List.of()));
            }

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
