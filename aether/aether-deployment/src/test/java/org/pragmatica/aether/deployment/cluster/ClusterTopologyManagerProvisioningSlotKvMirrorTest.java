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
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Fix D — KV-mirrored CTM `inFlightProvisions` slots so they survive leader handoff.
///
/// Verifies the structural invariant that a leader-1 → leader-2 handoff during an in-flight
/// provisioning wave does NOT cause leader-2 to dispatch a duplicate wave. The slot atoms are
/// also expected to be cleaned up on completion (assigned node reaches ON_DUTY) and on
/// expiry (deadline passed).
///
/// Critical regression test: `coldBoot_doesNotPhantomProvision` reproduces the prior
/// "Fix 5" failure scenario (cluster boots with N of M static nodes, leader sees deficit, must
/// NOT dispatch within bootstrap-grace + stability window).
class ClusterTopologyManagerProvisioningSlotKvMirrorTest {
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

    /// 0ms stability window so dispatch decisions in non-cold-boot tests are immediate.
    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();

    /// 1-second stability window for the cold-boot regression test (kept short so the wait
    /// completes quickly while still being meaningfully greater than zero).
    private static final TimeSpan COLD_BOOT_STABILITY = timeSpan(1).seconds();

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

    private ClusterTopologyManager createCtm(TimeSpan stabilityWindow) {
        return createCtm(stabilityWindow, timeSpan(60).seconds());
    }

