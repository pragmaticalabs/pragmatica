// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
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
import org.pragmatica.cluster.state.kvstore.KVStore;
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

        return CommunityMetricsSnapshot.communityMetricsSnapshot("community", WORKER, 0L, 1, slices, List.of());
    }

    private ControlLoopContext buildContext() {
        var config = ControllerConfig.DEFAULT.withScalingConfig(smallWindowConfig());
        var controller = DecisionTreeController.decisionTreeController(config);
        var ctxHolder = new AtomicReference<ControlLoopContext>();
        Consumer<ScalingEvent> sink = _ -> {};
        Function<Fsm<ControlLoopState, ClusterFsmEvent>, ControlLoopState> factory =
                fsm -> holdContext(ctxHolder, fsm, controller, config, sink);

        Fsm.fsm("attribution-test", SELF.id(), factory);

        return ctxHolder.get();
    }

    private ControlLoopState holdContext(AtomicReference<ControlLoopContext> ctxHolder,
                                         Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                                         DecisionTreeController controller,
                                         ControllerConfig config,
                                         Consumer<ScalingEvent> sink) {
        var context = new ControlLoopContext(fsm,
                                             SELF,
                                             controller,
                                             new StubMetricsCollector(),
                                             Option.none(),
                                             cluster,
                                             new StubKVStore(),
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

    static final class StubKVStore extends KVStore<AetherKey, AetherValue> {
        StubKVStore() {
            super(org.pragmatica.messaging.MessageRouter.mutable(),
                  StubSerializer.INSTANCE,
                  StubDeserializer.INSTANCE);
        }
    }

    enum StubSerializer implements org.pragmatica.serialization.Serializer {
        INSTANCE;

        @Override
        public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("unused");
        }
    }

    enum StubDeserializer implements org.pragmatica.serialization.Deserializer {
        INSTANCE;

        @Override
        public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
            throw new UnsupportedOperationException("unused");
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
