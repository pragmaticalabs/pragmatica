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
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
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
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Structural CTM ownership fix — provider owns identity. The provisioning slot is written
/// UNASSIGNED at dispatch, then re-PUT ASSIGNED to the canonical NodeId the provider actually
/// allocated (echoed back via `InstanceInfo.nodeId()`) once the provision resolves. This closes
/// the ghost-id hole where CTM pre-allocated an `aether-core-node-*` id that no container ever
/// claimed:
///
///  - slot is written UNASSIGNED at dispatch (PROVISIONING placeholder, no ghost JOINING)
///  - on provider success the slot is re-PUT with the provider-allocated `assignedNodeId`
///  - on slot expiry, the assigned NodeId is authoritatively tombstoned (DECOMMISSIONED)
///    so a late-arriving node carrying that id cannot silently promote to ON_DUTY
///  - best-effort cloud-side reap via `lifecycleManager.terminateNode` reclaims the instance
///  - each dispatch yields a distinct provider-allocated NodeId
class ClusterTopologyManagerIdentityBoundSlotTest {
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

    private ClusterTopologyManager createCtm(TimeSpan provisioningTimeout) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      provisioningTimeout,
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

    /// Reseed a full 5-of-5 cluster (occupants bound to slots 0-4) then activate.
    private void activateFull() {
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
        ctm.activate();
    }

    /// Model the reducer STOPping `dead` (remove-then-add §3.4 step 1): its slot goes DEAD →
    /// CTM frees → EMPTY → refilled. Republish on-duty without the dead node.
    private void stopPeer(NodeId dead, Set<NodeId> remaining) {
        clusterStore.installStopped(dead);
        publishOnDuty(remaining);
    }

    private void publishOnDuty(Set<NodeId> onDuty) {
        var all = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        snapshotSource.publish(StubView.stubView(all, onDuty, onDuty.size(), 5), snapshotSource.term.get() + 1L);
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {
            if (onDuty.contains(id)) {clusterStore.installOnDuty(id, epoch++);}
        }
    }

    private void awaitAssignedSlot() throws InterruptedException {
        var deadline = System.currentTimeMillis() + 2000L;

        while (countAssignedNewSlots() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
    }

    private long countAssignedNewSlots() {
        return clusterStore.slots()
                           .values()
                           .stream()
                           .filter(slot -> slot.assignedNodeId().map(id -> id.id().startsWith("aether-test-node-")).or(false))
                           .count();
    }

    /// Provider-owns-identity (slot-based-membership-convergence-spec §5.3): the provider mints the
    /// real id (echoed via `InstanceInfo.nodeId()`) and CTM binds it to the stable integer slot.
    /// CTM does NOT pre-allocate `ProvisionContext.nodeId`.
    @Test
    void provisionIntoSlot_bindsProviderAllocatedIdToIntegerSlot() throws InterruptedException {
        ctm = createCtm(timeSpan(60).seconds());
        activateFull();
        // Reducer STOPs PEER_D (youngest → slot 4); CTM frees the DEAD slot and refills it.
        stopPeer(PEER_D, Set.of(SELF, PEER_A, PEER_B, PEER_C));
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        awaitAssignedSlot();
        assertThat(countAssignedNewSlots()).as("freed DEAD slot refilled with provider-allocated occupant").isEqualTo(1);
        var newSlot = clusterStore.slots()
                                  .entrySet()
                                  .stream()
                                  .filter(e -> e.getValue().assignedNodeId().map(id -> id.id().startsWith("aether-test-node-")).or(false))
                                  .findFirst()
                                  .orElseThrow();
        assertThat(newSlot.getKey().slotId()).as("slot is keyed by a stable integer index").matches("\\d+");
        assertThat(newSlot.getValue().assignedNodeId().unwrap().id()).isEqualTo(lifecycleManager.lastAllocatedId());
        assertThat(lifecycleManager.provisionedContextNodeIds)
                .as("CTM does NOT pre-allocate ProvisionContext.nodeId — provider owns identity")
                .isEmpty();
    }

    /// No-id edge: when the provider reports an empty `InstanceInfo.nodeId()` the slot is left
    /// FILLING (no occupant) to expire and reset — no ghost JOINING is written.
    @Test
    void provisionIntoSlot_leavesSlotFilling_whenProviderReportsNoId() throws InterruptedException {
        lifecycleManager.echoNodeId.set(false);
        ctm = createCtm(timeSpan(60).seconds());
        activateFull();
        stopPeer(PEER_D, Set.of(SELF, PEER_A, PEER_B, PEER_C));
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        Thread.sleep(100L);
        assertThat(countAssignedNewSlots()).as("no occupant bound when provider reports no id").isZero();
        var fillingNoOccupant = clusterStore.slots()
                                            .values()
                                            .stream()
                                            .anyMatch(slot -> slot.spawnedAtMs() > 0L && slot.assignedNodeId().isEmpty());
        assertThat(fillingNoOccupant).as("slot stays FILLING (marker stamped, no occupant)").isTrue();
    }

    /// D2 fast-free: a DEAD slot (occupant STOPPED) is freed with a best-effort cloud reap and NO
    /// drain ack (the `NoOpDrainCoordinator` never acks; termination still proceeds).
    @Test
    void deadSlot_freedWithoutDrainAck_andCloudReaped() throws InterruptedException {
        ctm = createCtm(timeSpan(60).seconds());
        activateFull();
        var terminateBefore = lifecycleManager.terminateCount.get();
        stopPeer(PEER_D, Set.of(SELF, PEER_A, PEER_B, PEER_C));
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        var deadline = System.currentTimeMillis() + 2000L;

        while (lifecycleManager.terminateCount.get() == terminateBefore && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        assertThat(lifecycleManager.terminateCount.get())
                .as("dead occupant cloud-reaped (best-effort, no drain ack)")
                .isGreaterThan(terminateBefore);
        assertThat(lifecycleManager.terminatedNodes).as("terminateNode called with the dead occupant id").contains(PEER_D);
    }

    /// Two EMPTY slots fill to two DISTINCT stable integer slots with distinct provider ids.
    @Test
    void provisionIntoSlot_assignsDistinctIntegerSlots() throws InterruptedException {
        ctm = createCtm(timeSpan(60).seconds());
        activateFull();
        clusterStore.installStopped(PEER_C);
        clusterStore.installStopped(PEER_D);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B));
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        var deadline = System.currentTimeMillis() + 2000L;

        while (countAssignedNewSlots() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        var assignedIds = clusterStore.slots()
                                      .values()
                                      .stream()
                                      .map(ProvisioningSlotValue::assignedNodeId)
                                      .filter(Option::isPresent)
                                      .map(Option::unwrap)
                                      .filter(id -> id.id().startsWith("aether-test-node-"))
                                      .toList();
        assertThat(assignedIds).as("each refilled slot has a distinct provider-allocated occupant").doesNotHaveDuplicates();
        assertThat(lifecycleManager.allocatedIds()).doesNotHaveDuplicates();
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {
        static StubView stubView(Set<NodeId> coreMemberIds,
                                 Set<NodeId> onDutyMemberIds,
                                 int healthyOnDutyCount,
                                 int desiredCoreSize) {
            return new StubView(coreMemberIds,
                                onDutyMemberIds,
                                healthyOnDutyCount,
                                desiredCoreSize,
                                onDutyMemberIds,
                                Set.of());
        }
    }

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

    /// KV-store fixture covering slots, cluster-config, and lifecycle atoms. Production
    /// `provisionNodes → writeProvisioningSlotAtom` writes through `apply(...)`, observable
    /// via `slots()`. Tombstone writes from `lifecycleWriter.requestDecommission` flow
    /// through the same `apply(...)` and end up in `lifecycleKv` — observable via
    /// `lifecycle(nodeId)`.
    private static final class RecordingClusterStore {
        final AtomicInteger slotPutCount = new AtomicInteger();
        final AtomicInteger slotRemoveCount = new AtomicInteger();
        private final AtomicReference<Option<ClusterConfigValue>> clusterConfig = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, ProvisioningSlotKey> lastSlotKeyByNodeId = new ConcurrentHashMap<>();

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

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                          "host-" + nodeId.id(),
                                                                          5000,
                                                                          org.pragmatica.aether.slice.generation.Epoch.epoch(0L, epoch)));
        }

