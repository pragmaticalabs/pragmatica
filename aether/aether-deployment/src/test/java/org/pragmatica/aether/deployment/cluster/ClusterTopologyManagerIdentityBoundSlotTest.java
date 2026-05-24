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

    private void publishFullCluster() {
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                               1L);
    }

    private void publishDeficitOfOne(long term) {
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               term);
    }

    /// Step 1 of the structural fix: production writes the slot UNASSIGNED at dispatch and then
    /// re-PUTs it ASSIGNED to the canonical id the provider allocated (echoed via
    /// `InstanceInfo.nodeId()`). The provider — not the CTM — owns identity; `ProvisionContext.
    /// nodeId` is left empty so the provider mints the id and reports it back.
    @Test
    void provisionNodes_writesSlotAssignedToProviderAllocatedId() {
        var ctm = createCtm(timeSpan(60).seconds());
        publishFullCluster();
        ctm.activate();
        publishDeficitOfOne(2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
                .as("deficit=1 → exactly one provision dispatch")
                .isEqualTo(1);
        var slots = clusterStore.slots();
        assertThat(slots).as("exactly one slot atom written").hasSize(1);
        var slotValue = slots.values().iterator().next();
        assertThat(slotValue.assignedNodeId().isPresent())
                .as("slot atom re-PUT ASSIGNED to provider-allocated id after provision resolves")
                .isTrue();
        var slotNodeId = slotValue.assignedNodeId().unwrap();
        assertThat(slotNodeId.id())
                .as("slot assignedNodeId equals the id the provider echoed back via InstanceInfo")
                .isEqualTo(lifecycleManager.lastAllocatedId());
        assertThat(lifecycleManager.provisionedContextNodeIds)
                .as("CTM does NOT pre-allocate ProvisionContext.nodeId — provider owns identity")
                .isEmpty();
    }

    /// Provider-owns-identity, no-id edge: when the provider reports an empty `InstanceInfo.
    /// nodeId()` (should not happen for docker/cloud) the slot is left UNASSIGNED — no ghost
    /// JOINING is written, and the slot expires/GCs cleanly.
    @Test
    void provisionNodes_leavesSlotUnassigned_whenProviderReportsNoId() {
        lifecycleManager.echoNodeId.set(false);
        var ctm = createCtm(timeSpan(60).seconds());
        publishFullCluster();
        ctm.activate();
        publishDeficitOfOne(2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
                .as("deficit=1 → exactly one provision dispatch")
                .isEqualTo(1);
        var slots = clusterStore.slots();
        assertThat(slots).as("slot atom still written (UNASSIGNED)").hasSize(1);
        assertThat(slots.values().iterator().next().assignedNodeId().isPresent())
                .as("slot stays UNASSIGNED when provider reports no id — no ghost JOINING")
                .isFalse();
    }

    /// Step 2 of the structural fix: when a slot expires unfulfilled, the assigned NodeId is
    /// authoritatively tombstoned (NodeLifecycleKey → DECOMMISSIONED) and the cloud-side
    /// instance is reaped (best-effort) via `lifecycleManager.terminateNode`. Together these
    /// close the door on late-arriving nodes silently joining as ON_DUTY.
    @Test
    void slotExpiry_tombstonesAssignedNodeId() throws InterruptedException {
        var ctm = createCtm(timeSpan(50).millis());
        publishFullCluster();
        ctm.activate();
        publishDeficitOfOne(2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(clusterStore.slots()).hasSize(1);
        var assignedId = clusterStore.slots().values().iterator().next().assignedNodeId().unwrap();
        // Let the slot deadline lapse.
        Thread.sleep(200L);
        var terminateBefore = lifecycleManager.terminateCount.get();
        // Drive an expiry tick via a reconcile trigger. expireSlots() runs from a Reconciling
        // state during reconcileActive — a benign membership event re-runs the path.
        publishDeficitOfOne(3L);
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(clusterStore.slots())
                .as("expired slot atom removed")
                .doesNotContainKey(clusterStore.lastSlotKeyFor(assignedId));
        var tombstone = clusterStore.lifecycle(assignedId);
        assertThat(tombstone.isPresent())
                .as("lifecycle atom written for expired-slot owner")
                .isTrue();
        assertThat(tombstone.unwrap().state())
                .as("expired-slot owner authoritatively DECOMMISSIONED")
                .isEqualTo(NodeLifecycleState.STOPPED);
        assertThat(lifecycleManager.terminateCount.get())
                .as("cloud-side instance reap requested (best-effort)")
                .isGreaterThan(terminateBefore);
        assertThat(lifecycleManager.terminatedNodes)
                .as("terminateNode called with the expired slot's assignedNodeId")
                .contains(assignedId);
    }

    /// Step 3 of the structural fix: a late-arriving node carrying a tombstoned NodeId must NOT
    /// be promoted to ON_DUTY. The DECOMMISSIONED tombstone written on expiry is the gate —
    /// downstream consumers (MembershipFsm, MembershipView, dashboard) reading
    /// `NodeLifecycleKey` see DECOMMISSIONED and refuse promotion. We assert the gate is in
    /// place by checking the lifecycle store, which is the authoritative source.
    @Test
    void lateArrival_afterSlotExpiry_doesNotPromote() throws InterruptedException {
        var ctm = createCtm(timeSpan(50).millis());
        publishFullCluster();
        ctm.activate();
        publishDeficitOfOne(2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        var assignedId = clusterStore.slots().values().iterator().next().assignedNodeId().unwrap();
        Thread.sleep(200L);
        publishDeficitOfOne(3L);
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        // Tombstone is now in place.
        assertThat(clusterStore.lifecycle(assignedId).unwrap().state())
                .as("tombstone exists before simulated late arrival")
                .isEqualTo(NodeLifecycleState.STOPPED);
        // Simulate the late-arriving node showing up as ON_DUTY in a snapshot. The FSM
        // single-writer rule applies in production; here we assert that the lifecycle gate
        // (DECOMMISSIONED tombstone) survives — i.e., the late-arriving node does not get
        // promoted by any code path going through the CTM. The CTM never writes ON_DUTY for
        // such a node — only MembershipFsm does, and its `(DECOMMISSIONED, SwimHealthy) → nop`
        // cell is the FSM-level gate.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D, assignedId),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, assignedId),
                                                5,
                                                5),
                               4L);
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(assignedId,
                                                                List.of(SELF,
                                                                         PEER_A,
                                                                         PEER_B,
                                                                         PEER_C,
                                                                         PEER_D,
                                                                         assignedId)));
        assertThat(clusterStore.lifecycle(assignedId).unwrap().state())
                .as("tombstone survives late arrival — CTM never overwrites DECOMMISSIONED")
                .isEqualTo(NodeLifecycleState.STOPPED);
    }

    /// Step 4 of the structural fix: each provisioning dispatch must produce a globally unique
    /// NodeId. Two back-to-back dispatches must NOT collide.
    @Test
    void provisionNodes_generatesUniqueIds() {
        var ctm = createCtm(timeSpan(60).seconds());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                               1L);
        ctm.activate();
        // Deficit=2 in one snapshot → two provisions back-to-back from the same reconcile pass.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B),
                                                3,
                                                5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get())
                .as("two deficit-driven dispatches expected")
                .isGreaterThanOrEqualTo(2);
        var assignedIds = clusterStore.slots().values().stream()
                                                         .map(ProvisioningSlotValue::assignedNodeId)
                                                         .filter(Option::isPresent)
                                                         .map(Option::unwrap)
                                                         .toList();
        assertThat(assignedIds).as("each slot has a distinct assignedNodeId")
                                                         .doesNotHaveDuplicates();
        assertThat(lifecycleManager.allocatedIds()).as("each provision yielded a distinct provider-allocated id")
                                                                                                                .doesNotHaveDuplicates();
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
