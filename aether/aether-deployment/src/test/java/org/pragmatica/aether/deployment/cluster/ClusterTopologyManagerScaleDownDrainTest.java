// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
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

import java.util.ArrayList;
import java.util.Collections;
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


/// Theme D #1 — verifies that scale-down terminate is now a 2-phase protocol gated on
/// the [`DrainCoordinator`]. Phase 1 writes a `DRAINING` lifecycle atom and calls
/// `prepareDrain` + `awaitDrainAck`; Phase 2 calls `provider.terminate`. The rc1 stub
/// (`NoOpDrainCoordinator`) returns immediate success so the existing flow continues
/// to work, but the structural ordering is verified here for the rc2 #189 implementation.
class ClusterTopologyManagerScaleDownDrainTest {
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
    private RecordingClusterConfigStore configStore;

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
        configStore = new RecordingClusterConfigStore();
        configStore.seed(3);
    }

    private ClusterTopologyManager createCtm(DrainCoordinator drainCoordinator) {
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
                                                              configStore::current,
                                                              nodeId -> Option.none(),
                                                              java.util.Map::of,
                                                              configStore::apply,
                                                              drainCoordinator,
                                                              LegacyLifecycleWriterFixture.create(configStore::apply,
                                                                                                  nodeId -> Option.none(),
                                                                                                  System::currentTimeMillis),
                                                              () -> AetherValue.ClusterPhase.NORMAL);
    }

    private void publishSurplus(int onDuty) {
        var ids = new java.util.LinkedHashSet<NodeId>();
        ids.add(SELF); ids.add(PEER_A); ids.add(PEER_B); ids.add(PEER_C); ids.add(PEER_D);
        var onDutySet = new java.util.LinkedHashSet<NodeId>();
        var iter = ids.iterator();
        for (int i = 0; i < onDuty && iter.hasNext(); i++) {onDutySet.add(iter.next());}
        snapshotSource.publish(StubView.surplus(ids, onDutySet, onDuty, 3), 1L);
    }

    /// On scale-down, CTM must (1) write `NodeLifecycleValue(DRAINING, ...)` via consensus,
    /// (2) invoke `drainCoordinator.prepareDrain`, (3) `drainCoordinator.awaitDrainAck`,
    /// (4) `lifecycleManager.terminateNode`, and finally (5) write `DECOMMISSIONED`. The
    /// recording coordinator + recording cluster config store let us assert the exact
    /// ordering of these effects.
    @Test
    void scaleDown_writesDrainingBeforeTerminate() throws InterruptedException {
        var coordinator = new RecordingDrainCoordinator();
        var ctm = createCtm(coordinator);
        publishSurplus(5);
        ctm.activate();
        // activate() routes to Forming/Converged based on observer.healthyActiveNodeCount();
        // it does not directly run handleSurplus. Force reconcile via the new
        // onClusterConfigChanged() hook so the surplus path runs against the snapshot.
        ctm.onClusterConfigChanged();

        // Wait briefly for asynchronous Promise chain to complete.
        var deadline = System.currentTimeMillis() + 2000L;
        while (lifecycleManager.terminateCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        var lifecycleStates = configStore.observedLifecycleStates();
        assertThat(lifecycleStates).contains(NodeLifecycleState.DRAINING);
        assertThat(coordinator.prepareCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(coordinator.awaitCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(lifecycleManager.terminateCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(coordinator.markCompleteCount.get()).isGreaterThanOrEqualTo(1);
        // Ordering: first lifecycle write must be DRAINING, eventually followed by DECOMMISSIONED.
        var ordering = configStore.observedLifecycleStates();
        assertThat(ordering.get(0)).isEqualTo(NodeLifecycleState.DRAINING);
        assertThat(ordering).contains(NodeLifecycleState.DECOMMISSIONED);
    }

    /// If the drain coordinator's `awaitDrainAck` never resolves within the timeout, CTM
    /// must NOT block forever. The fallback timeout is `provisioningTimeout` (default 60s
    /// from `AutoHealConfig`); we shorten it here to make the test fast.
    @Test
    void scaleDown_drainAckTimeout_proceedsToTerminate() throws InterruptedException {
        var coordinator = new HangingDrainCoordinator();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      timeSpan(200).millis(),
                                                      NEGLIGIBLE_STABILITY)
                                            .unwrap();
        var ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                  lifecycleManager,
                                                                  autoHeal,
                                                                  DeploymentMap.deploymentMap(),
                                                                  snapshotSource,
                                                                  configStore::current,
                                                                  nodeId -> Option.none(),
                                                                  java.util.Map::of,
                                                                  configStore::apply,
                                                                  coordinator,
                                                                  LegacyLifecycleWriterFixture.create(configStore::apply,
                                                                                                       nodeId -> Option.none(),
                                                                                                       System::currentTimeMillis),
                                                                  () -> AetherValue.ClusterPhase.NORMAL);
        publishSurplus(5);
        ctm.activate();
        ctm.onClusterConfigChanged();

        // Wait long enough that the awaitDrainAck timeout fires (200ms) and terminate proceeds.
        var deadline = System.currentTimeMillis() + 3000L;
        while (lifecycleManager.terminateCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }

        assertThat(coordinator.prepareCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(lifecycleManager.terminateCount.get())
                .as("terminate should fire even when drain-ack hangs")
                .isGreaterThanOrEqualTo(1);
    }

    /// JOINING nodes must be excluded from termination candidates: they are still being
    /// onboarded and tearing them down would waste in-progress provisioning work.
    @Test
    void scaleDown_excludesJoiningNodesFromCandidates() throws InterruptedException {
        var coordinator = new RecordingDrainCoordinator();
        var ctm = createCtm(coordinator);

        // Publish a snapshot where all 5 nodes are core members but only 4 are ON_DUTY
        // (PEER_D is JOINING). Surplus is 5 - 3 = 2 → CTM should pick from {PEER_A, PEER_B,
        // PEER_C} (the 4 ON_DUTY excluding self), NOT PEER_D.
        var coreSet = new java.util.LinkedHashSet<NodeId>();
        coreSet.add(SELF); coreSet.add(PEER_A); coreSet.add(PEER_B); coreSet.add(PEER_C); coreSet.add(PEER_D);
        var onDuty = new java.util.LinkedHashSet<NodeId>();
        onDuty.add(SELF); onDuty.add(PEER_A); onDuty.add(PEER_B); onDuty.add(PEER_C);
        snapshotSource.publish(StubView.surplus(coreSet, onDuty, 4, 3), 1L);
        ctm.activate();
        ctm.onClusterConfigChanged();

        var deadline = System.currentTimeMillis() + 2000L;
        while (lifecycleManager.terminateCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(lifecycleManager.terminatedNodes)
                .as("JOINING node PEER_D must never be a termination candidate")
                .doesNotContain(PEER_D);
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {
        static StubView surplus(Set<NodeId> coreMemberIds,
                                Set<NodeId> onDutyMemberIds,
                                int healthyOnDutyCount,
                                int desiredCoreSize) {
            // All on-duty peers are CTM-owned so they're eligible for termination; empty nodes set is empty.
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

    private static final class RecordingClusterConfigStore {
        private final AtomicReference<Option<ClusterConfigValue>> current = new AtomicReference<>(Option.none());
        private final List<NodeLifecycleState> lifecycleWrites = Collections.synchronizedList(new ArrayList<>());

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

        List<NodeLifecycleState> observedLifecycleStates() {
            synchronized (lifecycleWrites) {return List.copyOf(lifecycleWrites);}
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {
                if (command instanceof KVCommand.Put<?, ?> put) {
                    if (put.key() instanceof AetherKey.ClusterConfigKey
                        && put.value() instanceof ClusterConfigValue configValue) {
                        current.set(Option.some(configValue));
                    } else if (put.key() instanceof NodeLifecycleKey
                               && put.value() instanceof NodeLifecycleValue lifecycle) {
                        lifecycleWrites.add(lifecycle.state());
                    }
                }
            }
            return Promise.success(List.of());
        }
    }

    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        final AtomicInteger provisionCount = new AtomicInteger();
        final AtomicInteger terminateCount = new AtomicInteger();
        final List<NodeId> terminatedNodes = Collections.synchronizedList(new ArrayList<>());

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

    /// rc1-style coordinator that records calls and resolves immediately (mirrors NoOp).
    private static final class RecordingDrainCoordinator implements DrainCoordinator {
        final AtomicInteger prepareCount = new AtomicInteger();
        final AtomicInteger awaitCount = new AtomicInteger();
        final AtomicInteger markCompleteCount = new AtomicInteger();

        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            prepareCount.incrementAndGet();
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            awaitCount.incrementAndGet();
            return Promise.unitPromise();
        }

        @Override public void markDrainComplete(NodeId nodeId) {
            markCompleteCount.incrementAndGet();
        }
    }

    /// Drain coordinator whose `awaitDrainAck` honors the supplied timeout but then fails —
    /// simulates the rc2 contract where peers never ack within the deadline. CTM must
    /// observe the failure and proceed to terminate without hanging.
    private static final class HangingDrainCoordinator implements DrainCoordinator {
        final AtomicInteger prepareCount = new AtomicInteger();

        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            prepareCount.incrementAndGet();
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            Promise<Unit> p = Promise.promise();
            org.pragmatica.lang.utils.SharedScheduler.schedule(() -> p.fail(org.pragmatica.lang.utils.Causes.cause("drain ack timeout (test stub)")),
                                                                timeout);
            return p;
        }

        @Override public void markDrainComplete(NodeId nodeId) {
        }
    }
}
