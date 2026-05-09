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
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
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


/// Theme B Item 3 — restored from reverted Fix 5 commit (`316d2656a`) with
/// stability-window adjustments. Verifies that `ClusterTopologyManagerRecord` tracks per-slot
/// deadlines on its in-flight provisioning attempts and dispatches a top-up wave once a
/// stalled slot's deadline has passed.
///
/// The post-RC1 stability gate (`provisionStabilityWindow`) defers any deficit dispatch until
/// `realActual` has been stable for the configured window. These tests use a 1-millisecond
/// stability window so the gate becomes a no-op in tests. A separate suite
/// (`ClusterTopologyManagerStabilityWindowTest`) covers the gating behaviour itself.
class ClusterTopologyManagerProvisioningSlotTimeoutTest {
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

    /// Zero-millisecond stability window so the post-RC1 gate is unconditionally a no-op
    /// for slot-timeout tests (the gate evaluates `elapsed >= window`, so 0 == 0 always
    /// passes regardless of clock granularity).
    /// (`ClusterTopologyManagerStabilityWindowTest` covers gating itself.)
    private static final TimeSpan NEGLIGIBLE_STABILITY = timeSpan(0).millis();

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private StubClusterConfigStore configStore;

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
        configStore = new StubClusterConfigStore();
        configStore.seed(5);
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
                                                              configStore::current,
                                                              nodeId -> Option.none(),
                                                              java.util.Map::of,
                                                              configStore::apply,
                                                              new org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator(),
                                                              LegacyLifecycleWriterFixture.create(configStore::apply,
                                                                                                   nodeId -> Option.none(),
                                                                                                   System::currentTimeMillis),
                                                              () -> org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase.NORMAL);
    }

    /// Reconciling holds `ProvisioningSlot` records whose `deadlineMs` is `spawnedAtMs +
    /// provisioningTimeout`. When the wave is dispatched the in-flight list size matches the
    /// dispatched count and every slot's deadline is in the future.
    @Test
    void provisionDispatch_createsSlotsWithDeadline() {
        var timeout = timeSpan(30).seconds();
        var ctm = createCtm(timeout);
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
        var dispatchTimeMs = System.currentTimeMillis();
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Reconciling.class);
        var reconciling = (NodeReconcilerState.Reconciling) ctm.reconcilerState();
        assertThat(reconciling.inFlight()).hasSize(lifecycleManager.provisionCount.get());
        var minExpectedDeadline = dispatchTimeMs + timeout.millis() - 100L;
        assertThat(reconciling.inFlight()).allMatch(slot -> slot.deadlineMs() >= minExpectedDeadline);
    }

    /// Wave 1 dispatches; provision stalls (snapshot still reports deficit). After the slot
    /// deadline lapses the next reconcile expires the slot, recomputes deficit against the
    /// (now-empty) in-flight list, and dispatches a top-up.
    @Test
    void slotTimeout_freesSlotForRedispatch() throws InterruptedException {
        var ctm = createCtm(timeSpan(100).millis());
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
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        var firstWaveCount = lifecycleManager.provisionCount.get();
        assertThat(firstWaveCount).isGreaterThanOrEqualTo(1);
        Thread.sleep(200L);
        // Snapshot still reports the same deficit (provisions stalled).
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               3L);
        // Next reconcile tick expires the slots and dispatches a top-up wave.
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_D, List.of()));
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThan(firstWaveCount);
        var afterTopup = (NodeReconcilerState.Reconciling) ctm.reconcilerState();
        assertThat(afterTopup.inFlight()).isNotEmpty();
        var nowMs = System.currentTimeMillis();
        assertThat(afterTopup.inFlight()).allMatch(slot -> slot.deadlineMs() >= nowMs);
    }

    /// Both provisions succeed: snapshot reports actual=desired before the deadline. Next
    /// reconcile observes convergence and transitions to `Converged` without dispatching
    /// another wave.
    @Test
    void successfulProvision_decrementsSlots() {
        var ctm = createCtm(timeSpan(60).seconds());
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
        ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
        var firstWaveCount = lifecycleManager.provisionCount.get();
        assertThat(firstWaveCount).isGreaterThanOrEqualTo(1);
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               3L);
        ctm.onMembershipDecision(MembershipDecision.nodeJoined(PEER_C, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
        assertThat(lifecycleManager.provisionCount.get()).isEqualTo(firstWaveCount);
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
        final AtomicInteger applyCount = new AtomicInteger();
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());

        void seed(int coreCount) {
            current.set(Option.some(new ClusterConfigValue("",
                                                           "",
                                                           "1.0.0",
                                                           coreCount,
                                                           3,
                                                           9,
                                                           "test",
                                                           1L,
                                                           System.currentTimeMillis())));
        }

        Option<ClusterConfigValue> current() {
            return current.get();
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            applyCount.incrementAndGet();
            for (var command : commands) {
                if (command instanceof KVCommand.Put<?, ?> put
                    && put.key() instanceof AetherKey.ClusterConfigKey
                    && put.value() instanceof ClusterConfigValue configValue) {
                    current.set(Option.some(configValue));
                }
            }
            return Promise.success(List.of());
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
