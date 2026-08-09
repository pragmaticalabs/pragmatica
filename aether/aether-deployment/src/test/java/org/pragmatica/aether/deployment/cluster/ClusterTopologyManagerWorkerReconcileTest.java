// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// RFC-0017 stage 5 — the CTM's worker-topology reconcile pass: ACTUAL provider inventory (label
/// listing) converged toward the desired per-(source, role) topology in cluster state. The core
/// tier is deliberately untouched — its reconciliation lives in the hardened LeaderReconciler
/// path; a worker deficit is never quorum-ambiguous.
class ClusterTopologyManagerWorkerReconcileTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 5002).unwrap());

    private WorkerRecordingLifecycleManager lifecycleManager;
    private AtomicReference<Option<ClusterConfigValue>> configRef;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        var snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF,
                                        3,
                                        timeSpan(60).seconds(),
                                        timeSpan(1).seconds(),
                                        List.of(INFO_SELF, INFO_A, INFO_B));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        lifecycleManager = new WorkerRecordingLifecycleManager();
        configRef = new AtomicReference<>(Option.none());
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
                                                            configRef::get,
                                                            ClusterTopologyManagerWorkerReconcileTest::applyNoop,
                                                            () -> AetherValue.ClusterPhase.NORMAL,
                                                            _ -> {},
                                                            _ -> {});
    }

    private static Promise<List<Object>> applyNoop(List<KVCommand<AetherKey>> commands) {
        return Promise.success(List.of());
    }

    private void seedTopology(AetherValue.TopologyEntry... entries) {
        configRef.set(Option.some(new ClusterConfigValue("", "prod", "1.0.0", List.of(entries), 3, 9, "test", 1L,
                                                         System.currentTimeMillis())));
    }

    private static AetherValue.TopologyEntry entry(String source, String role, int count) {
        return new AetherValue.TopologyEntry(source, role, count);
    }

    @Test
    void reconcile_workerDeficit_provisionsMissingWorkers_withWorkerRoleAndScopedFilter() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.provisionedRoles()).containsOnly("worker");
        assertThat(lifecycleManager.provisionedNodeIds())
                .hasSize(2)
                .allMatch(id -> id.startsWith("primary-worker-r"));
        assertThat(lifecycleManager.lastListFilter())
                .containsEntry("aether-cluster", "prod")
                .containsEntry("aether-source", "primary")
                .containsEntry("aether-role", "worker");
    }

    /// A pass sees what earlier provisions created (the recording manager feeds them back through
    /// `listInstances`) — a second poke finds desired == actual and provisions nothing more.
    @Test
    void reconcile_isConvergent_secondPassProvisionsNothing() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));
        ctm.activate();
        ctm.reconcileWorkerTopology();
        var afterFirst = lifecycleManager.provisionedNodeIds().size();

        ctm.reconcileWorkerTopology();

        assertThat(afterFirst).isEqualTo(2);
        assertThat(lifecycleManager.provisionedNodeIds()).hasSize(2);
    }

    /// Newest first: reconciler-minted `-r<clock36>` ids sort after bootstrap `-<index>` ids, so a
    /// scale-down reaps cluster-provisioned workers before bootstrap-provisioned ones.
    @Test
    void reconcile_workerSurplus_terminatesNewestFirst() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 1));
        lifecycleManager.preExisting(workerInstance("primary-worker-0"),
                                     workerInstance("primary-worker-1"),
                                     workerInstance("primary-worker-rzzz-0"));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.terminatedNodeIds())
                .containsExactly("primary-worker-rzzz-0", "primary-worker-1");
        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
    }

    @Test
    void reconcile_coreOnlyTopology_touchesNothing() {
        seedTopology(entry("primary", "core", 5));
        ctm.activate();

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.listCalls()).isZero();
        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
    }

    /// The `active` flag IS the leadership gate (same guard as the membership actuator path) — a
    /// non-leader CTM poked by the config fan-out must not act.
    @Test
    void reconcile_inactiveCtm_neverListsOrProvisions() {
        seedTopology(entry("primary", "core", 3), entry("primary", "worker", 2));

        ctm.reconcileWorkerTopology();

        assertThat(lifecycleManager.listCalls()).isZero();
        assertThat(lifecycleManager.provisionedNodeIds()).isEmpty();
    }

    private static InstanceInfo workerInstance(String nodeId) {
        return new InstanceInfo(InstanceId.instanceId("i-" + nodeId).unwrap(),
                                InstanceStatus.RUNNING,
                                List.of("127.0.0.1"),
                                InstanceType.ON_DEMAND,
                                Map.of("aether-role", "worker"),
                                Option.some(nodeId));
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        @Override public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Production-faithful recording manager: provisioned workers become VISIBLE to subsequent
    /// `listInstances` calls, so convergence is testable and the double-pass artifact (activate +
    /// explicit poke both reconciling against a stale empty inventory) cannot occur.
    private static final class WorkerRecordingLifecycleManager implements NodeLifecycleManager {
        private final CopyOnWriteArrayList<ProvisionSpec> provisioned = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<InstanceInfo> inventory = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> terminated = new CopyOnWriteArrayList<>();
        private final AtomicReference<Map<String, String>> lastFilter = new AtomicReference<>(Map.of());
        private final AtomicLong listCalls = new AtomicLong();

        void preExisting(InstanceInfo... instances) {
            inventory.addAll(List.of(instances));
        }

        List<String> provisionedRoles() {
            return provisioned.stream().map(spec -> spec.context().role()).distinct().toList();
        }

        List<String> provisionedNodeIds() {
            return provisioned.stream().flatMap(spec -> spec.context().nodeId().stream()).toList();
        }

        List<String> terminatedNodeIds() {
            return List.copyOf(terminated);
        }

        Map<String, String> lastListFilter() {
            return lastFilter.get();
        }

        long listCalls() {
            return listCalls.get();
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            provisioned.add(spec);
            var nodeId = spec.context().nodeId().or("unknown");
            var info = new InstanceInfo(InstanceId.instanceId("i-" + nodeId).unwrap(),
                                        InstanceStatus.RUNNING,
                                        List.of("127.0.0.1"),
                                        InstanceType.ON_DEMAND,
                                        Map.of("aether-role", spec.context().role()),
                                        Option.some(nodeId));

            inventory.add(info);

            return Promise.success(info);
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            listCalls.incrementAndGet();
            lastFilter.set(tagFilter);
            var role = tagFilter.getOrDefault("aether-role", "");

            return Promise.success(inventory.stream()
                                            .filter(info -> role.equals(info.tags().getOrDefault("aether-role", "")))
                                            .toList());
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            terminated.add(nodeId.id());
            inventory.removeIf(info -> info.nodeId().or("").equals(nodeId.id()));

            return Promise.success(unit());
        }

        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStopped(SELF));
        }

        @Override
        public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(unit());
        }

        @Override
        public boolean isCloudManaged() {
            return true;
        }
    }
}
