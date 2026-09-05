// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.http.HttpStatus;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
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
            var result = applier.apply(List.of(new DiffAction.ScaleUp(sourceNameOrDefault("default"), NodeRole.CORE, 5, 7))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .as("the source and role must survive the applier — the scalar surface discarded them")
                    .containsExactly(new RecordingTopologyManager.ScaleCall(sourceNameOrDefault("default"), NodeRole.CORE, 7));
        }

        @Test
        void apply_coreScaleDown_appliesDesiredSize() {
            var result = applier.apply(List.of(new DiffAction.ScaleDown(sourceNameOrDefault("default"), NodeRole.CORE, 7, 5))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall(sourceNameOrDefault("default"), NodeRole.CORE, 5));
        }
    }

    @Nested
    class NonCoreScaleRoutes {
        @Test
        void apply_workerScaleUp_writesTheWorkerDesiredCount() {
            var result = applier.apply(List.of(new DiffAction.ScaleUp(sourceNameOrDefault("default"), NodeRole.WORKER, 0, 3))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .as("a WORKER scale writes the WORKER pair — the typed topology makes this safe")
                    .containsExactly(new RecordingTopologyManager.ScaleCall(sourceNameOrDefault("default"), NodeRole.WORKER, 3));
        }

        @Test
        void apply_workerScaleDown_writesTheWorkerDesiredCount() {
            var result = applier.apply(List.of(new DiffAction.ScaleDown(sourceNameOrDefault("default"), NodeRole.WORKER, 3, 1))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall(sourceNameOrDefault("default"), NodeRole.WORKER, 1));
        }

        @Test
        void apply_spotScaleUp_writesTheSpotDesiredCount() {
            var result = applier.apply(List.of(new DiffAction.ScaleUp(sourceNameOrDefault("default"), NodeRole.SPOT, 0, 2))).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall(sourceNameOrDefault("default"), NodeRole.SPOT, 2));
        }

        /// A mixed diff applies BOTH actions in order — each role's count lands on its own
        /// (source, role) pair, and neither touches the other's.
        @Test
        void apply_mixedCoreThenWorker_appliesBothToTheirOwnPairs() {
            var actions = List.<DiffAction> of(new DiffAction.ScaleUp(sourceNameOrDefault("default"), NodeRole.CORE, 5, 6),
                                               new DiffAction.ScaleUp(sourceNameOrDefault("default"), NodeRole.WORKER, 0, 3));

            var result = applier.apply(actions).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .containsExactly(new RecordingTopologyManager.ScaleCall(sourceNameOrDefault("default"), NodeRole.CORE, 6),
                                     new RecordingTopologyManager.ScaleCall(sourceNameOrDefault("default"), NodeRole.WORKER, 3));
        }
    }

    /// #578 — the other 8 `DiffAction` variants used to fall through a catch-all `default` that
    /// logged and returned success; a config push naming one of them silently no-op'd while the
    /// response claimed the apply worked. They must now fail loudly instead, and — #578 review — a
    /// plan mixing a supported scale with an unsupported action must reject the WHOLE plan before
    /// actuating anything, not apply the scale and then fail (that would mutate the cluster while
    /// telling the operator the apply failed).
    @Nested
    class UnsupportedActions {
        /// 7 of the 8 unimplemented kinds share one cause — `ImmutableFieldChange` is covered
        /// separately below because it has its own dedicated, pre-existing `Cause` (409, not 501).
        static Stream<Arguments> theSevenGenericallyUnsupportedKinds() {
            var source = sourceNameOrDefault("extra");

            return Stream.of(Arguments.of(new DiffAction.AddSource(source)),
                             Arguments.of(new DiffAction.RemoveSource(source)),
                             Arguments.of(new DiffAction.AddRole(source, NodeRole.WORKER, 2)),
                             Arguments.of(new DiffAction.RemoveRole(source, NodeRole.WORKER, 2)),
                             Arguments.of(new DiffAction.RuntimeChange(source, NodeRole.WORKER, "jvm", "container")),
                             Arguments.of(new DiffAction.SourceFieldChange(source, "image")),
                             Arguments.of(new DiffAction.ClusterLevelChange("distribution.strategy", "balanced", "manual")));
        }

        @ParameterizedTest
        @MethodSource("theSevenGenericallyUnsupportedKinds")
        void apply_eachGenericallyUnsupportedKind_failsWithUnsupportedApplyAction_noTopologyWrite(DiffAction action) {
            var result = applier.apply(List.of(action)).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(ClusterConfigError.UnsupportedApplyAction.class,
                                                                              unsupported -> {
                assertThat(unsupported.action()).isEqualTo(action);
                assertThat(unsupported.httpStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
            }));
            assertThat(topologyManager.setDesiredCountCalls()).isEmpty();
        }

        /// `ImmutableFieldChange` is production-unreachable through the route (`ClusterConfigRoutes`
        /// rejects it earlier, at the `DiffPlan.hasImmutableChanges()` check, before the applier ever
        /// sees it) but the applier must still refuse it correctly if ever called directly — with its
        /// own 409, not the shared 501 the other 7 kinds get, and it must never reach the topology
        /// manager (#578 review Testing Gap T2 — field() carries the actual config key, and a
        /// rejected plan must have zero side effects, same as the mixed-plan test below).
        @Test
        void apply_immutableFieldChange_failsWithConflictStatus() {
            var result = applier.apply(List.of(new DiffAction.ImmutableFieldChange("cluster.name"))).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(ClusterConfigError.ImmutableFieldChange.class,
                                                                              immutable -> {
                assertThat(immutable.field()).isEqualTo("cluster.name");
                assertThat(immutable.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            }));
            assertThat(topologyManager.setDesiredCountCalls()).isEmpty();
        }

        /// The applier used to fold actions one at a time with `flatMap`, so a scale before a failing
        /// action already ran by the time the failure surfaced — the cluster was mutated but the
        /// apply was reported as failed. #578 review closed this: the whole plan is validated before
        /// anything is actuated, so an unsupported action ANYWHERE in the list — even after a scale —
        /// blocks every action in the plan, including the ones that would have succeeded.
        @Test
        void apply_scaleThenUnsupportedThenScale_rejectsWholePlan_noPartialTopologyWrite() {
            var actions = List.<DiffAction> of(new DiffAction.ScaleUp(sourceNameOrDefault("default"), NodeRole.CORE, 5, 7),
                                               new DiffAction.RemoveRole(sourceNameOrDefault("default"), NodeRole.WORKER, 2),
                                               new DiffAction.ScaleUp(sourceNameOrDefault("default"), NodeRole.WORKER, 0, 3));

            var result = applier.apply(actions).await();

            assertThat(result.isFailure()).isTrue();
            assertThat(topologyManager.setDesiredCountCalls())
                    .as("neither scale ran — the unsupported action in the middle rejects the whole plan up front")
                    .isEmpty();
        }
    }

    /// #578 review Testing Gap T1: `ClusterConfigApplier.NoTopologyManager` (`ManagementServer`'s
    /// currently-dead fallback for a node with no wired `ClusterTopologyManager`) must fail loudly,
    /// not silently succeed — the same defect shape #578 closes on the live applier path, hardened
    /// here in case a future conditional `clusterTopologyManager()` ever makes this fallback live.
    @Nested
    class NoTopologyManagerFallback {
        @Test
        void apply_withNoTopologyManager_failsWithServiceUnavailable() {
            var result = ClusterConfigApplier.NoTopologyManager.INSTANCE.apply(List.of(new DiffAction.ScaleUp(sourceNameOrDefault("default"),
                                                                                                              NodeRole.CORE,
                                                                                                              5,
                                                                                                              7)))
                                                                        .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(ClusterConfigError.ClusterTopologyManagerUnavailable.class,
                                                                              unavailable -> assertThat(unavailable.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)));
        }
    }

    /// Recording `ClusterTopologyManager` stub — only `setDesiredCount` matters for the applier;
    /// the rest is inert surface (same shape as `LeaderReconcilerTest.RecordingCtm`).
    private static final class RecordingTopologyManager implements ClusterTopologyManager {
        record ScaleCall(SourceName sourceName, NodeRole role, int count) {}

        private final List<ScaleCall> setDesiredCountCalls = new CopyOnWriteArrayList<>();

        List<ScaleCall> setDesiredCountCalls() {
            return List.copyOf(setDesiredCountCalls);
        }

        @Override
        public Promise<Unit> setDesiredCount(SourceName sourceName, NodeRole role, int count) {
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
        public Promise<Boolean> setAutoHealEnabled(boolean enabled, String reason) {
            return Promise.success(true);
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
