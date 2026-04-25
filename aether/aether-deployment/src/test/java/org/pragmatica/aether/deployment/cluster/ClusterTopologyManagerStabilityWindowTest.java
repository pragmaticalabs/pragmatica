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


/// Theme B Item 3 — verifies the universal `provisionStabilityWindow` gate. CTM may NOT
/// dispatch any provisioning until `realActual` (the snapshot's healthy ON_DUTY count) has
/// been stable for the configured window. This prevents phantom provisioning during cluster
/// boot when only N of M static nodes have joined and the count is still climbing.
class ClusterTopologyManagerStabilityWindowTest {
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

    /// 1-second stability window for tests — long enough that activate-followed-by-event is
    /// reliably gated, short enough that the legitimate-dispatch test completes quickly.
    private static final TimeSpan STABILITY_WINDOW = timeSpan(1).seconds();

    /// 1-millisecond Forming cooldown so the formation phase exits immediately and any
    /// subsequent dispatch must flow through `handleDeficit` (and thus the stability gate).
    private static final TimeSpan FORMING_COOLDOWN = timeSpan(1).millis();

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

    private ClusterTopologyManager createCtm(TimeSpan retryInterval) {
        var autoHeal = AutoHealConfig.autoHealConfig(retryInterval,
                                                      FORMING_COOLDOWN,
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      timeSpan(60).seconds(),
                                                      STABILITY_WINDOW)
                                            .unwrap();
        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                              lifecycleManager,
                                                              autoHeal,
                                                              DeploymentMap.deploymentMap(),
                                                              snapshotSource,
                                                              configStore::current,
                                                              configStore::apply);
    }

    /// Long retry interval — safety-net poll never fires during the test.
    private ClusterTopologyManager createCtm() {
        return createCtm(timeSpan(60).seconds());
    }

    /// Cluster boot scenario: 3 of 5 static nodes joined (real boot still in progress).
    /// Activate fires, Forming cooldown elapses (1ms), `handleDeficit` is called — but the
    /// stability anchor was just bumped on activate, so the gate defers the dispatch. No
    /// provisioning fires while we stay within the stability window.
    @Test
    void partialBootDuringFormingCooldown_doesNotDispatchPhantom() throws InterruptedException {
        var ctm = createCtm();
        // Snapshot reflects partial boot: configured=5, only 3 healthy ON_DUTY.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               1L);
        ctm.activate();
        // Forming cooldown is 1ms; let it expire and `handleDeficit` run.
        Thread.sleep(50L);
        // We are well within the 1-second stability window — no dispatch must have fired.
        assertThat(lifecycleManager.provisionCount.get())
                .as("phantom provisioning during stability window")
                .isZero();
        // Sanity: the `handleDeficit` call did execute (state is Converged after the gate
        // bailed out, since CTM never transitioned to Reconciling).
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
    }

    /// Boot eventually completes: realActual rises to 5 (all static nodes joined). The
    /// snapshot-driven reconcile observes convergence and stays in `Converged` without ever
    /// having dispatched a provision.
    @Test
    void partialBootCompletes_convergesWithoutProvisioning() throws InterruptedException {
        var ctm = createCtm();
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               1L);
        ctm.activate();
        Thread.sleep(50L);
        // Boot completes: snapshot now reports all 5 ON_DUTY before the stability window expires.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               2L);
        // External trigger as the last node joins.
        ctm.onTopologyChange(TopologyChangeNotification.nodeAdded(PEER_D, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.provisionCount.get()).isZero();
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
    }

    /// Legitimate post-grace deficit: realActual stays at 3 throughout the entire stability
    /// window. After the window elapses, the safety-net poll fires (NOT a topology event,
    /// which would re-bump the anchor) and dispatches provisioning. Use a short retry
    /// interval so the safety-net poll arrives soon after the stability window elapses.
    @Test
    void realActualStableForWindow_dispatchesDeficitProvisioning() throws InterruptedException {
        var ctm = createCtm(timeSpan(200).millis());
        // Activate while already at desired size to bypass Forming.
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        // Two nodes go down — anchor bumps but the deficit is permanent (not boot-in-progress).
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        ctm.onTopologyChange(TopologyChangeNotification.nodeDown(PEER_C));
        // Within the stability window — no dispatch yet.
        assertThat(lifecycleManager.provisionCount.get())
                .as("dispatch suppressed during stability window")
                .isZero();
        // Wait for the stability window to elapse PLUS a safety-net poll cycle. The safety-net
        // poll is the only re-trigger that does NOT bump the anchor, so the gate becomes
        // releasable purely by elapsed wall-clock time.
        Thread.sleep(STABILITY_WINDOW.millis() + 500L);
        assertThat(lifecycleManager.provisionCount.get())
                .as("dispatch released after stability window elapsed")
                .isGreaterThanOrEqualTo(1);
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
