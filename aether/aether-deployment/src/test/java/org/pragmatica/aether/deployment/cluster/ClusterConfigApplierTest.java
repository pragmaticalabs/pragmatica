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

/// The runtime config applier routes EVERY scale — any role — through the same fenced
/// desired-count write (RFC-0017 stage 5, #241 closed). It never provisions directly: the worker
/// reconciler acts on the committed value via the `ClusterConfigKey` fan-out, and the core path
/// stays with the LeaderReconciler. Before stage 5 a non-core scale was REJECTED here
/// (`RoleScaleUnsupported`) so it could not silently rewrite the core-only scalar; the typed
/// topology removed that hazard, so the guard's reason to exist is gone.
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
            assertThat(topologyManager.setDesiredCountCalls())
                    .as("the source and role must survive the applier — the scalar surface discarded them")
                    .containsExactly(new RecordingTopologyManager.ScaleCall("default", NodeRole.CORE, 7));
        }

        @Test
        void apply_coreScaleDown_appliesDesiredSize() {
            var result = applier.apply(List.of(new DiffAction.ScaleDown("default", NodeRole.CORE, 7, 5))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall("default", NodeRole.CORE, 5));
        }
    }

    @Nested
    class NonCoreScaleRoutes {
        @Test
        void apply_workerScaleUp_writesTheWorkerDesiredCount() {
            var result = applier.apply(List.of(new DiffAction.ScaleUp("default", NodeRole.WORKER, 0, 3))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .as("a WORKER scale writes the WORKER pair — the typed topology makes this safe")
                    .containsExactly(new RecordingTopologyManager.ScaleCall("default", NodeRole.WORKER, 3));
        }

        @Test
        void apply_workerScaleDown_writesTheWorkerDesiredCount() {
            var result = applier.apply(List.of(new DiffAction.ScaleDown("default", NodeRole.WORKER, 3, 1))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall("default", NodeRole.WORKER, 1));
        }

        @Test
        void apply_spotScaleUp_writesTheSpotDesiredCount() {
            var result = applier.apply(List.of(new DiffAction.ScaleUp("default", NodeRole.SPOT, 0, 2))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall("default", NodeRole.SPOT, 2));
        }

        /// A mixed diff applies BOTH actions in order — each role's count lands on its own
        /// (source, role) pair, and neither touches the other's.
        @Test
        void apply_mixedCoreThenWorker_appliesBothToTheirOwnPairs() {
            var actions = List.<DiffAction> of(new DiffAction.ScaleUp("default", NodeRole.CORE, 5, 6),
                                               new DiffAction.ScaleUp("default", NodeRole.WORKER, 0, 3));

            var result = applier.apply(actions).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall("default", NodeRole.CORE, 6),
                                     new RecordingTopologyManager.ScaleCall("default", NodeRole.WORKER, 3));
        }
    }

    /// Recording `ClusterTopologyManager` stub — only `setDesiredCount` matters for the applier;
    /// the rest is inert surface (same shape as `LeaderReconcilerTest.RecordingCtm`).
    private static final class RecordingTopologyManager implements ClusterTopologyManager {
        record ScaleCall(String sourceName, NodeRole role, int count) {}

        private final List<ScaleCall> setDesiredCountCalls = new CopyOnWriteArrayList<>();

        List<ScaleCall> setDesiredCountCalls() {
            return List.copyOf(setDesiredCountCalls);
        }

        @Override
        public Promise<Unit> setDesiredCount(String sourceName, NodeRole role, int count) {
            setDesiredCountCalls.add(new ScaleCall(sourceName, role, count));
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
