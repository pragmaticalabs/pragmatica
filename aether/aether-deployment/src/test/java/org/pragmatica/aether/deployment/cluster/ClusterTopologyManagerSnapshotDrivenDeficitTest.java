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
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
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


/// Verifies that `ClusterTopologyManagerRecord` reads cluster size from the
/// snapshot-backed `MembershipView` (when present) and triggers provisioning without
/// running an independent deficit-hysteresis timer chain. After the commit-3 refactor
/// the CTM has no local `configuredSize`/`desiredSize` caches — everything flows
/// through the snapshot, and `setDesiredSize` is a thin `ClusterConfigValue` write.
class ClusterTopologyManagerSnapshotDrivenDeficitTest {
    // Termination candidates must be CTM-provisioned — the hard filter
    // (`MembershipView::ctmProvisionedNodeIds`) excludes fixtures that the compute provider
    // cannot actually terminate. The stub view's `ctmProvisionedNodeIds` determines
    // eligibility; node-id prefixes no longer drive this decision.
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
    private ClusterTopologyManager ctm;

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
        // Seed a baseline ClusterConfigValue so setDesiredSize() can write-through.
        configStore = new StubClusterConfigStore();
        configStore.seed(5);
        // Use a long retry interval so the safety-net timer never fires during the test —
        // we drive reconciliation purely via setDesiredSize / topology events. Use a 1ms
        // stability window so the post-RC1 phantom-provision gate is a no-op for these
        // legacy provisioning-flow tests; the gate itself is covered by
        // `ClusterTopologyManagerStabilityWindowTest`.
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      timeSpan(0).millis())
                                            .unwrap();
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
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
                                                            () -> AetherValue.ClusterPhase.NORMAL);
    }

    @Test
    void reconcile_provisionsImmediately_whenSnapshotReportsDeficit() {
        // Snapshot reports only 4 healthy ON_DUTY out of desired 5
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C),
                                            4,
                                            5),
                               1L);
        ctm.activate();
        // Topology event triggers reconcile; snapshot-driven deficit provisioning fires
        ctm.onNodeReady(PEER_A);
        // Expect one provisioning attempt - no hysteresis defer
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Reconciling.class);
    }

    @Test
    void reconcile_doesNotProvision_whenSnapshotReportsConverged() {
        // Snapshot reports all 5 healthy ON_DUTY at the desired core size of 5
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        assertThat(lifecycleManager.provisionCount.get()).isZero();
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);
    }

    @Test
    void reconcile_terminatesSurplus_whenSnapshotReportsOverCapacity() {
        // Snapshot reports 7 healthy ON_DUTY but desired core size is 5
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            7,
                                            5),
                               1L);
        ctm.activate();
        // Topology change triggers reconcile; snapshot surplus drives termination.
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.terminateCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void setDesiredSize_writesClusterConfigValueAtom_withIncrementedVersion() {
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        var before = configStore.currentVersion();
        var result = ctm.setDesiredSize(7).await();
        assertThat(result.isSuccess()).isTrue();
        var after = configStore.current().unwrap();
        assertThat(after.coreCount()).isEqualTo(7);
        assertThat(after.configVersion()).isEqualTo(before + 1);
        assertThat(configStore.applyCount.get()).isEqualTo(1);
    }

    @Test
    void setDesiredSize_belowQuorum_rejectedWithoutAtomWrite() {
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        var result = ctm.setDesiredSize(2).await();
        assertThat(result.isFailure()).isTrue();
        assertThat(configStore.applyCount.get()).isZero();
    }

    @Test
    void reconcile_terminatesOnlyCtmProvisionedSurplus_whenSurplusIsManual() {
        // 7 healthy members but only SELF is CTM-provisioned; the rest are MANUAL.
        // SELF is excluded (self cannot be terminated), so no eligible candidates remain.
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            7,
                                            5,
                                            Set.of(SELF)),
                               1L);
        ctm.activate();
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.terminateCount.get()).isZero();
    }

    @Test
    void reconcile_terminatesCtmProvisionedSurplus_whenCandidatesAreCtm() {
        // 7 healthy members; PEER_A and PEER_B are CTM-provisioned candidates.
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            7,
                                            5,
                                            Set.of(PEER_A, PEER_B)),
                               1L);
        ctm.activate();
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.terminateCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(lifecycleManager.terminatedNodeIds()).isSubsetOf(Set.of(PEER_A, PEER_B));
    }

    @Test
    void reconcile_doesNotTerminate_whenAllCandidatesUnknownProvisioningSource() {
        // Empty CTM-provisioned set models an UNKNOWN / legacy projection —
        // selection must refuse to terminate anything (conservative).
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            7,
                                            5,
                                            Set.of()),
                               1L);
        ctm.activate();
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.terminateCount.get()).isZero();
    }

    @Test
    void reconcile_prefersEmptyNodesForTermination_whenSnapshotReportsNodesWithoutSlices() {
        // PEER_A and PEER_B both CTM-provisioned; only PEER_B has no slices per snapshot.
        // The comparator prefers empty nodes, so PEER_B must be terminated ahead of PEER_A
        // when surplus == 1.
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            6,
                                            5,
                                            Set.of(PEER_A, PEER_B),
                                            Set.of(PEER_B)),
                               1L);
        ctm.activate();
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.terminateCount.get()).isEqualTo(1);
        assertThat(lifecycleManager.terminatedNodeIds()).containsExactly(PEER_B);
    }

    @Test
    void reconcile_emptyNodeOutsideCtmProvisionedSet_isNotTerminated() {
        // PEER_A reported as empty by snapshot but NOT CTM-provisioned — hard filter excludes it.
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            6,
                                            5,
                                            Set.of(),
                                            Set.of(PEER_A)),
                               1L);
        ctm.activate();
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.terminateCount.get()).isZero();
    }

    @Test
    void reconcile_redispatches_whenTargetChangesMidReconciling() {
        // Regression for Bug W: scale-up 5→7 places CTM in Reconciling(target=7, current=5).
        // Before the new target (7) is reached, operator scales back down to 5. The snapshot's
        // desiredCoreSize becomes 5 and actual membership reaches 7 (both provisioned nodes joined).
        // Without the stale-target guard, handleSurplus short-circuits on `instanceof Reconciling`
        // and the cluster stays stuck for minutes. With the fix, reconcileActive detects the target
        // change, resets to Converged, and handleSurplus then terminates the surplus.
        //
        // Step 1: activate in a converged state (desired=5, actual=5) — bypasses Forming.
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5,
                                            Set.of(PEER_A, PEER_B)),
                               1L);
        ctm.activate();
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);

        // Step 2: operator scales up to 7 — deficit triggers provisioning and enters Reconciling(target=7).
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            7,
                                            Set.of(PEER_A, PEER_B)),
                               2L);
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Reconciling.class);
        var reconciling = (NodeReconcilerState.Reconciling) ctm.reconcilerState();
        assertThat(reconciling.targetSize()).isEqualTo(7);
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThanOrEqualTo(1);

        // Step 3: operator scales back down to 5 while the previous scale-up is still in-flight.
        // Both provisioned nodes joined (snapshot actual=7) with desired=5.
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            7,
                                            5,
                                            Set.of(PEER_A, PEER_B)),
                               3L);

        // Step 4: next reconcile trigger (topology event, safety-net poll, or onNodeReady).
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeJoined(PEER_B, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));

        // The stale-target guard transitions Reconciling(target=7) → Converged and re-dispatches
        // handleSurplus, which terminates the CTM-provisioned surplus (PEER_A and/or PEER_B).
        assertThat(lifecycleManager.terminateCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(lifecycleManager.terminatedNodeIds()).isSubsetOf(Set.of(PEER_A, PEER_B));
    }

    @Test
    void reconcile_observesNewSnapshotTerm_onSubsequentTrigger() {
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);

        // Snapshot advances to a new term reporting deficit
        snapshotSource.publish(StubView.stubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        // External trigger (topology event in production) drives reconcile
        ctm.onMembershipDecision(org.pragmatica.consensus.topology.MembershipDecision.nodeRemoved(PEER_C, List.of()));
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThanOrEqualTo(1);
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize,
                            Set<NodeId> ctmProvisionedNodeIds,
                            Set<NodeId> nodesWithoutSlices) implements MembershipView {
        StubView(Set<NodeId> coreMemberIds,
                 Set<NodeId> onDutyMemberIds,
                 int healthyOnDutyCount,
                 int desiredCoreSize,
                 Set<NodeId> ctmProvisionedNodeIds) {
            this(coreMemberIds, onDutyMemberIds, healthyOnDutyCount, desiredCoreSize, ctmProvisionedNodeIds, Set.of());
        }

        static StubView stubView(Set<NodeId> coreMemberIds,
                                 Set<NodeId> onDutyMemberIds,
                                 int healthyOnDutyCount,
                                 int desiredCoreSize) {
            // Default: all core members are CTM-provisioned (preserves existing test semantics).
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

    /// Simulates the kv-store `ClusterConfigValue` atom plus the `cluster.apply` path
    /// that CTM uses for `setDesiredSize` write-through.
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

        long currentVersion() {
            return current.get().map(ClusterConfigValue::configVersion).or(0L);
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
        private final java.util.concurrent.CopyOnWriteArraySet<NodeId> terminatedIds = new java.util.concurrent.CopyOnWriteArraySet<>();

        Set<NodeId> terminatedNodeIds() {
            return Set.copyOf(terminatedIds);
        }

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
            terminatedIds.add(nodeId);
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
