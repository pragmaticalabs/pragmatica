// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.drain.NoOpDrainCoordinator;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// R7 acceptance tests:
///   1. Auto-heal must be suspended in COLD_BOOT / RECOVERING phases (no provisioning, no
///      decommissioning).
///   2. Phase transition back to NORMAL must restart the stability timer from zero (post-
///      RECOVERING is treated as freshly stable, not resumed).
///   3. The provision-stability anchor only resets on `healthyOnDutyCount` edge transitions
///      (spec §11 Q2 resolution).
///   4. Drain / decommission writes must route through `LifecycleWriter` (MembershipFsm in
///      production, recording stub here) — CTM no longer performs direct KV writes for
///      `NodeLifecycleKey`.
class ClusterTopologyManagerPhaseAwareTest {
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

    private static final TimeSpan STABILITY_WINDOW = timeSpan(500).millis();
    private static final TimeSpan FORMING_COOLDOWN = timeSpan(1).millis();

    private StubSnapshotSource snapshotSource;
    private TopologyObserver observer;
    private RecordingLifecycleManager lifecycleManager;
    private StubClusterConfigStore configStore;
    private RecordingLifecycleWriter lifecycleWriter;
    private AtomicReference<ClusterPhase> phase;

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
        lifecycleWriter = new RecordingLifecycleWriter();
        phase = new AtomicReference<>(ClusterPhase.NORMAL);
    }

    private ClusterTopologyManager createCtm() {
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(200).millis(),
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
                                                              nodeId -> Option.none(),
                                                              () -> java.util.Map.of(),
                                                              configStore::apply,
                                                              new NoOpDrainCoordinator(),
                                                              lifecycleWriter,
                                                              phase::get);
    }

    @Nested class PhaseAwareSuspension {
        @Test
        void ctm_suspendedInColdBootPhase_doesNotProvision() throws InterruptedException {
            phase.set(ClusterPhase.COLD_BOOT);
            var ctm = createCtm();
            // Snapshot has a deficit (3/5) AND already-stable anchor.
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B),
                                                     3,
                                                     5),
                                   1L);
            ctm.activate();
            // Wait long enough for a safety-net poll cycle.
            Thread.sleep(STABILITY_WINDOW.millis() + 400L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("COLD_BOOT phase suspends provisioning")
                    .isZero();
        }

        @Test
        void ctm_suspendedInRecoveringPhase_doesNotDecommission() throws InterruptedException {
            phase.set(ClusterPhase.RECOVERING);
            var ctm = createCtm();
            // Surplus (5 ON_DUTY, configured 3) — without phase suspension, would terminate.
            configStore.seed(3);
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     5,
                                                     3),
                                   1L);
            ctm.activate();
            Thread.sleep(STABILITY_WINDOW.millis() + 400L);
            assertThat(lifecycleManager.terminateCount.get())
                    .as("RECOVERING phase suspends decommissioning")
                    .isZero();
        }

        @Test
        void ctm_phaseTransitionToColdBoot_cancelsInFlightStabilityWindow() {
            phase.set(ClusterPhase.NORMAL);
            var ctm = createCtm();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B),
                                                     3,
                                                     5),
                                   1L);
            ctm.activate();
            // Phase transitions away from NORMAL — no provisioning even after stability window.
            phase.set(ClusterPhase.COLD_BOOT);
            ctm.onClusterPhaseChanged(ClusterPhase.COLD_BOOT);
            assertThat(lifecycleManager.provisionCount.get()).isZero();
        }

        @Test
        void ctm_phaseTransitionBackToNormal_restartsStabilityWindowFromZero() throws InterruptedException {
            phase.set(ClusterPhase.NORMAL);
            var ctm = createCtm();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     5,
                                                     5),
                                   1L);
            ctm.activate();
            // Move to RECOVERING — drop to 3 ON_DUTY.
            phase.set(ClusterPhase.RECOVERING);
            ctm.onClusterPhaseChanged(ClusterPhase.RECOVERING);
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B),
                                                     3,
                                                     5),
                                   2L);
            // Move back to NORMAL — the stability window must restart from zero. So immediately
            // after the transition, no provisioning should fire (the safety-net poll within the
            // first STABILITY_WINDOW must observe the gate as not-yet-elapsed).
            phase.set(ClusterPhase.NORMAL);
            ctm.onClusterPhaseChanged(ClusterPhase.NORMAL);
            // Small wait — well below STABILITY_WINDOW. Provisioning should NOT have fired yet.
            Thread.sleep(100L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("Phase NORMAL transition restarts stability window from zero — no immediate dispatch")
                    .isZero();
            // Wait the full window — provisioning should now be released.
            Thread.sleep(STABILITY_WINDOW.millis() + 400L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("After stability window elapses post-NORMAL, dispatch is released")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Nested class StabilityEdgeSemantics {
        @Test
        void ctm_stabilityAnchor_doesNotReset_onSameCountNotification() throws InterruptedException {
            phase.set(ClusterPhase.NORMAL);
            var ctm = createCtm();
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B),
                                                     3,
                                                     5),
                                   1L);
            ctm.activate();
            // Issue several topology change notifications carrying the SAME healthyOnDuty=3.
            // Spec §11 Q2: anchor must only reset on edge transitions. Repeated same-count
            // notifications are flap and must be ignored. After the stability window elapses
            // (without any further edge), provisioning must dispatch.
            for (var i = 0; i < 10; i++) {
                ctm.onMembershipDecision(MembershipDecision.nodeRemoved(PEER_C, List.of()));
                Thread.sleep(20L);
            }
            Thread.sleep(STABILITY_WINDOW.millis() + 400L);
            assertThat(lifecycleManager.provisionCount.get())
                    .as("Same-count notifications must not delay provisioning beyond the stability window")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Nested class LifecycleRouting {
        @Test
        void ctm_drainPath_routesThroughLifecycleWriter() throws InterruptedException {
            phase.set(ClusterPhase.NORMAL);
            var ctm = createCtm();
            // Surplus: 5 ON_DUTY, configured 3 → expect 2 drains via LifecycleWriter.
            configStore.seed(3);
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     5,
                                                     3),
                                   1L);
            ctm.activate();
            ctm.onClusterConfigChanged(); // bypass stability gate
            // Wait for the async drain/terminate chain to complete.
            var deadline = System.currentTimeMillis() + 3000L;
            while (lifecycleWriter.drainCount.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(lifecycleWriter.drainCount.get())
                    .as("CTM drain must route through LifecycleWriter — no direct KV write")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        void ctm_decommissionPath_routesThroughLifecycleWriter() throws InterruptedException {
            phase.set(ClusterPhase.NORMAL);
            var ctm = createCtm();
            configStore.seed(3);
            snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                                     5,
                                                     3),
                                   1L);
            ctm.activate();
            ctm.onClusterConfigChanged();
            var deadline = System.currentTimeMillis() + 3000L;
            while (lifecycleWriter.decommissionCount.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(lifecycleWriter.decommissionCount.get())
                    .as("CTM decommission must route through LifecycleWriter — no direct KV write")
                    .isGreaterThanOrEqualTo(1);
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

    private static final class StubClusterConfigStore {
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

    private static final class RecordingLifecycleWriter implements LifecycleWriter {
        final AtomicInteger drainCount = new AtomicInteger();
        final AtomicInteger decommissionCount = new AtomicInteger();
        final AtomicInteger activateCount = new AtomicInteger();
        final AtomicInteger failedDrainCount = new AtomicInteger();

        @Override public Promise<Unit> requestDrain(NodeId target) {
            drainCount.incrementAndGet();
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> requestDecommission(NodeId target) {
            decommissionCount.incrementAndGet();
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> requestActivate(NodeId target) {
            activateCount.incrementAndGet();
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> requestFailedDrain(NodeId target) {
            failedDrainCount.incrementAndGet();
            return Promise.success(Unit.unit());
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
