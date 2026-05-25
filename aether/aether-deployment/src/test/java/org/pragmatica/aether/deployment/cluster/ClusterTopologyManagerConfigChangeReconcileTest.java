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
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// Theme D #3 — verifies that `ClusterTopologyManager.onClusterConfigChanged()` triggers an
/// immediate reconcile so `setDesiredSize()` is acted upon without waiting for the safety-net
/// poll (`AutoHealConfig.retryInterval`, default 10s). Production wiring: `AetherNode`
/// registers an `onPut(ClusterConfigKey)` handler in the `KVNotificationRouter` that calls
/// this hook directly.
class ClusterTopologyManagerConfigChangeReconcileTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());

    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();

    /// Long retry interval so the safety-net poll never fires during the test — any
    /// dispatched provision must be attributable to the immediate reconcile triggered
    /// by `onClusterConfigChanged()`.
    private static final TimeSpan LONG_RETRY = timeSpan(60).seconds();

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private StubClusterConfigStore configStore;

    @BeforeEach
    void setUp() {
        snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        3,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B));
        observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new RecordingLifecycleManager();
        configStore = new StubClusterConfigStore();
        configStore.seed(3);
    }

    private ClusterTopologyManager createCtm() {
        var autoHeal = AutoHealConfig.autoHealConfig(LONG_RETRY,
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
                                                              configStore::current,
                                                              configStore::lifecycle,
                                                              configStore::slots,
                                                              configStore::apply,
                                                              new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                              LegacyLifecycleWriterFixture.create(configStore::apply,
                                                                                                   configStore::lifecycle,
                                                                                                   System::currentTimeMillis),
                                                              () -> org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase.NORMAL);
    }

    /// Cluster is converged at 3 nodes / desired=3. Operator scales to desired=5 by writing
    /// a new ClusterConfigValue. Without the new hook, CTM would not see the change until
    /// the next safety-net poll (60s in this test). With the hook, reconcile fires
    /// immediately and provisioning is dispatched synchronously.
    @Test
    void setDesiredSize_triggersImmediateReconcile() throws InterruptedException {
        var ctm = createCtm();
        // Snapshot reflects converged 3-of-3 cluster; occupants ON_DUTY in lifecycle KV.
        configStore.seed(3);
        configStore.installOnDuty(SELF, 0L);
        configStore.installOnDuty(PEER_A, 1L);
        configStore.installOnDuty(PEER_B, 2L);
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B),
                                                   Set.of(SELF, PEER_A, PEER_B),
                                                   3,
                                                   3),
                               1L);
        ctm.activate();
        Thread.sleep(20L);

        // Operator scales to 5 — new ClusterConfigValue + new snapshot reporting desired=5.
        configStore.seed(5);
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B),
                                                   Set.of(SELF, PEER_A, PEER_B),
                                                   3,
                                                   5),
                               2L);
        // Production: KVNotificationRouter dispatches `onClusterConfigChanged` after the
        // `Put<ClusterConfigKey>` commits; invoke directly to verify the synchronous reconcile.
        var beforeProvisions = lifecycleManager.provisionCount.get();
        ctm.onClusterConfigChanged();

        // Scale-up adds 2 EMPTY slots (3→5) which the reconcile loop fills immediately.
        var deadline = System.currentTimeMillis() + 2000L;

        while (lifecycleManager.provisionCount.get() == beforeProvisions && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        assertThat(lifecycleManager.provisionCount.get())
                .as("immediate reconcile fills the new EMPTY slots without waiting for safety-net poll")
                .isGreaterThan(beforeProvisions);
    }

    /// Inactive CTM (not leader) must ignore config-change notifications.
    @Test
    void inactiveCtm_ignoresClusterConfigChanged() {
        var ctm = createCtm();
        // Activate not called — CTM is in Inactive state.
        ctm.onClusterConfigChanged();
        assertThat(lifecycleManager.provisionCount.get()).isZero();
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

    private static final class StubClusterConfigStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());
        private final java.util.concurrent.ConcurrentHashMap<ProvisioningSlotKey, ProvisioningSlotValue> slotKv = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<NodeId, NodeLifecycleValue> lifecycleKv = new java.util.concurrent.ConcurrentHashMap<>();

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("",
                                                           "",
                                                           "1.0.0",
                                                           coreCount,
                                                           3,
                                                           9,
                                                           "test",
                                                           current.get().map(ClusterConfigValue::configVersion).or(0L) + 1L,
                                                           System.currentTimeMillis())));
        }

        void installOnDuty(NodeId nodeId, long epoch) {
            lifecycleKv.put(nodeId, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                                      "host-" + nodeId.id(),
                                                                                      5000,
                                                                                      Epoch.epoch(0L, epoch)));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        Option<NodeLifecycleValue> lifecycle(NodeId nodeId) {
            return Option.option(lifecycleKv.get(nodeId));
        }

        java.util.Map<ProvisioningSlotKey, ProvisioningSlotValue> slots() {
            return new java.util.LinkedHashMap<>(slotKv);
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {
                switch (command) {
                    case KVCommand.Put<AetherKey, ?> put -> applyPut(put);
                    case KVCommand.Remove<AetherKey> remove -> {
                        if (remove.key() instanceof ProvisioningSlotKey psk) {slotKv.remove(psk);}
                    }
                    default -> {}
                }
            }
            return Promise.success(List.of());
        }

        private void applyPut(KVCommand.Put<AetherKey, ?> put) {
            if (put.key() instanceof ProvisioningSlotKey psk && put.value() instanceof ProvisioningSlotValue psv) {
                slotKv.put(psk, psv);
            } else if (put.key() instanceof AetherKey.ClusterConfigKey && put.value() instanceof ClusterConfigValue configValue) {
                current.set(Option.some(configValue));
            } else if (put.key() instanceof NodeLifecycleKey nlk && put.value() instanceof NodeLifecycleValue nlv) {
                lifecycleKv.put(nlk.nodeId(), nlv);
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
