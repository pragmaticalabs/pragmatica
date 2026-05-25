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
    private ClusterTopologyManager ctm;

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

    private void activateFull() {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D), Map.of());
        ctm.activate();
    }

    private void publishOnDuty(Set<NodeId> onDuty, Map<NodeId, LifecycleState> lifecycleStates) {
        var all = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        snapshotSource.publish(joiningView(all, onDuty, onDuty.size(), 5, lifecycleStates), snapshotSource.term.get() + 1L);
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {
            if (onDuty.contains(id)) {clusterStore.installOnDuty(id, epoch++);}
        }
    }

    private void awaitProvision(int atLeast) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 2000L;

        while (lifecycleManager.provisionCount.get() < atLeast && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    @Nested
    class ProviderOwnsIdentity {
        /// The provider mints the real id (echoed via `InstanceInfo.nodeId()`); CTM binds it to the
        /// freed slot. CTM never pre-allocates a ghost id and never writes a ghost-assigned slot.
        @Test
        void freedSlot_refilledWithProviderEchoedId_notAGhostId() throws InterruptedException {
            lifecycleManager.echoedId.set(PROVIDER_ID);
            ctm = createCtm();
            activateFull();
            // Reducer STOPs PEER_D → its slot DEAD → freed → refilled with the provider-echoed id.
            clusterStore.installStopped(PEER_D);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), Map.of());
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
            awaitProvision(1);
            assertThat(clusterStore.assignedNodeIdsWritten())
                    .as("no aether-core-node-* ghost id is ever assigned to a slot")
                    .noneMatch(id -> id.id().startsWith("aether-core-node"));
            var assigned = clusterStore.slots()
                                       .values()
                                       .stream()
                                       .map(ProvisioningSlotValue::assignedNodeId)
                                       .filter(Option::isPresent)
                                       .map(Option::unwrap)
                                       .anyMatch(id -> id.id().equals(PROVIDER_ID));
            assertThat(assigned).as("freed slot re-bound to the provider-echoed id").isTrue();
        }

        /// Durable slots (D1): when the occupant reaches ON_DUTY the slot is NOT deleted — it stays
        /// bound and reclassifies HEALTHY (replaces the old delete-on-ON_DUTY behavior).
        @Test
        void slotNotDeleted_whenOccupantReachesOnDuty() throws InterruptedException {
            lifecycleManager.echoedId.set(PROVIDER_ID);
            ctm = createCtm();
            activateFull();
            clusterStore.installStopped(PEER_D);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), Map.of());
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
            awaitProvision(1);
            var sizeAfterFill = clusterStore.slots().size();
            var realId = nodeId(PROVIDER_ID).unwrap();
            clusterStore.installOnDuty(realId, 9L);
            ctm.onNodeReady(realId);
            assertThat(clusterStore.slots())
                    .as("durable slot set keeps exactly clusterSize entries — slot NOT deleted on ON_DUTY")
                    .hasSize(sizeAfterFill);
        }

        @Test
        void noOccupantBound_whenProviderReportsNoId() throws InterruptedException {
            lifecycleManager.echoedId.set(null);
            ctm = createCtm();
            activateFull();
            clusterStore.installStopped(PEER_D);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), Map.of());
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
            awaitProvision(1);
            // The freed slot (the one whose marker is stamped but provider returned no id) carries
            // a FILLING marker and NO occupant — no ghost JOINING.
            var fillingNoOccupant = clusterStore.slots()
                                                .values()
                                                .stream()
                                                .anyMatch(slot -> slot.spawnedAtMs() > 0L && slot.assignedNodeId().isEmpty());
            assertThat(fillingNoOccupant)
                    .as("provider reported no id → slot stays FILLING with no occupant")
                    .isTrue();
        }
    }

    @Nested
    class FillingAwareDeficit {
        /// A slot whose occupant is JOINING (FILLING) is NOT re-provisioned — the durable-slot
        /// replacement for the old joining-aware deficit subtraction.
        @Test
        void reconcile_doesNotRefillFillingSlot_whenOccupantJoining() throws InterruptedException {
            ctm = createCtm();
            activateFull();
            assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
            // PEER_D is JOINING (occupant present, not ON_DUTY, not STOPPED) → slot FILLING.
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), Map.of(PEER_D, LifecycleState.JOINING));
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
            Thread.sleep(100L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("FILLING slot (JOINING occupant) is not re-provisioned")
                    .isZero();
        }

        /// An EMPTY slot (occupant STOPPED → freed) IS filled.
        @Test
        void reconcile_fillsEmptySlot_whenOccupantStopped() throws InterruptedException {
            lifecycleManager.echoedId.set(PROVIDER_ID);
            ctm = createCtm();
            activateFull();
            clusterStore.installStopped(PEER_D);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C), Map.of());
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
            awaitProvision(1);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("STOPPED occupant → DEAD slot freed → EMPTY → filled")
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

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(AetherValue.NodeLifecycleState.ON_DUTY,
                                                                          "host-" + nodeId.id(),
                                                                          5000,
                                                                          org.pragmatica.aether.slice.generation.Epoch.epoch(0L, epoch)));
        }

        void installStopped(NodeId nodeId) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(AetherValue.NodeLifecycleState.STOPPED, "host-" + nodeId.id(), 5000));
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
