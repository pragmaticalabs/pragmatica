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


/// Regression for the stale-FILLING wedge (slot-based-membership-convergence-spec §5.1/§5.2): an
/// ASSIGNED slot whose occupant is stuck non-terminal (JOINING/DRAINING/absent — never reaches
/// STOPPED because the FSM JOIN_DEADLINE escape was lost to a non-leader delivery race) classifies
/// FILLING forever, so `selectEmptySlotsToFill` returns nothing and the cluster wedges
/// UNDER-provisioned after a chaos kill. `freeStaleFillingSlots` mirrors the unassigned-path
/// deadline contract (`classifyEmptyOrFilling`) for ASSIGNED slots, gated by the occupant having
/// ALREADY left the connected set so a live-but-slow JOINING node keeps its slot.
///
/// The connected set is the config peer list (SELF/A/B/C/D are seeded HEALTHY at construction); a
/// 6th node (PEER_E) is NOT in the config, hence NOT connected — the disconnected stuck occupant.
class ClusterTopologyManagerStaleFillingReclaimTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId PEER_D = nodeId("node-d").unwrap();
    private static final NodeId PEER_E = nodeId("node-e").unwrap();

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
        // 5-node config — SELF/A/B/C/D are all CONNECTED (seeded HEALTHY at construction). PEER_E is
        // intentionally NOT in the config so it is never connected: the disconnected stuck occupant.
        var config = new TopologyConfig(SELF,
                                        5,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B, INFO_C, INFO_D));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        clusterStore = new RecordingClusterStore();
        clusterStore.seedClusterConfig(5);
        ctm = createCtm(timeSpan(60).seconds());
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

    /// An ASSIGNED slot whose occupant is stuck JOINING, past the stamped deadline, and has left the
    /// connected set is reclaimed: the stale occupant is cleared (freed) and the freed index is
    /// selected for refill (bound to a connected node this same pass under universal slot-fill).
    @Test
    void reconcile_assignedSlotFillingPastDeadlineAndOccupantDisconnected_freesAndRefills() {
        var pastDeadline = System.currentTimeMillis() - 60_000L;
        clusterStore.putSlot(0, slotAssignedTo(PEER_E, pastDeadline));
        clusterStore.installJoining(PEER_E);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
        ctm.activate();
        triggerReconcile();

        assertThat(slotOccupant(0))
                .as("stale-FILLING occupant cleared from its slot")
                .isNotEqualTo(Option.some(PEER_E));
        assertThat(lifecycleManager.terminatedNodes)
                .as("freeSlot best-effort cloud-reaps the reclaimed stale occupant")
                .contains(PEER_E);
        assertThat(clusterStore.slotPutCount.get())
                .as("freed index drives a refill PUT (bind/provision)")
                .isPositive();
    }

    /// Same shape, but the occupant is CONNECTED — a live-but-slow JOINING node keeps its slot
    /// (safety gate: CTM never reclaims a node it can still reach).
    @Test
    void reconcile_assignedSlotFillingButOccupantConnected_keepsSlot() {
        var pastDeadline = System.currentTimeMillis() - 60_000L;
        clusterStore.putSlot(0, slotAssignedTo(PEER_A, pastDeadline));
        clusterStore.installJoining(PEER_A);
        publishOnDuty(Set.of(SELF, PEER_B, PEER_C, PEER_D));
        ctm.activate();
        triggerReconcile();

        assertThat(slotOccupant(0))
                .as("connected JOINING occupant keeps its slot — never reclaimed")
                .isEqualTo(Option.some(PEER_A));
    }

    /// Same shape, but the deadline has NOT lapsed — the grace window is honored, slot kept.
    @Test
    void reconcile_assignedSlotFillingWithinDeadline_keepsSlot() {
        var futureDeadline = System.currentTimeMillis() + 60_000L;
        clusterStore.putSlot(0, slotAssignedTo(PEER_E, futureDeadline));
        clusterStore.installJoining(PEER_E);
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
        ctm.activate();
        triggerReconcile();

        assertThat(slotOccupant(0))
                .as("within-deadline FILLING occupant keeps its slot — grace not expired")
                .isEqualTo(Option.some(PEER_E));
    }

    /// Absent-lifecycle occupant (the `.or(FILLING)` branch of `classifyOccupied`) past the deadline
    /// and disconnected is reclaimed exactly like the JOINING case.
    @Test
    void reconcile_assignedSlotFillingAbsentLifecyclePastDeadline_freesAndRefills() {
        var pastDeadline = System.currentTimeMillis() - 60_000L;
        clusterStore.putSlot(0, slotAssignedTo(PEER_E, pastDeadline));
        // No lifecycle atom for PEER_E — classifyOccupied falls back to FILLING.
        publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
        ctm.activate();
        triggerReconcile();

        assertThat(slotOccupant(0))
                .as("absent-lifecycle stale occupant cleared from its slot")
                .isNotEqualTo(Option.some(PEER_E));
        assertThat(lifecycleManager.terminatedNodes)
                .as("freeSlot best-effort cloud-reaps the reclaimed stale occupant")
                .contains(PEER_E);
    }

    private static ProvisioningSlotValue slotAssignedTo(NodeId occupant, long deadlineMs) {
        var spawnedAtMs = deadlineMs - 30_000L;
        return new ProvisioningSlotValue(spawnedAtMs, deadlineMs, Option.some(occupant), 1L, Option.none());
    }

    private ProvisioningSlotValue slotValue(int index) {
        return clusterStore.slots()
                           .get(ProvisioningSlotKey.provisioningSlotKey(Integer.toString(index)));
    }

    private Option<NodeId> slotOccupant(int index) {
        return slotValue(index).assignedNodeId();
    }

    /// A no-op membership event drives a synchronous reconcile() on the calling thread.
    private void triggerReconcile() {
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
    }

    private void publishOnDuty(Set<NodeId> onDuty) {
        var all = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        snapshotSource.publish(StubView.stubView(all, onDuty, onDuty.size(), 5), snapshotSource.term.get() + 1L);
        var epoch = 0L;
        for (var id : List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)) {
            if (onDuty.contains(id)) {clusterStore.installOnDuty(id, epoch++);}
        }
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
            return new StubView(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, onDutyMemberIds, Set.of());
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

    private static final class RecordingClusterStore {
        final AtomicInteger slotPutCount = new AtomicInteger();
        private final AtomicReference<Option<ClusterConfigValue>> clusterConfig = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new ConcurrentHashMap<>();

        void seedClusterConfig(int coreCount) {
            clusterConfig.set(Option.some(new ClusterConfigValue("", "", "1.0.0", coreCount, 3, 9, "test", 1L, System.currentTimeMillis())));
        }

        void putSlot(int index, ProvisioningSlotValue value) {
            slotKv.put(ProvisioningSlotKey.provisioningSlotKey(Integer.toString(index)), value);
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

        void installJoining(NodeId nodeId) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, "host-" + nodeId.id(), 5000));
        }

        Map<ProvisioningSlotKey, ProvisioningSlotValue> slots() {
            return new LinkedHashMap<>(slotKv);
        }

        Option<NodeLifecycleValue> lifecycle(NodeId nodeId) {
            return Option.option(lifecycleKv.get(nodeId));
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
            } else if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue cv) {
                clusterConfig.set(Option.some(cv));
            } else if (put.key() instanceof NodeLifecycleKey nlk && put.value() instanceof NodeLifecycleValue nlv) {
                lifecycleKv.put(nlk.nodeId(), nlv);
            }
        }

        private void applyRemove(KVCommand.Remove<AetherKey> remove) {
            if (remove.key() instanceof ProvisioningSlotKey psk) {slotKv.remove(psk);}
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        final List<NodeId> terminatedNodes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

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
                                                             Option.some("aether-test-node-" + count)).unwrap());
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
