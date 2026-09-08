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
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #698 — `SliceTargetValue.owningBlueprint` was erased every time the autoscaler rebuilt the value.
///
/// `ControlLoopContext.applyScaling` constructed a *fresh* `SliceTargetValue` and hardcoded
/// `Option.none()` for the owner while carrying every other field forward. The write itself looked
/// correct in isolation, which is why this survived twelve days after #555: the loss is only
/// observable at the *consumer*, on a later leader restore, where
/// `ClusterDeploymentState.restoreSliceTarget` resolves `schemaRequired` through the owner and an
/// absent owner silently takes the historical default `true`. A slice deployed with
/// `schema_required = false` therefore came back after failover asserting schema was required.
///
/// The two nested classes below are deliberately split along that producer/consumer seam, because
/// testing either half alone is what missed the defect:
///
/// - `ProducerPreservesOwner` drives the real feeder (`ControlLoop.onSliceTargetPut`) and the real
///   evaluation cycle, so the owner makes the full KV → in-memory model → KV round trip through
///   production code. Nothing here is hand-constructed except the initial `SliceTargetValue` that a
///   deploy would have written.
/// - `OwnerSurvivesLeaderRestore` takes **the exact value the autoscaler just emitted** — not a
///   hand-built stand-in — seeds it into a real `ClusterDeploymentContext`'s KV store, and drives
///   the leader-restore path. This is the assertion that would have caught #698, and it is the one
///   the ticket's acceptance criteria ask for: the failover restore path, not merely the write.
class ControlLoopOwnerPreservationTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:owned-slice:1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:owning-app:1.0.0").unwrap();
    private static final int WINDOW = 3;

    private CapturingClusterNode cluster;
    private ControlLoop controlLoop;
    private ControlLoopContext ctx;

    @BeforeEach
    void setUp() {
        cluster = new CapturingClusterNode();
        controlLoop = buildControlLoop(scaleUpBy(2));
        ctx = ((ControlLoop.ControlLoopAdapter) controlLoop).ctx();
        ctx.setTopology(List.of(SELF,
                                WORKER,
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
    }

    /// Registers the slice the way production does — through the real `ControlLoop` feeder, from a
    /// `SliceTargetValue` carrying an owner, exactly as a blueprint deploy writes it. The feeder is
    /// the only writer of the context's blueprint map, so this is the sole route by which the
    /// autoscaler can learn an owner at all.
    private void registerOwnedSliceViaRealFeeder(Option<BlueprintId> owner) {
        var key = SliceTargetKey.sliceTargetKey(SLICE.base());
        var deployed = SliceTargetValue.sliceTargetValue(SLICE.version(),
                                                         2,
                                                         1,
                                                         owner,
                                                         Option.some(8),
                                                         Option.none(),
                                                         Option.none());

        controlLoop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(key, deployed), Option.none()));
    }

    /// The single `SliceTargetValue` the autoscaler wrote during this test's evaluation cycle.
    private SliceTargetValue emittedTarget() {
        var emitted = cluster.sliceTargets();

        assertThat(emitted).as("the evaluation cycle must have produced exactly one SliceTarget Put")
                           .hasSize(1);

        return emitted.getFirst();
    }

    @Nested
    class ProducerPreservesOwner {
        @Test
        void applyScaling_ownedSlice_carriesOwnerOntoTheScaledValue() {
            registerOwnedSliceViaRealFeeder(Option.some(OWNER));

            ctx.runEvaluationCycle();

            var scaled = emittedTarget();

            assertThat(scaled.targetInstances()).as("the cycle must actually have scaled, or the owner assertion is vacuous")
                                                .isEqualTo(4);
            assertThat(scaled.owningBlueprint()).as("#698: the autoscaler must not erase the slice's owner")
                                                .isEqualTo(Option.some(OWNER));
        }

        /// The other fields `applyScaling` carries forward, pinned alongside the owner so a future
        /// rebuild of this value cannot drop one of them the way it dropped the owner.
        @Test
        void applyScaling_ownedSlice_carriesOperatorOverridesOntoTheScaledValue() {
            registerOwnedSliceViaRealFeeder(Option.some(OWNER));

            ctx.runEvaluationCycle();

            assertThat(emittedTarget().maxInstances()).isEqualTo(Option.some(8));
        }

        /// Opposite polarity: a genuinely unowned slice must still come out unowned. Without this
        /// the owner assertion above would also pass against an implementation that fabricated an
        /// owner from somewhere, and `none()` must keep meaning "no owning blueprint".
        @Test
        void applyScaling_unownedSlice_leavesOwnerAbsent() {
            registerOwnedSliceViaRealFeeder(Option.none());

            ctx.runEvaluationCycle();

            assertThat(emittedTarget().owningBlueprint()).isEqualTo(Option.none());
        }
    }

    /// The consumer half, driven from the producer's actual output. `restoreSliceTarget` runs from
    /// `rebuildStateFromKVStore` on `Active` entry — the leader-failover path.
    @Nested
    class OwnerSurvivesLeaderRestore {
        private InMemoryKvStore kvStore;
        private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

        @BeforeEach
        void newDeploymentHarness() {
            var router = MessageRouter.mutable();

            kvStore = new InMemoryKvStore(router);

            var deploymentCluster = new RecordingClusterNode(SELF);
            LongSupplier clock = () -> 10_000_000L;
            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> new ClusterDeploymentContext(fsm,
                                                        SELF,
                                                        deploymentCluster,
                                                        kvStore,
                                                        router,
                                                        stubTopologyManager(SELF),
                                                        stubSchemaOrchestrator(),
                                                        () -> Set.of(SELF, WORKER),
                                                        () -> Set.of(SELF, WORKER),
                                                        Set::of,
                                                        Set.of(SELF, WORKER),
                                                        DeploymentAtomicity.ALL_OR_NOTHING,
                                                        3,
                                                        timeSpan(300).seconds(),
                                                        clock).dormant();

            harness = FsmTestHarness.harness("owner-restore-test-" + System.nanoTime(), factory);
        }

        /// The end-to-end assertion for #698. A blueprint declaring `schema_required = false` owns
        /// the slice; the autoscaler scales it; the leader restarts and restores from the KV store.
        /// Before the fix the restored blueprint asserted `schemaRequired = true`, because the
        /// autoscaler's Put had dropped the owner and `restoreSliceTarget` fell through to the
        /// unowned default.
        @Test
        void autoscalerOutput_restoredAfterFailover_keepsSchemaRequiredFalse() {
            seedOwningBlueprint(OWNER, false);
            registerOwnedSliceViaRealFeeder(Option.some(OWNER));

            ctx.runEvaluationCycle();

            var autoscalerOutput = emittedTarget();

            kvStore.put(SliceTargetKey.sliceTargetKey(SLICE.base()), autoscalerOutput);
            harness.dispatch(new Activate());

            assertThat(restoredSchemaRequired()).as("#698: a schema_required=false slice must not come back requiring schema"
                                                    + " after an autoscale event followed by a leader restore")
                                                .isEqualTo(false);
        }

        /// Opposite polarity, same path: proves the restore actually consults the owning blueprint
        /// rather than returning a constant. Without this pair, an implementation hardcoding `false`
        /// would pass the test above.
        @Test
        void autoscalerOutput_restoredAfterFailover_resolvesSchemaRequiredTrue() {
            seedOwningBlueprint(OWNER, true);
            registerOwnedSliceViaRealFeeder(Option.some(OWNER));

            ctx.runEvaluationCycle();

            kvStore.put(SliceTargetKey.sliceTargetKey(SLICE.base()), emittedTarget());
            harness.dispatch(new Activate());

            assertThat(restoredSchemaRequired()).isEqualTo(true);
        }

        private boolean restoredSchemaRequired() {
            var active = (ClusterDeploymentState.Active) harness.state();
            var scaledArtifact = SLICE.base().withVersion(SLICE.version());
            var blueprint = active.blueprints().get(scaledArtifact);

            assertThat(blueprint).as("expected a restored blueprint entry for %s", scaledArtifact)
                                 .isNotNull();

            return blueprint.schemaRequired();
        }

        /// Minimal but complete blueprint document — `id`, a non-empty `[[slices]]` and an explicit
        /// `[deployment].strategy` are all required before `schema_required` is read at all.
        /// Mirrors `SchemaRequiredResolutionTest.resourcesToml`, whose javadoc explains each.
        private void seedOwningBlueprint(BlueprintId owner, boolean schemaRequired) {
            var toml = """
                       id = "%s"

                       [[slices]]
                       artifact = "org.example:seed-slice:1.0.0"

                       [deployment]
                       strategy = "rolling"
                       schema_required = %s
                       """.formatted(owner.asString(), schemaRequired);
            var expanded = ExpandedBlueprint.expandedBlueprint(owner, List.of(), Option.some(toml));

            kvStore.put(AppBlueprintKey.appBlueprintKey(owner), AppBlueprintValue.appBlueprintValue(expanded));
        }
    }

    // --- control-loop fixtures ---

    private static ClusterController scaleUpBy(int additional) {
        return _ -> Promise.success(ControlDecisions.controlDecisions(new BlueprintChange.ScaleUp(SLICE, additional)));
    }

    /// Assembled through the production factory (`ControlLoop.controlLoop`), which builds the
    /// context, the FSM and the adapter the same way a node does — so the feeder driven below is the
    /// real one, not a test-assembled stand-in.
    private ControlLoop buildControlLoop(ClusterController controller) {
        Consumer<ScalingEvent> sink = _ -> {};

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

    static final class CapturingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> commands = new ArrayList<>();

        List<SliceTargetValue> sliceTargets() {
            return commands.stream()
                           .filter(KVCommand.Put.class::isInstance)
                           .map(command -> ((KVCommand.Put<?, ?>) command).value())
                           .filter(SliceTargetValue.class::isInstance)
                           .map(SliceTargetValue.class::cast)
                           .toList();
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException("unused");}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> toApply) {
            commands.addAll(toApply);

            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }

    // --- deployment-side fixtures (mirrors SchemaRequiredResolutionTest's, which is in another module) ---

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

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
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
