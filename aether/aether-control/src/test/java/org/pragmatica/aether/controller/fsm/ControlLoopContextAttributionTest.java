// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.ScalingConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncCollector.MetricsSnapshot;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.PerSliceMetrics;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.PeerObservationBuffer;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;


import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Leader-side per-artifact attribution (#422/#423). Drives real per-artifact metric windows from
/// ingested community snapshots and asserts a load spike scoped to ONE artifact scales only that
/// artifact — the idle artifact never receives a SliceTarget write.
class ControlLoopContextAttributionTest {

    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final Artifact HOT = Artifact.artifact("org.test:hot:1.0.0").unwrap();
    private static final Artifact IDLE = Artifact.artifact("org.test:idle:1.0.0").unwrap();
    private static final int WINDOW = 3;

    private CapturingClusterNode cluster;
    private ControlLoopContext ctx;

    @BeforeEach
    void setUp() {
        cluster = new CapturingClusterNode();
        ctx = buildContext();
        ctx.putBlueprint(HOT, 2, 1);
        ctx.putBlueprint(IDLE, 2, 1);
        ctx.setTopology(List.of(SELF, WORKER, NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(), NodeId.nodeId("n5").unwrap()));
    }

    @Test
    void runEvaluationCycle_loadSpikeOnHotArtifact_scalesOnlyHot() {
        fillWindows();

        ingest(100, 2);
        ctx.runEvaluationCycle();

        var scaledBases = cluster.putBases();
        assertThat(scaledBases).contains(HOT.base());
        assertThat(scaledBases).doesNotContain(IDLE.base());
    }

    @Test
    void runEvaluationCycle_steadyLoad_scalesNothing() {
        fillWindows();

        ingest(2, 2);
        ctx.runEvaluationCycle();

        assertThat(cluster.putBases()).isEmpty();
    }

    /// Review MAJOR: a departed node's frozen per-artifact metrics must be evicted, not re-summed
    /// every cycle. Only WORKER feeds HOT here; after WORKER leaves, HOT's active load must fall to
    /// zero so a scaled-up slice can scale back down — proving the frozen window contribution is gone
    /// (without eviction the stale active=100 would persist and no scale-down could ever occur).
    @Test
    void onNodeDeparted_evictsDepartedNodeMetrics_enablesScaleDown() {
        ctx.putBlueprint(HOT, 3, 1);

        for (int i = 0; i < WINDOW; i++) {
            ingest(100, 0);
            ctx.runEvaluationCycle();
        }

        assertThat(cluster.putBases()).describedAs("steady high load triggers no scaling").isEmpty();

        ctx.onNodeDeparted(WORKER, List.of(SELF, NodeId.nodeId("n3").unwrap(),
                                           NodeId.nodeId("n4").unwrap(), NodeId.nodeId("n5").unwrap()));
        ctx.runEvaluationCycle();

        assertThat(cluster.putBases()).contains(HOT.base());
        assertThat(cluster.lastTargetInstances()).isEqualTo(2);
    }

    /// #424 leader cap: `maxInstances` bounds the autoscaler's requested instance count BEFORE the
    /// cluster-size cap. A stub controller emits a fixed ScaleUp so the cap arithmetic is isolated
    /// from metric-window composite scoring.
    @Nested
    class MaxInstancesCap {
        private static final List<NodeId> FIVE_NODES = List.of(SELF,
                                                               WORKER,
                                                               NodeId.nodeId("n3").unwrap(),
                                                               NodeId.nodeId("n4").unwrap(),
                                                               NodeId.nodeId("n5").unwrap());

        @Test
        void runEvaluationCycle_maxInstancesBelowClusterSize_capsToMaxNotClusterSize() {
            cluster = new CapturingClusterNode();
            var capCtx = buildContext(scaleUpBy(2));

            capCtx.putBlueprint(HOT, 2, 1, Option.some(3), Option.none(), Option.none());
            capCtx.setTopology(FIVE_NODES);

            capCtx.runEvaluationCycle();

            assertThat(cluster.putBases()).contains(HOT.base());
            assertThat(cluster.lastTargetInstances()).isEqualTo(3);
        }

        @Test
        void runEvaluationCycle_noMaxInstances_capsToClusterSizeOnly() {
            cluster = new CapturingClusterNode();
            var capCtx = buildContext(scaleUpBy(2));

            capCtx.putBlueprint(HOT, 2, 1);
            capCtx.setTopology(FIVE_NODES);

            capCtx.runEvaluationCycle();

            assertThat(cluster.putBases()).contains(HOT.base());
            assertThat(cluster.lastTargetInstances()).isEqualTo(4);
        }

        private ClusterController scaleUpBy(int additional) {
            return _ -> Promise.success(ControlDecisions.controlDecisions(new BlueprintChange.ScaleUp(HOT, additional)));
        }
    }

    /// #425 per-slice decision snapshot: a real cap reduction emits [ScalingEvent.ScaleCapped] to the
    /// event sink AND records outcome CAPPED with the bound-specific guard; a clean scale-up records
    /// SCALED_UP. A stub controller emits a fixed ScaleUp so the recording is isolated from
    /// metric-window composite scoring.
    @Nested
    class DecisionSnapshot {
        private static final List<NodeId> FIVE_NODES = List.of(SELF,
                                                               WORKER,
                                                               NodeId.nodeId("n3").unwrap(),
                                                               NodeId.nodeId("n4").unwrap(),
                                                               NodeId.nodeId("n5").unwrap());

        private final List<ScalingEvent> events = new ArrayList<>();

        @Test
        void runEvaluationCycle_maxInstancesCapReducesRequest_emitsScaleCappedAndRecordsCapped() {
            cluster = new CapturingClusterNode();
            var capCtx = buildContext(scaleUpBy(3), events::add);

            capCtx.putBlueprint(HOT, 2, 1, Option.some(3), Option.none(), Option.none());
            capCtx.setTopology(FIVE_NODES);

            capCtx.runEvaluationCycle();

            var capped = events.stream()
                               .filter(event -> event instanceof ScalingEvent.ScaleCapped)
                               .map(event -> (ScalingEvent.ScaleCapped) event)
                               .findFirst()
                               .orElseThrow();

            assertThat(capped.artifact()).isEqualTo(HOT);
            assertThat(capped.requestedInstances()).isEqualTo(5);
            assertThat(capped.cappedAtInstances()).isEqualTo(3);
            assertThat(capped.reason()).isEqualTo("max-instances");

            var decision = capCtx.scalingDecisions().get(HOT);

            assertThat(decision.outcome()).isEqualTo(ScalingDecisionRecord.Outcome.CAPPED);
            assertThat(decision.guard()).isEqualTo(ScalingDecisionRecord.Guard.MAX_INSTANCES);
            assertThat(decision.requestedInstances()).isEqualTo(5);
            assertThat(decision.cappedInstances()).isEqualTo(3);
        }

        @Test
        void runEvaluationCycle_cleanScaleUp_recordsScaledUpWithNoGuard() {
            cluster = new CapturingClusterNode();
            var capCtx = buildContext(scaleUpBy(2), events::add);

            capCtx.putBlueprint(HOT, 2, 1);
            capCtx.setTopology(FIVE_NODES);

            capCtx.runEvaluationCycle();

            assertThat(events).noneMatch(event -> event instanceof ScalingEvent.ScaleCapped);

            var decision = capCtx.scalingDecisions().get(HOT);

            assertThat(decision.outcome()).isEqualTo(ScalingDecisionRecord.Outcome.SCALED_UP);
            assertThat(decision.guard()).isEqualTo(ScalingDecisionRecord.Guard.NONE);
            assertThat(decision.requestedInstances()).isEqualTo(4);
            assertThat(decision.cappedInstances()).isEqualTo(4);
        }

        private ClusterController scaleUpBy(int additional) {
            return _ -> Promise.success(ControlDecisions.controlDecisions(new BlueprintChange.ScaleUp(HOT, additional)));
        }
    }

    private void fillWindows() {
        for (int i = 0; i < WINDOW; i++) {
            ingest(2, 2);
            ctx.runEvaluationCycle();
        }
    }

    private void ingest(long hotActive, long idleActive) {
        ctx.storeCommunitySnapshot(snapshot(hotActive, idleActive));
    }

    private static CommunityMetricsSnapshot snapshot(long hotActive, long idleActive) {
        var slices = List.of(PerSliceMetrics.perSliceMetrics(HOT, hotActive, 0.0, 0.0, hotActive),
                             PerSliceMetrics.perSliceMetrics(IDLE, idleActive, 0.0, 0.0, idleActive));

        return CommunityMetricsSnapshot.communityMetricsSnapshot("community", WORKER, 1, slices);
    }

    private ControlLoopContext buildContext() {
        return buildContext(DecisionTreeController.decisionTreeController(ControllerConfig.DEFAULT.withScalingConfig(smallWindowConfig())));
    }

    private ControlLoopContext buildContext(ClusterController controller) {
        return buildContext(controller, _ -> {});
    }

    private ControlLoopContext buildContext(ClusterController controller, Consumer<ScalingEvent> sink) {
        var config = ControllerConfig.DEFAULT.withScalingConfig(smallWindowConfig());
        var ctxHolder = new AtomicReference<ControlLoopContext>();
        Function<Fsm<ControlLoopState, ClusterFsmEvent>, ControlLoopState> factory =
                fsm -> holdContext(ctxHolder, fsm, controller, config, sink);

        Fsm.fsm("attribution-test", SELF.id(), factory);

        return ctxHolder.get();
    }

    private ControlLoopState holdContext(AtomicReference<ControlLoopContext> ctxHolder,
                                         Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                                         ClusterController controller,
                                         ControllerConfig config,
                                         Consumer<ScalingEvent> sink) {
        var context = new ControlLoopContext(fsm,
                                             SELF,
                                             controller,
                                             new StubMetricsCollector(),
                                             Option.none(),
                                             cluster,
                                             TimeSpan.timeSpan(5_000).millis(),
                                             config,
                                             sink);

        ctxHolder.set(context);

        return context.dormant();
    }

    private static ScalingConfig smallWindowConfig() {
        var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);

        weights.put(ScalingMetric.CPU, 0.0);
        weights.put(ScalingMetric.ACTIVE_INVOCATIONS, 0.6);
        weights.put(ScalingMetric.P95_LATENCY, 0.4);
        weights.put(ScalingMetric.ERROR_RATE, 0.0);

        return ScalingConfig.scalingConfig(WINDOW, 5_000L, 1.5, 0.5, weights).unwrap();
    }

    // --- Minimal stub collaborators ---

    static final class CapturingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> commands = new ArrayList<>();

        List<Object> putBases() {
            return commands.stream()
                           .filter(command -> command instanceof KVCommand.Put)
                           .map(command -> ((KVCommand.Put<?, ?>) command).key())
                           .filter(key -> key instanceof SliceTargetKey)
                           .map(key -> (Object) ((SliceTargetKey) key).artifactBase())
                           .toList();
        }

        int lastTargetInstances() {
            return commands.stream()
                           .filter(command -> command instanceof KVCommand.Put)
                           .map(command -> ((KVCommand.Put<?, ?>) command).value())
                           .filter(value -> value instanceof AetherValue.SliceTargetValue)
                           .map(value -> ((AetherValue.SliceTargetValue) value).targetInstances())
                           .reduce((first, second) -> second)
                           .orElse(-1);
        }

        @Override public NodeId self() { return SELF; }
        @Override public TopologyManager topologyManager() { throw new UnsupportedOperationException("unused"); }
        @Override public Promise<Unit> start() { return Promise.unitPromise(); }
        @Override public Promise<Unit> stop() { return Promise.unitPromise(); }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> toApply) {
            commands.addAll(toApply);

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }

    static final class StubMetricsCollector implements ClusterSyncCollector {
        @Override public Map<String, Double> collectLocal() { return Map.of(); }
        @Override public void recordCall(MethodName method, long durationMs) {}
        @Override public void recordCustom(String name, double value) {}
        @Override public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {}
        @Override public Map<NodeId, Map<String, Double>> allMetrics() { return Map.of(); }
        @Override public Map<String, Double> metricsFor(NodeId nodeId) { return Map.of(); }
        @Override public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() { return Map.of(); }
        @Override public void removeNode(NodeId nodeId) {}
        @Override public void onMembershipDecision(MembershipDecision decision) {}
        @Override public void onClusterSyncPing(ClusterSyncPing ping) {}
        @Override public void onClusterSyncPong(ClusterSyncPong pong) {}
        @Override public long observedRabiaTerm() { return 0L; }
        @Override public Epoch observedEpoch() { throw new UnsupportedOperationException("unused"); }
        @Override public List<CommunityReport> collectCommunityReports() { return List.of(); }
        @Override public void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier) {}
        @Override public void addPongListener(Consumer<ClusterSyncPong> listener) {}
        @Override public void setPongSignalFan(ClusterSyncPongSignalFan fan) {}
        @Override public void setPeerObservationBuffer(PeerObservationBuffer buffer) {}
        @Override public void emitPeriodicConnectivity(Set<NodeId> topology, Set<NodeId> connected, NodeId self, long nowMs) {}
    }
}
