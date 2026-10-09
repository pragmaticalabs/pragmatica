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
    private final java.util.List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean lagTerminate = new java.util.concurrent.atomic.AtomicBoolean();
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
        var snapshotSource = new GenerationSnapshotSource() {
            @Override public Option<MembershipView> currentMembershipView() { return Option.none(); }
            @Override public long observedRabiaTerm() { return 0L; }
        };
        var self = NodeInfo.nodeInfo(CORE, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(CORE, 3, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                lifecycleManager,
                                                                AutoHealConfig.DEFAULT.withProvisioningTimeout(timeSpan(60).millis()),
                                                                DeploymentMap.deploymentMap(),
                                                                snapshotSource,
                                                                () -> Option.<ClusterConfigValue> none(),
                                                                commands -> Promise.success(List.<Object> of()),
                                                                () -> AetherValue.ClusterPhase.NORMAL,
                                                                _ -> {},
                                                                _ -> {},
                                                                Option::none,
                                                                MembershipLiveness.membershipLiveness(Set::of,
                                                                                                      Set::of,
                                                                                                      node -> Option.option(states.get(node)).filter(state -> !"Dead".equals(state)).isPresent(),
                                                                                                      _ -> false,
                                                                                                      Set::of,
                                                                                                      () -> 3,
                                                                                                      _ -> Option.none()));

        ctm.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warning -> warnings.add(warning.code().code() + ":" + warning.subject() + ":" + warning.message())));
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

    /// B1 (v-2062): NodeRemoved and the drain-grace backstop both reap a drained node. The second finds the instance already gone and is
    /// not re-asked: no provider call, and no "may still be running" event.
    @Test
    void aSecondReapOfAnAlreadyConfirmedInstance_raisesNoUnconfirmedEvent() throws Exception {
        put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(3, 1, true));
        listing.set(Promise.success(List.of(instance(OLD, "i-1", InstanceStatus.RUNNING))));

        retire(OLD);
        within(10, () -> assertThat(reservation(OLD).isEmpty()).isTrue());
        var calls = lists.get() + terminates.get();

        retire(OLD);
        Thread.sleep(1500);

        assertThat(lists.get() + terminates.get()).as("not re-asked").isEqualTo(calls);
        assertThat(warnings).as("confirmed once, never reported unconfirmed").noneMatch(w -> w.startsWith("instance-termination-unconfirmed:"));
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

    private final class CountingProvider implements ComputeProvider {
        @Override
        public Promise<org.pragmatica.aether.environment.InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("create").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId id) {
            terminates.incrementAndGet();
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
