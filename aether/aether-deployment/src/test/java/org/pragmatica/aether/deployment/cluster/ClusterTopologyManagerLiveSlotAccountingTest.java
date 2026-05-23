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
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
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


/// CTM auto-heal storm regression: deficit + surplus accounting must include live
/// provisioning slots written to the KV-Store.
///
/// Gap B — `reconcileActive` must not provision a top-up while a live slot in the
/// KV-Store is already covering the gap. A slot is "live" when its `deadlineMs` is in
/// the future (un-expired). Expired slots are correctly ignored, so a stalled wave
/// re-arms top-up dispatch.
///
/// Gap C — `handleSurplus` may legitimately terminate during `Reconciling` when there are
/// no live slots in flight (e.g., same-target overshoot where a previously expired-slot
/// node finally arrives ON_DUTY alongside its replacement). When live slots are in
/// flight, surplus termination defers until the wave completes.
///
/// All tests drive the production code path (`provisionNodes` → `writeProvisioningSlotAtom`).
/// They never install slot atoms manually — that would mask the integration between the
/// dispatch path and the KV mirror.
class ClusterTopologyManagerLiveSlotAccountingTest {
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
                                                              nodeId -> Option.none(),
                                                              clusterStore::slots,
                                                              clusterStore::apply,
                                                              new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                              LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                   nodeId -> Option.none(),
                                                                                                   System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    /// Gap B: when the KV-Store already holds a live (un-expired) provisioning slot, the
    /// deficit accounting must subtract it. Cluster at 4 healthy ON_DUTY with desired=5
    /// and one live slot in KV — no second dispatch may fire on the next reconcile tick.
    @Test
    void reconcileActive_deficitWithLiveSlots_doesNotTopUp() {
        // Long-lived slot: deadline is 60s out — un-expired throughout the test.
        var ctm = createCtm(timeSpan(60).seconds());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                               1L);
        ctm.activate();
        // Drop PEER_D so deficit = 1. Production dispatch writes one slot to KV.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        var firstWave = lifecycleManager.provisionCount.get();
        assertThat(firstWave).as("first wave dispatched exactly one provision for deficit=1").isEqualTo(1);
        assertThat(clusterStore.slots())
                .as("first wave wrote one live slot atom to KV via production path")
                .hasSize(1);
        // Next reconcile tick — snapshot still reports deficit, slot is still live in KV.
        // Without Gap B, this would top up; with Gap B, it must defer.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               3L);
        // Drive reconcile a few times via the safety-net-equivalent (membership events that
        // trigger reconcile()). Use a distinct peer that's NOT decommissioned (otherwise the
        // event mutates the topology).
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_B,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_C,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.provisionCount.get())
                .as("no top-up dispatched — KV live slot covers the deficit")
                .isEqualTo(firstWave);
    }

    /// Gap B: an expired slot must NOT cover deficit. The next reconcile tick must
    /// observe expiry (deadlineMs < nowMs) and dispatch a fresh wave.
    @Test
    void reconcileActive_deficitWithExpiredSlots_topsUp() throws InterruptedException {
        // Short-lived slot (50ms) — expires in well under the test window.
        var ctm = createCtm(timeSpan(50).millis());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                               1L);
        ctm.activate();
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        var firstWave = lifecycleManager.provisionCount.get();
        assertThat(firstWave).isEqualTo(1);
        assertThat(clusterStore.slots()).hasSize(1);
        // Wait beyond the provisioning timeout so the slot atom's deadlineMs has lapsed.
        Thread.sleep(200L);
        // Re-publish the deficit snapshot — slot is now expired in KV.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               3L);
        // Trigger another reconcile (slot expiry is observed via liveProvisioningSlotCount).
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.provisionCount.get())
                .as("expired KV slot does not cover deficit — production must top up")
                .isGreaterThan(firstWave);
    }

    /// Gap C narrow guard, positive case: same-target overshoot during `Reconciling`
    /// must legitimately terminate when no live slots are in flight. Sequence:
    /// drop one peer → deficit=1, wave dispatched → slot expires → late-arriving original
    /// AND replacement both healthy → actual=6, target=5, surplus=1. No live slots, so
    /// the narrowed guard lets termination proceed.
    @Test
    void handleSurplus_actualExceedsTargetDuringReconciling_noLiveSlots_terminates()
            throws InterruptedException {
        var ctm = createCtm(timeSpan(50).millis());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                               1L);
        ctm.activate();
        // Deficit → Reconciling. Slot written to KV via production path.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Reconciling.class);
        // Let the slot deadline lapse so liveProvisioningSlotCount() returns 0.
        Thread.sleep(200L);
        // Both the original PEER_D and a replacement come up — actual=6, target=5.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                6,
                                                5),
                               3L);
        var beforeTerminate = lifecycleManager.terminateCount.get();
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_D,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        // Surplus path must fire (state was Reconciling, but no live slots, no terminations
        // in flight — the narrowed Gap C guard lets us proceed).
        var deadline = System.currentTimeMillis() + 2000L;
        while (lifecycleManager.terminateCount.get() == beforeTerminate
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(lifecycleManager.terminateCount.get())
                .as("surplus terminated despite Reconciling state — no live slots block it")
                .isGreaterThan(beforeTerminate);
    }

    /// Gap C narrow guard, negative case: while a live slot is in flight, surplus
    /// termination must defer. Sequence: deficit dispatched → slot live in KV with state
    /// = Reconciling(target=5). Snapshot then reports actual=6 healthy ON_DUTY against
    /// the same target — surplus appears WITHIN the in-flight provisioning wave. CTM
    /// must wait: provisioning AND terminating against the same target would oscillate.
    @Test
    void handleSurplus_actualExceedsTargetDuringReconciling_withLiveSlots_waits() {
        // Long-lived slot (60s) — stays live throughout the test.
        var ctm = createCtm(timeSpan(60).seconds());
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                               1L);
        ctm.activate();
        // Deficit drives the production dispatch path; one slot is now live in KV.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                                4,
                                                5),
                               2L);
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(clusterStore.slots()).hasSize(1);
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Reconciling.class);
        var beforeTerminate = lifecycleManager.terminateCount.get();
        // Surplus appears WITHIN the in-flight wave: same target=5, but actual climbed to 6
        // (e.g. the original PEER_D arrived ON_DUTY before its replacement, and a spurious
        // extra node is reporting healthy). Live slot is still un-expired in KV.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                6,
                                                5),
                               3L);
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_D,
                                                                List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        // Gap C narrow guard: Reconciling + liveSlots>0 → defer surplus termination.
        assertThat(lifecycleManager.terminateCount.get())
                .as("surplus termination deferred while live slot remains in KV during Reconciling")
                .isEqualTo(beforeTerminate);
        assertThat(clusterStore.slots())
                .as("live slot atom remains in KV — wave has not been cancelled")
                .hasSize(1);
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
            // All on-duty peers are CTM-provisioned so surplus termination has eligible
            // candidates (the production filter excludes non-CTM-managed nodes).
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

    /// Shared KV-store backing for slots, cluster-config, and lifecycle atoms. Wires
    /// `slots()` (reader) to the same map that `apply(...)` (writer) populates — so the
    /// production code path `provisionNodes` → `writeProvisioningSlotAtom` → KV is
    /// observable via `slots()`, exactly as a real leader would see it after replicating
    /// the slot atom through consensus.
    private static final class RecordingClusterStore {
        final AtomicInteger slotPutCount = new AtomicInteger();
        final AtomicInteger slotRemoveCount = new AtomicInteger();
        private final AtomicReference<Option<ClusterConfigValue>> clusterConfig = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();

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
            if (put.key() instanceof ProvisioningSlotKey psk
                    && put.value() instanceof ProvisioningSlotValue psv) {
                slotPutCount.incrementAndGet();
                slotKv.put(psk, psv);
            } else if (put.key() instanceof AetherKey.ClusterConfigKey
                    && put.value() instanceof ClusterConfigValue cv) {
                clusterConfig.set(Option.some(cv));
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
        final List<NodeId> terminatedNodes = Collections.synchronizedList(new ArrayList<>());

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub")
                                                                                                    .unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            provisionCount.incrementAndGet();
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + provisionCount.get())
                                                                       .unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND).unwrap());
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
