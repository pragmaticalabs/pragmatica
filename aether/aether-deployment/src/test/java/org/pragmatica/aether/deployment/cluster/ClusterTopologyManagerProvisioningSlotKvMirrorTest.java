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
                                                              new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                              LegacyLifecycleWriterFixture.create(clusterStore::apply,
                                                                                                   clusterStore::lifecycle,
                                                                                                   System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    private ClusterTopologyManager createCtm() {
        return createCtm(NEGLIGIBLE_STABILITY);
    }

    private void publishOnDuty(Set<NodeId> onDuty) {
        var all = Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        snapshotSource.publish(StubView.stubView(all, onDuty, onDuty.size(), 5), snapshotSource.observedRabiaTerm() + 1L);
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
    class HappyPath {
        /// A reseeded leader seeds exactly clusterSize stable-index slots; a DEAD slot (occupant
        /// STOPPED) is freed and refilled.
        @Test
        void deadSlot_freed_andRefilled() throws InterruptedException {
            var ctm = createCtm();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
            ctm.activate();
            assertThat(clusterStore.slots()).as("durable slot set seeded to clusterSize").hasSize(5);
            clusterStore.installStopped(PEER_C);
            clusterStore.installStopped(PEER_D);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B));
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
            awaitProvision(1);
            assertThat(lifecycleManager.provisionCount.get()).isGreaterThanOrEqualTo(1);
            assertThat(clusterStore.slots())
                    .as("durable slot set stays sized to clusterSize across free+refill")
                    .hasSize(5);
        }

        /// Durable slots (D1): a stalled FILLING marker past its deadline resets to EMPTY (slot
        /// persists) rather than being deleted.
        @Test
        void stalledFillingMarker_resetsToEmpty_slotPersists() throws InterruptedException {
            var ctm = createCtm(NEGLIGIBLE_STABILITY, timeSpan(50).millis());
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
            ctm.activate();
            clusterStore.installStopped(PEER_D);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C));
            ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
            awaitProvision(1);
            Thread.sleep(150L);
            ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
            assertThat(clusterStore.slots())
                    .as("slot set persists at clusterSize — slots reset to EMPTY, never deleted")
                    .hasSize(5);
        }

        /// Durable slots: the slot is NOT deleted when its occupant reaches ON_DUTY.
        @Test
        void slotNotDeleted_whenOccupantReachesOnDuty() {
            var ctm = createCtm();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
            ctm.activate();
            var sizeAfterActivate = clusterStore.slots().size();
            clusterStore.installOnDuty(PEER_C, 9L);
            ctm.onNodeReady(PEER_C);
            assertThat(clusterStore.slots())
                    .as("durable slot NOT deleted on ON_DUTY arrival")
                    .hasSize(sizeAfterActivate);
        }
    }

    @Nested
    class LeaderHandoff {
        /// Create-once / preserve (slot-based-core-membership-redesign §2): leader-1 first-forms the
        /// stable slot set (KV empty); leader-2 finds slots already present and PRESERVES the
        /// existing bindings — no wipe, no rebind, no duplicate provision wave for serving occupants.
        @Test
        void leaderHandoff_preservesStableSlots_noDuplicateWave() {
            var leaderOne = createCtm();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
            leaderOne.activate();
            leaderOne.deactivate();
            lifecycleManager.provisionCount.set(0);
            var leaderTwo = createCtm();
            leaderTwo.activate();
            assertThat(clusterStore.slots()).as("slot set preserved at clusterSize stable slots").hasSize(5);
            assertThat(clusterStore.slots().keySet())
                    .as("the preserved bindings keep their stable integer indices")
                    .allMatch(key -> key.slotId().matches("\\d+"));
            assertThat(lifecycleManager.provisionCount.get())
                    .as("leader-2 preserves existing bound occupants — no duplicate wave")
                    .isZero();
        }

        /// First formation binds the present occupants; the binding then PERSISTS across the leader
        /// change (create-once / preserve — no rebind on leader-2 activation).
        @Test
        void leaderHandoff_preservesOccupantBindings() {
            var leaderOne = createCtm();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
            leaderOne.activate();
            leaderOne.deactivate();
            var leaderTwo = createCtm();
            leaderTwo.activate();
            var boundOccupants = clusterStore.slots()
                                             .values()
                                             .stream()
                                             .map(ProvisioningSlotValue::assignedNodeId)
                                             .filter(Option::isPresent)
                                             .map(Option::unwrap)
                                             .toList();
            assertThat(boundOccupants)
                    .as("all 5 occupant bindings persist across the leader change")
                    .containsExactlyInAnyOrder(SELF, PEER_A, PEER_B, PEER_C, PEER_D);
        }
    }

    @Nested
    class ColdBootRegression {
        /// CRITICAL Fix-5 regression: a below-target cluster within the bootstrap-grace + stability
        /// window must NOT dispatch real provisioning. Seeding EMPTY slots is fine — the invariant
        /// is zero provisioning + convergence once the remaining static nodes join.
        @Test
        void coldBoot_doesNotPhantomProvision() throws InterruptedException {
            var ctm = createCtm(COLD_BOOT_STABILITY);
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B));
            ctm.activate();
            Thread.sleep(500L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("no provisionNodes(...) within bootstrap stability window")
                    .isZero();
            var occupantsAssigned = clusterStore.slots()
                                                .values()
                                                .stream()
                                                .anyMatch(slot -> slot.assignedNodeId().map(id -> id.id().startsWith("stub")).or(false));
            assertThat(occupantsAssigned)
                    .as("no provider-allocated occupant assigned within stability window")
                    .isFalse();
            publishOnDuty(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
            ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_C, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
            ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_D, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
            Thread.sleep(COLD_BOOT_STABILITY.millis() + 200L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("cluster converged 5/5 without ever dispatching provisioning")
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

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                          "host-" + nodeId.id(),
                                                                          5000,
                                                                          org.pragmatica.aether.slice.generation.Epoch.epoch(0L, epoch)));
        }

        void installStopped(NodeId nodeId) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, "host-" + nodeId.id(), 5000));
        }

        void replaceAllSlotsWithLapsed() {
            // spawnedAt well past the default 60s provisioningTimeout so the derived expiry
            // (spawnedAt + provisioningTimeout) is lapsed regardless of timeout (#230 remodel).
            var spawnedLongAgo = System.currentTimeMillis() - 600_000L;
            var lapsed = new LinkedHashMap<ProvisioningSlotKey, ProvisioningSlotValue>();
            slotKv.forEach((key, _) -> lapsed.put(key, ProvisioningSlotValue.provisioningSlotValue(spawnedLongAgo)));
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
