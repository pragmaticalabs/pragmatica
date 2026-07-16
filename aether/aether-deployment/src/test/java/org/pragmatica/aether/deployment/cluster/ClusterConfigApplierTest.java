// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import java.net.SocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Wave 2 / W5 (cluster-topology-overhaul spec): the runtime config applier ROLE-ROUTES scale
/// ops. A CORE-targeted scale mutates the core desired size exactly as before; a WORKER- (or
/// SPOT-) targeted scale must NOT touch core sizing — until worker provisioning is wired (#241)
/// it is rejected with an explicit operator error, never silently applied to the core size.
class ClusterConfigApplierTest {
    private RecordingTopologyManager topologyManager;
    private ClusterConfigApplier applier;

    @BeforeEach
    void setUp() {
        topologyManager = new RecordingTopologyManager();
        applier = ClusterConfigApplier.clusterConfigApplier(topologyManager);
    }

    @Nested
    class CoreScale {
        @Test
        void apply_coreScaleUp_appliesDesiredSize() {
            var result = applier.apply(List.of(new DiffAction.ScaleUp("default", NodeRole.CORE, 5, 7))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredSizeCalls()).containsExactly(7);
        }

        @Test
        void apply_coreScaleDown_appliesDesiredSize() {
            var result = applier.apply(List.of(new DiffAction.ScaleDown("default", NodeRole.CORE, 7, 5))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredSizeCalls()).containsExactly(5);
        }
    }

    @Nested
    class NonCoreScaleRejected {
        @Test
        void apply_workerScaleUp_rejectedWithoutTouchingCoreSize() {
            var result = applier.apply(List.of(new DiffAction.ScaleUp("default", NodeRole.WORKER, 0, 3))).await();

            assertThat(result.isFailure()).isTrue();
            assertThat(topologyManager.setDesiredSizeCalls()).as("a WORKER scale must never mutate the core desired size").isEmpty();
            result.onFailure(cause -> assertThat(cause.message()).contains("worker").contains("#241"));
        }

        @Test
        void apply_workerScaleDown_rejectedWithoutTouchingCoreSize() {
            var result = applier.apply(List.of(new DiffAction.ScaleDown("default", NodeRole.WORKER, 3, 1))).await();

            assertThat(result.isFailure()).isTrue();
            assertThat(topologyManager.setDesiredSizeCalls()).isEmpty();
        }

        @Test
        void apply_spotScaleUp_rejectedWithoutTouchingCoreSize() {
            var result = applier.apply(List.of(new DiffAction.ScaleUp("default", NodeRole.SPOT, 0, 2))).await();

            assertThat(result.isFailure()).isTrue();
            assertThat(topologyManager.setDesiredSizeCalls()).isEmpty();
        }

        /// A mixed diff applies the CORE action and fails on the WORKER action — the overall
        /// result is the failure (the operator sees the rejection), and core sizing reflects
        /// only the CORE-targeted op.
        @Test
        void apply_mixedCoreThenWorker_appliesCoreAndFailsOverall() {
            var actions = List.<DiffAction> of(new DiffAction.ScaleUp("default", NodeRole.CORE, 5, 6),
                                               new DiffAction.ScaleUp("default", NodeRole.WORKER, 0, 3));

            var result = applier.apply(actions).await();

            assertThat(result.isFailure()).isTrue();
            assertThat(topologyManager.setDesiredSizeCalls()).containsExactly(6);
        }
    }

    /// Recording `ClusterTopologyManager` stub — only `setDesiredSize` matters for the applier;
    /// the rest is inert surface (same shape as `LeaderReconcilerTest.RecordingCtm`).
    private static final class RecordingTopologyManager implements ClusterTopologyManager {
        private final List<Integer> setDesiredSizeCalls = new CopyOnWriteArrayList<>();

        List<Integer> setDesiredSizeCalls() {
            return List.copyOf(setDesiredSizeCalls);
        }

        @Override
        public Promise<Unit> setDesiredSize(int size) {
            setDesiredSizeCalls.add(size);
            return Promise.success(unit());
        }

        @Override
        public Promise<ProvisionDisposition> provisionReplacement(NodeId newNodeId,
                                                                  Option<NodeId> failedPeer,
                                                                  Set<NodeId> clusterMembers,
                                                                  NodeRole intendedRole) {
            return Promise.success(ProvisionDisposition.dispatched());
        }

        @Override
        public Promise<Unit> drainNode(NodeId targetNodeId, DrainReason reason) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Unit> reconcile() {
            return Promise.success(unit());
        }

        @Override
        public NodeReconcilerState reconcilerState() {
            return new NodeReconcilerState.Inactive("stub");
        }

        @Override
        public int desiredSize() {
            return 0;
        }

        @Override
        public int configuredSize() {
            return 0;
        }

        @Override
        @Contract
        public void onNodeReady(NodeId nodeId) {}

        @Override
        @Contract
        public void onMembershipDecision(MembershipDecision decision) {}

        @Override
        @Contract
        public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {}

        @Override
        @Contract
        public void onClusterConfigChanged() {}

        @Override
        @Contract
        public void onClusterPhaseChanged(ClusterPhase newPhase) {}

        @Override
        @Contract
        public void activate() {}

        @Override
        @Contract
        public void deactivate() {}

        @Override
        @Contract
        public TopologyObserver observer() {
            return null;
        }

        @Override
        public CircuitBreakerState circuitBreakerState() {
            return new CircuitBreakerState(0, 0, 0L, false);
        }

        @Override
        public Option<LastProvisionFailure> lastProvisionFailure() {
            return Option.none();
        }

        @Override
        public int resetCircuitBreaker(String reason) {
            return 0;
        }

        @Override
        public boolean isAutoHealEnabled() {
            return true;
        }

        @Override
        public boolean setAutoHealEnabled(boolean enabled, String reason) {
            return true;
        }

        @Override
        @Contract
        public NodeInfo self() {
            return null;
        }

        @Override
        public Option<NodeInfo> get(NodeId id) {
            return Option.none();
        }

        @Override
        public int clusterSize() {
            return 0;
        }

        @Override
        public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
            return Option.none();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(unit());
        }

        @Override
        public TimeSpan pingInterval() {
            return timeSpan(1).seconds();
        }

        @Override
        public TimeSpan helloTimeout() {
            return timeSpan(1).seconds();
        }

        @Override
        public Option<TlsConfig> tls() {
            return Option.none();
        }

        @Override
        public Option<NodeState> getState(NodeId id) {
            return Option.none();
        }

        @Override
        public List<NodeId> topology() {
            return List.of();
        }
    }
}
