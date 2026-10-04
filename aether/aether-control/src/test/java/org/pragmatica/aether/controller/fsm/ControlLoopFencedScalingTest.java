// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.ScalingConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #1864 — the autoscaler's count update was a plain `Put` of its CACHED `SliceTargetValue` with a new
/// instance count. A blueprint republish committed between the autoscaler's read and its write was then
/// silently reverted: the Put wrote back the old version, max and thresholds.
///
/// The cluster node here is a real fenced `KVStore`. It reproduces consensus ordering: the republish's
/// batch is ordered before the autoscaler's, and its notification reaches the control loop (as the KV
/// router delivers it) before the autoscaler's batch applies.
class ControlLoopFencedScalingTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:scaled-slice:1.0.0").unwrap();
    private static final SliceTargetKey KEY = SliceTargetKey.sliceTargetKey(SLICE.base());
    private static final int WINDOW = 3;

    private KVStore<AetherKey, AetherValue> store;
    private RacingClusterNode cluster;
    private ControlLoop controlLoop;
    private ControlLoopContext ctx;
    private final List<ScalingEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        seedLeader(store);
        cluster = new RacingClusterNode(store);
        controlLoop = buildControlLoop();
        ctx = ((ControlLoop.ControlLoopAdapter) controlLoop).ctx();
        ctx.setTopology(List.of(SELF,
                                NodeId.nodeId("n2").unwrap(),
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
    }

    @Test
    void aRepublishCommittedBetweenReadAndWrite_survives_andTheCountIsAppliedOnTheFreshValue() {
        var deployed = target(SLICE.version().bareVersion(), 8);
        var republished = target("2.0.0", 10);

        commitAndNotify(deployed);
        cluster.beforeNextApply(() -> commitAndNotify(republished));

        ctx.runEvaluationCycle();

        var committed = committedTarget();

        assertThat(committed.currentVersion().bareVersion())
                .as("the republish's version must survive the autoscaler's write")
                .isEqualTo("2.0.0");
        assertThat(committed.maxInstances()).as("and its maxInstances").isEqualTo(Option.some(10));
        assertThat(committed.targetInstances())
                .as("the scale-up is applied on the FRESH value, not dropped")
                .isEqualTo(4);
    }

    @Test
    void control_noRacingWrite_theScaleUpLands() {
        commitAndNotify(target(SLICE.version().bareVersion(), 8));

        ctx.runEvaluationCycle();

        assertThat(committedTarget().targetInstances()).isEqualTo(4);
        assertThat(events).as("one scaling event for the one committed change").hasSize(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void seedLeader(KVStore<AetherKey, AetherValue> store) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SELF, 1)))));
    }

    private SliceTargetValue target(String version, int maxInstances) {
        return SliceTargetValue.sliceTargetValue(org.pragmatica.aether.artifact.Version.version(version).unwrap(),
                                                 2,
                                                 1,
                                                 Option.none(),
                                                 Option.some(maxInstances),
                                                 Option.none(),
                                                 Option.none());
    }

    /// What consensus does for any committed SliceTarget write: apply it, then notify the feeder.
    private void commitAndNotify(SliceTargetValue value) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<>(KEY, value))));
        controlLoop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(KEY, value), Option.none()));
    }

    private SliceTargetValue committedTarget() {
        return store.get(KEY)
                    .map(SliceTargetValue.class::cast)
                    .unwrap();
    }

    private ControlLoop buildControlLoop() {
        ClusterController scaleUpByTwo = _ -> Promise.success(ControlDecisions.controlDecisions(new BlueprintChange.ScaleUp(SLICE,
                                                                                                                            2)));
        Consumer<ScalingEvent> sink = events::add;

        return ControlLoop.controlLoop(SELF,
                                       scaleUpByTwo,
                                       new ControlLoopContextAttributionTest.StubMetricsCollector(),
                                       Option.none(),
                                       cluster,
                                       TimeSpan.timeSpan(5_000).millis(),
                                       ControllerConfig.DEFAULT.withScalingConfig(smallWindowConfig()),
                                       sink);
    }

    private static ScalingConfig smallWindowConfig() {
        var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);

        weights.put(ScalingMetric.CPU, 0.0);
        weights.put(ScalingMetric.ACTIVE_INVOCATIONS, 0.6);
        weights.put(ScalingMetric.P95_LATENCY, 0.4);
        weights.put(ScalingMetric.ERROR_RATE, 0.0);

        return ScalingConfig.scalingConfig(WINDOW, 5_000L, 1.5, 0.5, weights).unwrap();
    }

    /// Applies each batch into the real fenced store and returns the applier's results. A racing write
    /// registered with [#beforeNextApply] is committed (and notified) first, as consensus would order it.
    private static final class RacingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final KVStore<AetherKey, AetherValue> store;
        private Runnable racing = () -> {};

        private RacingClusterNode(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
        }

        void beforeNextApply(Runnable racing) {
            this.racing = racing;
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException("unused");}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            var once = racing;

            racing = () -> {};
            once.run();

            return Promise.success(store.process(store.createBatch(batch)));
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {return null;}
        };
    }
}
