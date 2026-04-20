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
    // NodeIds prefixed with `aether-core-` mark CTM-provisioned nodes (see
    // `ClusterTopologyManagerRecord.isProvisionedByCtm`). Termination candidates must be
    // provisioned — the hard filter excludes fixtures that the compute provider cannot
    // actually terminate. Test peers use the provisioned-style prefix so surplus selection
    // admits them as candidates.
    private static final NodeId SELF = nodeId("aether-core-self").unwrap();
    private static final NodeId PEER_A = nodeId("aether-core-a").unwrap();
    private static final NodeId PEER_B = nodeId("aether-core-b").unwrap();
    private static final NodeId PEER_C = nodeId("aether-core-c").unwrap();
    private static final NodeId PEER_D = nodeId("aether-core-d").unwrap();

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
        // we drive reconciliation purely via setDesiredSize / topology events.
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(), timeSpan(1).millis()).unwrap();
        ctm = ClusterTopologyManager.clusterTopologyManager(observer,
                                                            lifecycleManager,
                                                            autoHeal,
                                                            DeploymentMap.deploymentMap(),
                                                            snapshotSource,
                                                            configStore::current,
                                                            configStore::apply);
    }

    @Test
    void reconcile_provisionsImmediately_whenSnapshotReportsDeficit() {
        // Snapshot reports only 4 healthy ON_DUTY out of desired 5
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
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
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
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
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            7,
                                            5),
                               1L);
        ctm.activate();
        // Topology change triggers reconcile; snapshot surplus drives termination.
        ctm.onTopologyChange(org.pragmatica.consensus.topology.TopologyChangeNotification.nodeAdded(PEER_A, List.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D)));
        assertThat(lifecycleManager.terminateCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void setDesiredSize_writesClusterConfigValueAtom_withIncrementedVersion() {
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        var before = configStore.currentVersion();
        var result = ctm.setDesiredSize(7);
        assertThat(result.isSuccess()).isTrue();
        var after = configStore.current().unwrap();
        assertThat(after.coreCount()).isEqualTo(7);
        assertThat(after.configVersion()).isEqualTo(before + 1);
        assertThat(configStore.applyCount.get()).isEqualTo(1);
    }

    @Test
    void setDesiredSize_belowQuorum_rejectedWithoutAtomWrite() {
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        var result = ctm.setDesiredSize(2);
        assertThat(result.isFailure()).isTrue();
        assertThat(configStore.applyCount.get()).isZero();
    }

    @Test
    void reconcile_observesNewSnapshotTerm_onSubsequentTrigger() {
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            5,
                                            5),
                               1L);
        ctm.activate();
        assertThat(ctm.reconcilerState()).isInstanceOf(NodeReconcilerState.Converged.class);

        // Snapshot advances to a new term reporting deficit
        snapshotSource.publish(new StubView(Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D),
                                            Set.of(SELF, PEER_A, PEER_B),
                                            3,
                                            5),
                               2L);
        // External trigger (topology event in production) drives reconcile
        ctm.onTopologyChange(org.pragmatica.consensus.topology.TopologyChangeNotification.nodeDown(PEER_C));
        assertThat(lifecycleManager.provisionCount.get()).isGreaterThanOrEqualTo(1);
    }

    private record StubView(Set<NodeId> coreMemberIds,
                            Set<NodeId> onDutyMemberIds,
                            int healthyOnDutyCount,
                            int desiredCoreSize) implements MembershipView {}

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