    private ClusterTopologyManager createCtm(TimeSpan stabilityWindow, TimeSpan provisioningTimeout) {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      provisioningTimeout,
                                                      stabilityWindow)
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
                                                              new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator());
    }

    private ClusterTopologyManager createCtm() {
        return createCtm(NEGLIGIBLE_STABILITY);
    }

    @Nested
    class HappyPath {
        @Test
        void provisionNodes_writesSlotToKV_onLeaderSide() {
            var ctm = createCtm();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                                   1L);
            ctm.activate();
            // Drop two peers — leader provisions replacements.
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B),
                                                3,
                                                5),
                                   2L);
            ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_C));
            var dispatched = lifecycleManager.provisionCount.get();
            assertThat(dispatched).as("at least one provision dispatched").isGreaterThanOrEqualTo(1);
            assertThat(clusterStore.slotPutCount.get())
                    .as("each provision creates one ProvisioningSlot KV atom")
                    .isGreaterThanOrEqualTo(dispatched);
            assertThat(clusterStore.slots())
                    .as("KV-mirrored slots match dispatched provisions")
                    .hasSize(dispatched);
        }

        @Test
        void slotExpiry_deletesKVAtom() throws InterruptedException {
            // Use a short provisioning timeout so the in-memory Reconciling slot deadlines
            // lapse within the test window. After the deadline passes, the next reconcile tick
            // (driven by another topology event) calls `expireSlots`, which drops the slots
            // from the in-memory list AND issues KV `Remove` commands for the expired atoms.
            var ctm = createCtm(NEGLIGIBLE_STABILITY, timeSpan(50).millis());
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                                   1L);
            ctm.activate();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B),
                                                3,
                                                5),
                                   2L);
            ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_C));
            var slotsAfterDispatch = clusterStore.slots().size();
            assertThat(slotsAfterDispatch).isGreaterThanOrEqualTo(1);
            // Wait beyond the provisioning timeout so deadlines lapse.
            Thread.sleep(150L);
            // Snapshot still reports the same deficit (provisions stalled).
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B),
                                                3,
                                                5),
                                   3L);
            // Trigger another reconcile so `expireSlots` runs.
            ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_D));
            assertThat(clusterStore.slotRemoveCount.get())
                    .as("expired slots produce KV Remove commands")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        void slotCompletion_onAssignedNodeOnDuty_deletesKVAtom() {
            var ctm = createCtm();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                                   1L);
            ctm.activate();
            // Manually install a slot that was dispatched by a (notional) prior wave with
            // PEER_C as the assigned node.
            var nowMs = System.currentTimeMillis();
            var slotKey = ProvisioningSlotKey.provisioningSlotKey("manual-c");
            clusterStore.installSlot(slotKey, ProvisioningSlotValue.provisioningSlotValue(nowMs, nowMs + 60_000L, PEER_C));
            // Mirror PEER_C arriving ON_DUTY in lifecycle KV.
            clusterStore.installLifecycle(PEER_C,
                                          NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                                  "host-c",
                                                                                  5003));
            ctm.onNodeReady(PEER_C);
            assertThat(clusterStore.slotRemoveCount.get())
                    .as("completed slot for ON_DUTY node is removed from KV")
                    .isGreaterThanOrEqualTo(1);
            assertThat(clusterStore.slots()).as("KV slot atom is gone").isEmpty();
        }
    }

    @Nested
    class LeaderHandoff {
        @Test
        void leaderHandoff_midProvision_newLeaderRehydratesSlots() {
            var leaderOne = createCtm();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                                   1L);
            leaderOne.activate();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B),
                                                3,
                                                5),
                                   2L);
            leaderOne.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_C));
            var slotsLeftByLeader1 = clusterStore.slots().size();
            assertThat(slotsLeftByLeader1).as("leader-1 created KV slots").isGreaterThanOrEqualTo(1);
            // Leader-1 hands off; deactivate so its safety-net timer cannot fire spurious
            // provisions while leader-2 takes over.
            leaderOne.deactivate();
            // Reset CTM in-memory counters by creating a fresh CTM instance against the same
            // `clusterStore`.
            lifecycleManager.provisionCount.set(0);
            var leaderTwo = createCtm();
            leaderTwo.activate();
            assertThat(leaderTwo.reconcilerState())
                    .as("leader-2 enters Reconciling because KV slots indicate in-flight wave")
                    .isInstanceOf(NodeReconcilerState.Reconciling.class);
            var rehydrated = (NodeReconcilerState.Reconciling) leaderTwo.reconcilerState();
            assertThat(rehydrated.inFlight())
                    .as("leader-2 in-flight slot count matches what leader-1 left in KV")
                    .hasSize(slotsLeftByLeader1);
            // Crucial dedup property: leader-2 did NOT dispatch a fresh wave despite seeing the
            // same deficit as leader-1.
            assertThat(lifecycleManager.provisionCount.get())
                    .as("leader-2 must NOT double-dispatch — slots from KV cover the deficit")
                    .isZero();
        }

        @Test
        void leaderHandoff_completedSlotsCleanedUp() {
            var leaderOne = createCtm();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                                   1L);
            leaderOne.activate();
            // Manually install a slot whose assigned node already reached ON_DUTY.
            var nowMs = System.currentTimeMillis();
            var slotKey = ProvisioningSlotKey.provisioningSlotKey("done-a");
            clusterStore.installSlot(slotKey, ProvisioningSlotValue.provisioningSlotValue(nowMs, nowMs + 60_000L, PEER_A));
            clusterStore.installLifecycle(PEER_A,
                                          NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                                  "host-a",
                                                                                  5001));
            leaderOne.deactivate();
            // Leader-2 takes over.
            var leaderTwo = createCtm();
            leaderTwo.activate();
            assertThat(clusterStore.slots())
                    .as("slot for already-ON_DUTY node is auto-cleaned on rehydrate")
                    .doesNotContainKey(slotKey);
        }
    }

    @Nested
    class ColdBootRegression {
        /// CRITICAL regression-prevention test for the prior Fix 5 incident. Cluster A starts
        /// with 3 of 5 static nodes ready and configured=5. Leader elects, snapshot reports
        /// `realActual=3, configured=5`. Within the bootstrap-grace + stability window, NO
        /// `ProvisioningSlot` atom must be written and NO `provisionNodes(...)` call must
        /// fire. After the remaining 2 nodes join, the cluster converges to 5/5 with zero
        /// provisioning ever having been dispatched.
        @Test
        void coldBoot_doesNotPhantomProvision() throws InterruptedException {
            var ctm = createCtm(COLD_BOOT_STABILITY);
            // 3 of 5 nodes ON_DUTY at activation — same shape as the historic Fix-5 failure.
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B),
                                                3,
                                                5),
                                   1L);
            ctm.activate();
            // Sleep through the Forming cooldown (1ms) plus enough time that the safety-net poll
            // fires repeatedly while the deficit is visible.
            Thread.sleep(500L);
            // Within the stability window — no provisioning must have fired and no KV slot atom
            // can have been written.
            assertThat(lifecycleManager.provisionCount.get())
                    .as("no provisionNodes(...) within bootstrap stability window")
                    .isZero();
            assertThat(clusterStore.slotPutCount.get())
                    .as("no ProvisioningSlot atom written within stability window")
                    .isZero();
            // Remaining 2 nodes finish booting before the stability window matures.
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                5,
                                                5),
                                   2L);
            ctm.onTopologyChange(TopologyChangeNotification.nodeAdded(PEER_C, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
            ctm.onTopologyChange(TopologyChangeNotification.nodeAdded(PEER_D, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
            // Wait beyond the stability window so any pending dispatch would have fired by now.
            Thread.sleep(COLD_BOOT_STABILITY.millis() + 200L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("cluster converged 5/5 without ever dispatching provisioning")
                    .isZero();
            assertThat(clusterStore.slotPutCount.get())
                    .as("no slot atom ever written in cold-boot scenario")
                    .isZero();
            assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
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
            return new StubView(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, coreMemberIds, Set.of());
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

    /// Records every `KVCommand` flowing through the leader-side write path AND maintains a
    /// shared in-memory KV view that cross-CTM tests can inspect (used to simulate leader
    /// handoff without a real consensus implementation).
    private static final class RecordingClusterStore {
        final AtomicInteger slotPutCount = new AtomicInteger();
        final AtomicInteger slotRemoveCount = new AtomicInteger();
        private final AtomicReference<Option<ClusterConfigValue>> clusterConfig = new AtomicReference<>(Option.none());
        private final ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new ConcurrentHashMap<>();

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
            var value = lifecycleKv.get(nodeId);
            return value == null
                  ? Option.none()
                  : Option.some(value);
        }

        Map<ProvisioningSlotKey, ProvisioningSlotValue> slots() {
            return new LinkedHashMap<>(slotKv);
        }

        void installSlot(ProvisioningSlotKey key, ProvisioningSlotValue value) {
            slotKv.put(key, value);
        }

        void installLifecycle(NodeId nodeId, NodeLifecycleValue value) {
            lifecycleKv.put(nodeId, value);
        }

        void replaceAllSlotsWithLapsed() {
            var nowMs = System.currentTimeMillis();
            var lapsed = new LinkedHashMap<ProvisioningSlotKey, ProvisioningSlotValue>();
            slotKv.forEach((key, _) -> lapsed.put(key, ProvisioningSlotValue.provisioningSlotValue(nowMs - 10_000L, nowMs - 1L)));
            slotKv.clear();
            slotKv.putAll(lapsed);
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {applyOne(command);}
            return Promise.success(List.of());
        }

        private void applyOne(KVCommand<AetherKey> command) {
            switch (command){
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
            } else if (put.key() instanceof NodeLifecycleKey nlk
                    && put.value() instanceof NodeLifecycleValue nlv) {
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

        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                          InstanceStatus.RUNNING,
                                                                                          List.of("127.0.0.1"),
                                                                                          InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            provisionCount.incrementAndGet();
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub-" + provisionCount.get()).unwrap(),
                                                             InstanceStatus.RUNNING,
                                                             List.of("127.0.0.1"),
                                                             InstanceType.ON_DEMAND).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            terminateCount.incrementAndGet();
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
