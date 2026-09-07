// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.AppBlueprintPutReceived;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentContext;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentState;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #916 — the unload/activate crossing, pinned deterministically across BOTH halves of the defect.
///
/// The race is: an ACTIVATE for an artifact arrives on a node whose `SliceStore` no longer holds
/// the slice, because an unload issued for the previous deployment of the same artifact is still
/// in flight. This class reproduces that with an EMPTY `SliceStore` — no load, no CPU pressure,
/// no timing window — which is the same observable the racing unload produces.
///
/// Both FSM halves run for real and are wired together by replaying the node's OWN emitted
/// consensus command into the cluster leader, rather than by rebuilding a value the test invented:
///
///   1. the node FSM's `processStateTransition(key, ACTIVATE)` finds nothing in the store, raises
///      the production cause, and emits a `Put` of a FAILED [NodeArtifactValue];
///   2. that exact `Put` is handed to the cluster FSM as a `NodeArtifactPutReceived`.
///
/// So a revert of EITHER production hunk — the typed cause in `NodeDeploymentState`, or the
/// `Intermittent.SliceNotInStore` record itself — changes what step 1 emits and turns step 2's
/// assertion red. A test that constructed the failure value itself would stay green on that revert
/// and pin nothing about the wiring.
///
/// [#unloadActivateCrossing_isFatalIsFalse_andBlueprintIsNotRolledBack] is the fix.
/// [#genuinelyUnclassifiedCause_stillRollsBackTheBlueprint] is its positive control: the same
/// crossing driven with an untyped cause still reaches `classify`'s permanent catch-all and still
/// rolls back, which proves the rollback assertion can fail and that the #916 ruling to KEEP the
/// catch-all permanent is in force.
class ActivationRaceNotFatalTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();

    private RecordingClusterNode nodeSideCluster;
    private RecordingClusterNode leaderSideCluster;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> nodeHarness;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness;

    @BeforeEach
    void setUp() {
        nodeSideCluster = new RecordingClusterNode(SELF);
        leaderSideCluster = new RecordingClusterNode(SELF);
        nodeHarness = nodeHarness(nodeSideCluster);
        leaderHarness = leaderHarness(leaderSideCluster);
    }

    @Test
    void unloadActivateCrossing_isFatalIsFalse_andBlueprintIsNotRolledBack() {
        var expanded = blueprint();
        var emitted = driveActivationAgainstEmptyStore();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOf(emitted)));

        // Assertion order is deliberate: the two load-bearing claims come first, so a revert of the
        // production hunk reports the DEFECT (fatal, rolled back) rather than tripping first on the
        // message-text check, which only identifies the cause and is not what the ticket is about.
        assertThat(emitted.fatal())
                .as("#916: the slice being absent while an unload is in flight is a retryable "
                    + "crossing, so the node must NOT mark it fatal — a fatal report is what made "
                    + "the leader roll the blueprint back")
                .isFalse();
        assertThat(leaderSideCluster.removeKeys())
                .as("#916 acceptance: the blueprint must survive the crossing and stay deployable "
                    + "(ALL_OR_NOTHING must not roll it back)")
                .doesNotContain(AppBlueprintKey.appBlueprintKey(expanded.id()));
        assertThat(emitted.state()).as("the node must report the activation as FAILED")
                                   .isEqualTo(SliceState.FAILED);
        assertThat(emitted.failureReason()
                          .or(""))
                .as("the reported reason must name the store absence, not a generic wrapper")
                .contains("not present in SliceStore");
    }

    @Test
    void genuinelyUnclassifiedCause_stillRollsBackTheBlueprint() {
        var expanded = blueprint();
        var unclassified = NodeArtifactValue.failedNodeArtifactValue(Causes.cause("something nobody typed"));

        assertThat(unclassified.fatal())
                .as("#916 ruling: classify's catch-all stays PERMANENT — an unrecognised cause is "
                    + "still fatal, and this control fails the moment that default is flipped")
                .isTrue();

        leaderHarness.dispatch(new AppBlueprintPutReceived(appBlueprintPut(expanded)));
        leaderHarness.dispatch(new NodeArtifactPutReceived(replayOf(unclassified)));

        assertThat(leaderSideCluster.removeKeys())
                .as("positive control for the assertion above: a genuinely fatal failure DOES remove "
                    + "the blueprint, so a green result in the other test is not a dead assertion")
                .contains(AppBlueprintKey.appBlueprintKey(expanded.id()));
    }

    /// NOTE 2 from review round 1 — `SLICE_NOT_LOADED_FOR_REGISTRATION` was typed by #916 but
    /// unpinned: the other tests drive only `handleActivating`'s branch, so reverting that second
    /// constant to a plain `Causes.forOneValue` left them green.
    ///
    /// This drives the OTHER raise site, and it does so through the scenario the constant's own doc
    /// claims: the slice is present when `handleActivating` looks for it, activation succeeds, and
    /// it is gone by the time `registerSliceForInvocation` looks again. The store below evicts on
    /// `activateSlice` precisely to place the eviction in that window.
    @Test
    void sliceEvictedBeforeInvocationRegistration_isReportedIntermittent() {
        var evicting = new EvictOnActivateSliceStore();
        var harness = nodeHarness(nodeSideCluster, evicting);

        harness.dispatch(new QuorumEstablished());

        var active = (NodeDeploymentState.Active) harness.state();

        active.processStateTransition(SliceNodeKey.sliceNodeKey(SLICE, SELF), SliceState.ACTIVATE);

        var emitted = lastFailedNodeArtifactValue();

        assertThat(evicting.activateCalls)
                .as("precondition: the run must have gone THROUGH activation, otherwise it took "
                    + "handleActivating's branch and pins the other constant all over again")
                .isEqualTo(1);
        assertThat(emitted.fatal())
                .as("#916: eviction between the activation lookup and the registration lookup is the "
                    + "same retryable crossing, so this raise site must not be fatal either")
                .isFalse();
        assertThat(emitted.failureReason()
                          .or(""))
                .as("the failure must come from the invocation-registration lookup, not the "
                    + "activation lookup — those are different constants and only one is under test")
                .contains("invocation registration");
    }

    /// Drives the real node FSM through the ACTIVATE transition with an empty `SliceStore` and
    /// returns the [NodeArtifactValue] the node actually pushed to consensus.
    private NodeArtifactValue driveActivationAgainstEmptyStore() {
        nodeHarness.dispatch(new QuorumEstablished());

        var active = (NodeDeploymentState.Active) nodeHarness.state();

        active.processStateTransition(SliceNodeKey.sliceNodeKey(SLICE, SELF), SliceState.ACTIVATE);

        return nodeSideCluster.commands.stream()
                                       .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                          && put.key() instanceof NodeArtifactKey)
                                       .map(command -> (NodeArtifactValue) ((KVCommand.Put<AetherKey, ?>) command).value())
                                       .filter(value -> value.state() == SliceState.FAILED)
                                       .reduce((first, second) -> second)
                                       .orElseThrow(() -> new AssertionError("node FSM emitted no FAILED NodeArtifactValue"));
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> replayOf(NodeArtifactValue value) {
        var key = NodeArtifactKey.nodeArtifactKey(SELF, SLICE);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValuePut<AppBlueprintKey, AppBlueprintValue> appBlueprintPut(ExpandedBlueprint expanded) {
        var key = AppBlueprintKey.appBlueprintKey(expanded.id());

        return new ValuePut<>(new KVCommand.Put<>(key, AppBlueprintValue.appBlueprintValue(expanded)), Option.none());
    }

    private static ExpandedBlueprint blueprint() {
        var id = BlueprintId.blueprintId("com.example:app:1.0.0").unwrap();
        var slice = ResolvedSlice.resolvedSlice(SLICE, 3, false).unwrap();

        return ExpandedBlueprint.expandedBlueprint(id, List.of(slice));
    }

    private static FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> nodeHarness(ClusterNode<KVCommand<AetherKey>> cluster) {
        return nodeHarness(cluster, emptySliceStore());
    }

    private static FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> nodeHarness(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                                    SliceStore sliceStore) {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> new NodeDeploymentContext(fsm,
                                                 SELF,
                                                 new NodeAddress("localhost", 9000),
                                                 sliceStore,
                                                 SliceActionConfig.sliceActionConfig(),
                                                 SliceCodec.sliceCodec(List.of()),
                                                 cluster,
                                                 kvStore,
                                                 stubInvocationHandler(),
                                                 router,
                                                 Option.none(),
                                                 Option.none(),
                                                 timeSpan(120_000).millis(),
                                                 timeSpan(2_000).millis()).dormant();

        return FsmTestHarness.harness("activation-race-node-" + SELF.id(), factory);
    }

    private static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> leaderHarness(ClusterNode<KVCommand<AetherKey>> cluster) {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        LongSupplier clock = () -> 10_000_000L;
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, NODE_A),
                                                    () -> Set.of(SELF, NODE_A),
                                                    Set::of,
                                                    Set.of(SELF, NODE_A),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        var harness = FsmTestHarness.<ClusterDeploymentState, ClusterFsmEvent>harness("activation-race-leader-" + SELF.id(),
                                                                                      factory);

        harness.dispatch(new Activate());

        return harness;
    }

    private NodeArtifactValue lastFailedNodeArtifactValue() {
        return nodeSideCluster.commands.stream()
                                       .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                          && put.key() instanceof NodeArtifactKey)
                                       .map(command -> (NodeArtifactValue) ((KVCommand.Put<AetherKey, ?>) command).value())
                                       .filter(value -> value.state() == SliceState.FAILED)
                                       .reduce((first, second) -> second)
                                       .orElseThrow(() -> new AssertionError("node FSM emitted no FAILED NodeArtifactValue"));
    }

    /// A store that holds the slice for the activation lookup and drops it the moment activation
    /// completes, putting the eviction in the window between `handleActivating`'s `findLoadedSlice`
    /// and `registerSliceForInvocation`'s.
    private static final class EvictOnActivateSliceStore implements SliceStore {
        private volatile boolean evicted = false;
        private volatile int activateCalls = 0;

        private final LoadedSlice entry = new LoadedSlice() {
            @Override public Artifact artifact() {
                return SLICE;
            }

            @Override public org.pragmatica.aether.slice.Slice slice() {
                return List::of;
            }
        };

        @Override public List<LoadedSlice> loaded() {
            return evicted ? List.of() : List.of(entry);
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return Promise.success(entry);
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            activateCalls++;
            evicted = true;

            return Promise.success(entry);
        }

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Promise.success(entry);
        }

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {
            return Promise.unitPromise();
        }

        @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
            return Option.none();
        }
    }

    /// The store observable the in-flight unload produces: the artifact is simply not there.
    private static SliceStore emptySliceStore() {        return new SliceStore() {
            @Override public List<LoadedSlice> loaded() {
                return List.of();
            }

            @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
                return Causes.cause("not used by this test").promise();
            }

            @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
                return Causes.cause("not used by this test").promise();
            }

            @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
                return Causes.cause("not used by this test").promise();
            }

            @Override public Promise<Unit> unloadSlice(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
                return Option.none();
            }
        };
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        private RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(Collections.emptyList());
        }

        private List<AetherKey> removeKeys() {
            synchronized (commands) {
                return commands.stream()
                               .filter(command -> command instanceof KVCommand.Remove<AetherKey>)
                               .map(KVCommand::key)
                               .toList();
            }
        }


    }

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 2;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static org.pragmatica.aether.invoke.InvocationHandler stubInvocationHandler() {
        return new org.pragmatica.aether.invoke.InvocationHandler() {
            @Override public void onInvokeRequest(org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, org.pragmatica.aether.slice.SliceBridge bridge) {}

            @Override public void unregisterSlice(Artifact artifact) {}

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> localSlice(Artifact artifact) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector> metricsCollector() {
                return Option.none();
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