        void installStopped(NodeId nodeId) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, "host-" + nodeId.id(), 5000));
        }

        Map<ProvisioningSlotKey, ProvisioningSlotValue> slots() {
            return new LinkedHashMap<>(slotKv);
        }

        Option<NodeLifecycleValue> lifecycle(NodeId nodeId) {
            return Option.option(lifecycleKv.get(nodeId));
        }

        ProvisioningSlotKey lastSlotKeyFor(NodeId nodeId) {
            return lastSlotKeyByNodeId.get(nodeId);
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
                slotKv.put(psk, psv);
                psv.assignedNodeId().onPresent(id -> lastSlotKeyByNodeId.put(id, psk));
            } else if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue cv) {
                clusterConfig.set(Option.some(cv));
            } else if (put.key() instanceof NodeLifecycleKey nlk && put.value() instanceof NodeLifecycleValue nlv) {
                lifecycleKv.put(nlk.nodeId(), nlv);
            }
        }

        private void applyRemove(KVCommand.Remove<AetherKey> remove) {
            if (remove.key() instanceof ProvisioningSlotKey psk) {
                slotRemoveCount.incrementAndGet();
                slotKv.remove(psk);
            }
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        final List<String> provisionedContextNodeIds = Collections.synchronizedList(new ArrayList<>());
        final List<String> allocatedIds = Collections.synchronizedList(new ArrayList<>());
        final List<NodeId> terminatedNodes = Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicBoolean echoNodeId = new java.util.concurrent.atomic.AtomicBoolean(true);

        List<String> allocatedIds() {
            return List.copyOf(allocatedIds);
        }

        String lastAllocatedId() {
            return allocatedIds.getLast();
        }

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub")
                                                                                                    .unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            var count = provisionCount.incrementAndGet();
            spec.context().nodeId().onPresent(provisionedContextNodeIds::add);
            var allocated = "aether-test-node-" + count;
            allocatedIds.add(allocated);
            var echoed = echoNodeId.get()
                         ? Option.some(allocated)
                         : Option.<String>none();
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + count).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND,
                                                             Map.of(),
                                                             echoed).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            terminateCount.incrementAndGet();
            terminatedNodes.add(nodeId);
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
