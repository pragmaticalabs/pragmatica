// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;
import org.pragmatica.cluster.node.ClusterNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #937 — an operator's slice placement was silently reset to `CORE_ONLY` on the first autoscale,
/// and the reset value was acted on.
///
/// `SliceTargetValue` carries nine components; the autoscaler held seven of them and rebuilt the
/// record from a factory overload in which `placement` is **not expressible** at all — it is
/// hardcoded to the default. So the operator's placement did not merely fail to be copied, it could
/// not have been copied. This is the same rebuild-from-a-subset root as #698 (owner) and #936
/// (`minInstances`), at the same expression; the fix derives the new value from the observed one, so
/// no component can go missing by omission.
///
/// The two nested classes are split along the producer/consumer seam because the producer half alone
/// cannot show why this matters. A reset placement is not bookkeeping: `ClusterDeploymentState`
/// feeds `effectivePlacement()` straight into `SliceAllocationEngine`, so a workload an operator
/// deliberately placed on worker nodes is **relocated** on its first scale event, with no command,
/// event or log saying so.
class ControlLoopPlacementPreservationTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:placed-slice:1.0.0").unwrap();
    /// A real [org.pragmatica.aether.config.PlacementPolicy] constant that is not the default and
    /// that `SliceAllocationEngine` routes differently from it — `WORKERS_ONLY` takes the worker
    /// branch and skips core allocation entirely, so the two are distinguishable by what they emit.
    private static final String OPERATOR_PLACEMENT = "WORKERS_ONLY";
    private static final String DEFAULT_PLACEMENT = "CORE_ONLY";
    private static final int WINDOW = 3;

    private ControlLoopOwnerPreservationTest.CapturingClusterNode cluster;
    private ControlLoop controlLoop;
    private ControlLoopContext ctx;
    private final AtomicReference<ControlDecisions> nextDecision = new AtomicReference<>(ControlDecisions.none());

    @BeforeEach
    void setUp() {
        cluster = new ControlLoopOwnerPreservationTest.CapturingClusterNode();
        controlLoop = buildControlLoop();
        ctx = ((ControlLoop.ControlLoopAdapter) controlLoop).ctx();
        ctx.setTopology(List.of(SELF,
                                WORKER,
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
    }

    @Nested
    class ProducerPreservesPlacement {
        @Test
        void applyScaling_operatorPlacedSlice_carriesThePlacementOntoTheScaledValue() {
            deploy(OPERATOR_PLACEMENT);

            var scaled = scaleUp();

            assertThat(scaled.targetInstances()).as("the cycle must actually have scaled, or the placement assertion is vacuous")
                                                .isEqualTo(4);
            assertThat(scaled.effectivePlacement()).as("#937: the autoscaler must not reset the operator's placement")
                                                   .isEqualTo(OPERATOR_PLACEMENT);
        }

        /// Opposite polarity: a slice on the default must still come out on the default. Without
        /// this, an implementation that hardcoded `WORKERS_ONLY` would pass the test above, and
        /// `CORE_ONLY` must keep meaning "placed on the core" rather than "placement was lost".
        @Test
        void applyScaling_defaultPlacedSlice_staysOnTheDefault() {
            deploy(DEFAULT_PLACEMENT);

            assertThat(scaleUp().effectivePlacement()).isEqualTo(DEFAULT_PLACEMENT);
        }
    }

    /// The consumer half, driven from the producer's actual output. This is the step #937 recorded
    /// as `[mechanism:]` — the chain from an operator-set placement through a real autoscale to a
    /// re-allocation had never been executed.
    @Nested
    class PlacementSurvivesToTheAllocationEngine {
        private ClusterDeploymentState.Active active;

        @BeforeEach
        void newDeploymentHarness() {
            var harness = deploymentHarness();

            harness.dispatch(new Activate());
            active = (ClusterDeploymentState.Active) harness.state();
            // The allocation pool's worker list is this set; a slice placed on workers can only be
            // distinguished from one on the core when a worker exists to place it on.
            active.workerNodes().add(WORKER);
            this.harness = harness;
        }

        private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

        /// The end-to-end assertion for #937. The value handed to the leader is the one the
        /// autoscaler actually emitted, not a hand-built stand-in — a hand-built value would state
        /// the intended behaviour rather than probe the real one, and the producer is precisely what
        /// was broken.
        @Test
        void autoscalerOutput_deliveredToTheLeader_allocatesUnderTheOperatorPlacement() {
            deploy(OPERATOR_PLACEMENT);

            deliver(scaleUp());

            assertThat(workerDirectives()).as("#937: a WORKERS_ONLY slice must reach the allocation engine as WORKERS_ONLY —"
                                              + " under the reset placement the engine takes the core branch and writes"
                                              + " no worker directive at all")
                                          .isNotEmpty()
                                          .allSatisfy(directive -> assertThat(directive.placement()).isEqualTo(OPERATOR_PLACEMENT));
        }

        /// Opposite polarity, same path: a genuinely core-placed slice must produce no worker
        /// directive. Without it the assertion above could be satisfied by an engine that wrote a
        /// worker directive unconditionally, which is the shape the reset defect would hide behind.
        @Test
        void coreOnlySlice_deliveredToTheLeader_writesNoWorkerDirective() {
            deploy(DEFAULT_PLACEMENT);

            deliver(scaleUp());

            assertThat(workerDirectives()).isEmpty();
        }

        private List<WorkerSliceDirectiveValue> workerDirectives() {
            return deploymentCluster.commands.stream()
                                             .filter(KVCommand.Put.class::isInstance)
                                             .map(command -> ((KVCommand.Put<?, ?>) command).value())
                                             .filter(WorkerSliceDirectiveValue.class::isInstance)
                                             .map(WorkerSliceDirectiveValue.class::cast)
                                             .toList();
        }

        private void deliver(SliceTargetValue value) {
            harness.dispatch(new SliceTargetPutReceived(valuePut(value)));
        }
    }

    // --- sequence helpers ---

    /// Registers the slice through the production feeder, from a `SliceTargetValue` carrying the
    /// operator's placement exactly as `SliceRoutes.applyScaleToExisting` writes it.
    private void deploy(String placement) {
        controlLoop.onSliceTargetPut(valuePut(SliceTargetValue.sliceTargetValue(SLICE.version(), 2, 1)
                                                              .withPlacement(placement)));
    }

    private SliceTargetValue scaleUp() {
        nextDecision.set(ControlDecisions.controlDecisions(new BlueprintChange.ScaleUp(SLICE, 2)));
        ctx.runEvaluationCycle();

        var emitted = cluster.sliceTargets();

        assertThat(emitted).as("the evaluation cycle must have produced exactly one SliceTarget Put")
                           .hasSize(1);

        return emitted.getFirst();
    }

    private static ValuePut<SliceTargetKey, SliceTargetValue> valuePut(SliceTargetValue value) {
        return new ValuePut<>(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(SLICE.base()), value), Option.none());
    }

    // --- control-loop fixtures (mirrors ControlLoopOwnerPreservationTest's) ---

    private ControlLoop buildControlLoop() {
        Consumer<ScalingEvent> sink = _ -> {};
        ClusterController controller = _ -> Promise.success(nextDecision.get());

        return ControlLoop.controlLoop(SELF,
                                       controller,
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

    // --- deployment-side fixtures (mirrors ControlLoopOwnerPreservationTest's, same package) ---

    private final RecordingClusterNode deploymentCluster = new RecordingClusterNode(SELF);

    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> deploymentHarness() {
        var router = MessageRouter.mutable();
        var kvStore = new InMemoryKvStore(router);
        LongSupplier clock = () -> 10_000_000L;
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    deploymentCluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF),
                                                    () -> Set.of(SELF),
                                                    Set::of,
                                                    Set.of(SELF),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();

        return FsmTestHarness.harness("placement-test-" + System.nanoTime(), factory);
    }

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> baseline(String datasourceName, int version) {return Promise.success(Unit.unit());}
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));}

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {return 2;}

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}

            @Override public TimeSpan pingInterval() {return timeSpan(5).seconds();}

            @Override public TimeSpan helloTimeout() {return timeSpan(5).seconds();}

            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}

            @Override public List<NodeId> topology() {return List.of(self);}
        };
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(Collections.emptyList());
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
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
