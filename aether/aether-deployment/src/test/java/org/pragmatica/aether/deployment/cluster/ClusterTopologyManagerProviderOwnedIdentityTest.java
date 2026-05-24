// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.LifecycleState;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Provider-owns-identity CTM contract.
///
/// CTM no longer pre-allocates an `aether-core-node-*` id and writes a slot ASSIGNED to it (the
/// ghost id that no container claimed). Instead it writes the slot UNASSIGNED at dispatch and
/// re-PUTs it ASSIGNED only after the provider resolves and echoes the canonical id it actually
/// used via `InstanceInfo.nodeId()`. Two-phase: UNASSIGNED → (provider id) → ASSIGNED.
///
/// Also verifies the JOINING-aware deficit math: a node that is JOINING (capacity-in-progress)
/// is subtracted from the raw deficit so the formation wave does not redundantly provision.
class ClusterTopologyManagerProviderOwnedIdentityTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId PEER_D = nodeId("node-d").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());
    private static final NodeInfo INFO_C = NodeInfo.nodeInfo(PEER_C, NodeAddress.nodeAddress("localhost", 5003).unwrap());
    private static final NodeInfo INFO_D = NodeInfo.nodeInfo(PEER_D, NodeAddress.nodeAddress("localhost", 5004).unwrap());

    private static final String PROVIDER_ID = "aether-a-node-6";

    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private RecordingClusterStore clusterStore;

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        clusterStore = new RecordingClusterStore();
        clusterStore.seedClusterConfig(5);
    }

    private ClusterTopologyManager createCtm() {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      timeSpan(60).seconds(),
                                                      NEGLIGIBLE_STABILITY)
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                              lifecycleManager,
                                                              autoHeal,
                                                              DeploymentMap.deploymentMap(),
                                                              snapshotSource,
                                                              clusterStore::currentClusterConfig,
                                                              clusterStore::lifecycle,
                                                              clusterStore::slots,
                                                              clusterStore::apply,
                                                              new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                              LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                   clusterStore::lifecycle,
                                                                                                   System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    @Nested
    class TwoPhaseProvisioning {
        @Test
        void provisionNodes_writesSlotUnassignedThenAssignsProviderId_whenProviderEchoesNodeId() {
            lifecycleManager.echoedId.set(PROVIDER_ID);
            var ctm = createCtm();
            snapshotSource.publish(view(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        5,
                                        5),
                                   1L);
            ctm.activate();
            // Drop one peer — leader provisions exactly one replacement.
            snapshotSource.publish(view(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                        4,
                                        5),
                                   2L);
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));

            assertThat(lifecycleManager.provisionCount.get())
                    .as("deficit=1 → one provision dispatch")
                    .isEqualTo(1);
            // Phase 1: an UNASSIGNED slot atom was written before the assigned re-PUT.
            assertThat(clusterStore.firstSlotPutWasUnassigned.get())
                    .as("slot is written UNASSIGNED first — no JOINING for any ghost id")
                    .isTrue();
            assertThat(clusterStore.assignedNodeIdsWritten())
                    .as("no aether-core-node-* ghost id is ever assigned to a slot")
                    .noneMatch(id -> id.id().startsWith("aether-core-node"));
            // Phase 2: after the provision resolves, the slot is re-PUT ASSIGNED to the real id.
            var slots = clusterStore.slots();
            assertThat(slots).as("exactly one live slot atom").hasSize(1);
            var assigned = slots.values().iterator().next().assignedNodeId();
            assertThat(assigned.isPresent()).as("slot re-PUT ASSIGNED after provision resolved").isTrue();
            assertThat(assigned.unwrap().id())
                    .as("slot assignedNodeId is the provider-echoed id")
                    .isEqualTo(PROVIDER_ID);
            // slotKeyByNodeId is keyed by the real id — completion cleanup matches.
            var realId = nodeId(PROVIDER_ID).unwrap();
            var slotKey = slots.keySet().iterator().next();
            clusterStore.installLifecycle(realId,
                                          NodeLifecycleValue.nodeLifecycleValue(AetherValue.NodeLifecycleState.ON_DUTY,
                                                                                "host-real",
                                                                                6000));
            ctm.onNodeReady(realId);
            assertThat(clusterStore.slots())
                    .as("onNodeReady(realId) removes the slot keyed by the provider id")
                    .doesNotContainKey(slotKey);
        }

        @Test
        void provisionNodes_leavesSlotUnassigned_whenProviderReportsNoId() {
            lifecycleManager.echoedId.set(null);
            var ctm = createCtm();
            snapshotSource.publish(view(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        5,
                                        5),
                                   1L);
            ctm.activate();
            snapshotSource.publish(view(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                        4,
                                        5),
                                   2L);
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));

            assertThat(lifecycleManager.provisionCount.get()).isEqualTo(1);
            var slots = clusterStore.slots();
            assertThat(slots).as("slot atom written").hasSize(1);
            assertThat(slots.values().iterator().next().assignedNodeId().isPresent())
                    .as("provider reported no id → slot stays UNASSIGNED, no ghost JOINING")
                    .isFalse();
            assertThat(clusterStore.assignedNodeIdsWritten())
                    .as("no slot was ever PUT with an assigned id")
                    .isEmpty();
        }
    }

    @Nested
    class JoiningAwareDeficit {
        @Test
        void reconcile_dispatchesZeroProvisions_whenJoiningPlusOnDutyCoversDesired() {
            var ctm = createCtm();
            // Activate converged at 5/5 so subsequent reconcile() runs reconcileActive (not the
            // activation leader-failover path, which bypasses the joining-aware deficit math).
            snapshotSource.publish(view(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        5,
                                        5),
                                   1L);
            ctm.activate();
            assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
            // desired=5, 4 ON_DUTY + 1 JOINING = 5 capacity (in-progress) → deficit must be 0.
            snapshotSource.publish(joiningView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                               Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                               4,
                                               5,
                                               Map.of(PEER_D, LifecycleState.JOINING)),
                                   2L);
            // A topology event drives reconcileActive while the JOINING node has not yet matured.
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));

            assertThat(lifecycleManager.provisionCount.get())
                    .as("JOINING node covers the deficit — zero provisions dispatched")
                    .isZero();
            assertThat(clusterStore.slots())
                    .as("no slot atom written when capacity-in-progress covers the gap")
                    .isEmpty();
            assertThat(ctm.reconcilerState())
                    .as("CTM stays out of Reconciling — no replacement wave")
                    .isNotInstanceOf(NodeReconcilerState.Reconciling.class);
        }

        @Test
        void reconcile_dispatchesProvision_whenDeficitExceedsJoiningCapacity() {
            lifecycleManager.echoedId.set(PROVIDER_ID);
            var ctm = createCtm();
            snapshotSource.publish(view(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                        5,
                                        5),
                                   1L);
            ctm.activate();
            assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
            // desired=5, 3 ON_DUTY + 1 JOINING = 4 → genuine deficit of 1 remains after joining.
            snapshotSource.publish(joiningView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                               Set.of(SELF, PEER_A, PEER_B),
                                               3,
                                               5,
                                               Map.of(PEER_C, LifecycleState.JOINING)),
                                   2L);
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));

            assertThat(lifecycleManager.provisionCount.get())
                    .as("deficit beyond JOINING capacity still triggers provisioning")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    private static MembershipView view(Set<NodeId> coreMemberIds,
                                       Set<NodeId> onDutyMemberIds,
                                       int healthyOnDutyCount,
                                       int desiredCoreSize) {
        return new StubView(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, coreMemberIds, Map.of());
    }

    private static MembershipView joiningView(Set<NodeId> coreMemberIds,
                                              Set<NodeId> onDutyMemberIds,
                                              int healthyOnDutyCount,
                                              int desiredCoreSize,
                                              Map<NodeId, LifecycleState> lifecycleStates) {
        return new StubView(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, coreMemberIds, lifecycleStates);
    }

    /// `joiningCount()` is intentionally NOT overridden — it exercises the real default
    /// implementation that derives the count from `lifecycleStates()`.
    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Map<NodeId, LifecycleState> lifecycleStates) implements MembershipView {}

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());
        private final AtomicLong term = new AtomicLong(0L);

        void publish(MembershipView v, long rabiaTerm) {
            view.set(Option.some(v));
            term.set(rabiaTerm);
        }

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return term.get();
        }
    }

    private static final class RecordingClusterStore {
        final AtomicInteger slotPutCount = new AtomicInteger();
        final AtomicReference<Boolean> firstSlotPutWasUnassigned = new AtomicReference<>(null);
        private final AtomicReference<Option<ClusterConfigValue>> clusterConfig = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new ConcurrentHashMap<>();
        private final List<NodeId> assignedWrites = Collections.synchronizedList(new ArrayList<>());

        void seedClusterConfig(int coreCount) {
            clusterConfig.set(Option.some(new ClusterConfigValue("",
                                                                 "",
                                                                 "1.0.0",
                                                                 coreCount,
                                                                 3,
                                                                 9,
                                                                 "test",
                                                                 1L,
                                                                 System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> currentClusterConfig() {
            return clusterConfig.get();
        }

        Option<NodeLifecycleValue> lifecycle(NodeId nodeId) {
            return Option.option(lifecycleKv.get(nodeId));
        }

        Map<ProvisioningSlotKey, ProvisioningSlotValue> slots() {
            return new LinkedHashMap<>(slotKv);
        }

        void installLifecycle(NodeId nodeId, NodeLifecycleValue value) {
            lifecycleKv.put(nodeId, value);
        }

        List<NodeId> assignedNodeIdsWritten() {
            return List.copyOf(assignedWrites);
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {applyOne(command);}
            return Promise.success(List.of());
        }

        private void applyOne(KVCommand<AetherKey> command) {
            switch (command) {
                case KVCommand.Put<AetherKey, ?> put -> applyPut(put);
                case KVCommand.Remove<AetherKey> remove -> applyRemove(remove);
                default -> {}
            }
        }

        private void applyPut(KVCommand.Put<AetherKey, ?> put) {
            if (put.key() instanceof ProvisioningSlotKey psk && put.value() instanceof ProvisioningSlotValue psv) {
                slotPutCount.incrementAndGet();
                firstSlotPutWasUnassigned.compareAndSet(null, psv.assignedNodeId().isEmpty());
                psv.assignedNodeId().onPresent(assignedWrites::add);
                slotKv.put(psk, psv);
            } else if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue cv) {
                clusterConfig.set(Option.some(cv));
            } else if (put.key() instanceof NodeLifecycleKey nlk && put.value() instanceof NodeLifecycleValue nlv) {
                lifecycleKv.put(nlk.nodeId(), nlv);
            }
        }

        private void applyRemove(KVCommand.Remove<AetherKey> remove) {
            if (remove.key() instanceof ProvisioningSlotKey psk) {
                slotKv.remove(psk);
            }
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicReference<String> echoedId = new AtomicReference<>(PROVIDER_ID);

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            var count = provisionCount.incrementAndGet();
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + count).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND,
                                                             Map.of(),
                                                             Option.option(echoedId.get())).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
        }
    }
}
