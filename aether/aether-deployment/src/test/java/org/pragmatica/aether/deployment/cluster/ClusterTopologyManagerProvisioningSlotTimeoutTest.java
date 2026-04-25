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


/// Verifies that `ClusterTopologyManagerRecord` tracks per-slot deadlines on its in-flight
/// provisioning attempts and dispatches a top-up wave once a stalled slot's deadline has
/// passed. Without this behaviour the Reconciling state would lock the reconcile loop until
/// every member of the original wave reached HEALTHY/ON_DUTY — a single stalled provision
/// would prevent any further dispatch.
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
                                                      provisioningTimeout)
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                              lifecycleManager,
                                                              autoHeal,
                                                              DeploymentMap.deploymentMap(),
                                                              snapshotSource,
                                                              configStore::current,
                                                              configStore::apply);
    }

    /// Wave 1 dispatches 2 provisions (one of which stalls — neither node joins the snapshot).
    /// After the slots time out, the next reconcile tick observes deficit=2, inFlight=0, and
    /// dispatches a top-up wave. Total provisioning calls must exceed the initial batch.
    @Test
    void partialWaveStall_topUpDispatchedAfterTimeout() throws InterruptedException {
        var ctm = createCtm(timeSpan(100).millis());
        // Activate in a converged state to bypass Forming cooldown
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
        // Snapshot reports deficit (2 nodes faulty)
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        // Trigger reconciliation; first wave dispatches 2 provisions
        ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_C));
        var initialProvisions = lifecycleManager.provisionCount.get();
        assertThat(initialProvisions).isGreaterThanOrEqualTo(1);
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Reconciling.class);
        // Wait for slot deadlines to lapse
        Thread.sleep(200L);
        // Snapshot still reports the same deficit (provisions stalled — no node joined)
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               3L);
        // Next reconcile tick — slots expire and top-up dispatches
        ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_D));
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThan(initialProvisions);
    }

    /// Both provisioned nodes reach HEALTHY/ON_DUTY before the slot deadline expires.
    /// Snapshot reports actual=desired so the next reconcile tick transitions to Converged
    /// without dispatching another wave.
    @Test
    void bothProvisionsSucceed_noTopUp() {
        var ctm = createCtm(timeSpan(60).seconds());
        // Activate in a converged state to bypass Forming cooldown
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        // Snapshot reports a deficit (2 nodes faulty)
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_C));
        var firstWaveCount = lifecycleManager.provisionCount.get();
        assertThat(firstWaveCount).isGreaterThanOrEqualTo(1);
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Reconciling.class);
        // Snapshot now reports the cluster reached desired size (provisions succeeded)
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               3L);
        // Next reconcile tick should observe convergence — no further provisioning
        ctm.onTopologyChange(TopologyChangeNotification.nodeAdded(PEER_C, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
        assertThat(lifecycleManager.provisionCount.get()).isEqualTo(firstWaveCount);
    }

    /// Single provision dispatched, stalls, slot expires. Verifies the in-flight slot list
    /// is freed (size==0) and the deficit recomputation triggers another provision dispatch.
    @Test
    void expiredSlotFreesUp() throws InterruptedException {
        var ctm = createCtm(timeSpan(100).millis());
        // Activate in converged (gap=1 also takes leader-failover path, but be explicit
        // so the test doesn't depend on activation heuristics)
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        // Single-node deficit (4 of 5)
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                            4,
                                            5),
                               2L);
        ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_D));
        var firstWaveCount = lifecycleManager.provisionCount.get();
        assertThat(firstWaveCount).isGreaterThanOrEqualTo(1);
        var reconciling = (NodeReconcilerState.Reconciling) ctm.reconcilerState();
        assertThat(reconciling.inFlight()).hasSize(firstWaveCount);
        // Wait past slot deadline
        Thread.sleep(200L);
        // Snapshot still reports the same deficit (stalled provision)
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                            4,
                                            5),
                               3L);
        // Trigger another reconcile — slots expire, top-up fires
        ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_D));
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThan(firstWaveCount);
        // After top-up dispatch the in-flight list reflects the new wave only
        var afterTopup = (NodeReconcilerState.Reconciling) ctm.reconcilerState();
        assertThat(afterTopup.inFlight()).isNotEmpty();
        // All surviving slots have a deadline in the future (the original ones were expired & dropped)
        var nowMs = System.currentTimeMillis();
        assertThat(afterTopup.inFlight()).allMatch(slot -> slot.deadlineMs() >= nowMs);
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
