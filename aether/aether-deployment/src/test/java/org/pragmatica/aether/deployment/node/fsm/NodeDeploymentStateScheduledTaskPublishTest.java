// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned;
import org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.PeerObservedFaulty;
import org.pragmatica.consensus.topology.TransportObservation.PeerReconnected;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #1216 — THE SCHEDULED HALF'S VALID PATH, which no test reached before.
///
/// `NodeDeploymentStateScheduledTaskValidationTest` drives only INVALID schedule strings through the
/// activation-time gate, so the path a real slice takes — manifest entry → slice composite bound to a
/// [org.pragmatica.aether.resource.ScheduleConfig] → validated `scheduled-task/` Put submitted to
/// consensus before ACTIVE → registry → a started timer — had no pin at all: removing the
/// `publishScheduledTasks` hop from `performActivation` left every test in this module green.
///
/// Drives the real LOAD → ACTIVE chain through the FSM harness with a slice whose manifest declares a
/// scheduled binding and whose slice composite carries the ticketing demo's own section shape
/// (`interval`, an EMPTY `cron`, `execution_mode = "SINGLE"` — all three keys present, as the demo's
/// `resources.toml` says they must be), then hands the Put the node submitted to a real
/// [ScheduledTaskRegistry] + [ScheduledTaskManager] on a leader and asserts the timer starts.
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateScheduledTaskPublishTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:sweep-holds:1.0.0").unwrap();
    private static final Artifact BLUEPRINT = Artifact.artifact("org.example:ticketing-app:1.0.0").unwrap();
    private static final MethodName EXECUTE = MethodName.methodName("execute").unwrap();
    private static final String SECTION = "scheduling.sweep-holds";

    private static final String RESOURCES_TOML = """
        [scheduling.sweep-holds]
        interval = "60s"
        cron = ""
        execution_mode = "SINGLE"
        """;

    private RecordingClusterNode cluster;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;
    private Option<ScheduledTaskManager> manager = Option.none();

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        // The deploy-time fact the node needs before it lets the slice reach ACTIVE (#1068).
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(SLICE.base()),
                                                                        SliceTargetValue.sliceTargetValue(SLICE.version(),
                                                                                                          1,
                                                                                                          Option.some(BlueprintId.blueprintId(BLUEPRINT)))))));
        cluster = new RecordingClusterNode(SELF);
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> buildContext(fsm,
                                                                                                               ctxHolder,
                                                                                                               router,
                                                                                                               kvStore);

        harness = FsmTestHarness.harness("scheduled-task-publish-" + SELF.id(), factory);
    }

    @AfterEach
    void tearDown() {
        manager.onPresent(ScheduledTaskManager::stop);
    }

    @Test
    void validSchedule_publishesTheScheduledTaskPutBeforeActive_andStartsATimerOnTheLeader() {
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));
        await().atMost(5, TimeUnit.SECONDS)
             .untilAsserted(() -> assertThat(activeTransitions()).as("the slice reached ACTIVE")
                                            .isNotEmpty());
        var puts = scheduledTaskPuts();

        assertThat(puts).as("#1216: activation must submit exactly one scheduled-task/ Put for the manifest's one binding")
                  .hasSize(1);
        var put = puts.getFirst();
        var expectedKey = ScheduledTaskKey.scheduledTaskKey(SECTION, SLICE, EXECUTE);

        assertThat(put.key()).isEqualTo(expectedKey);
        assertThat(expectedKey.asString()).startsWith("scheduled-task/");
        assertThat(put.value()).as("the value is the bound ScheduleConfig, registered by this node, not paused")
                  .isEqualTo(ScheduledTaskValue.intervalTask(SELF, "60s", ExecutionMode.SINGLE));
        assertThat(commandIndexOf(put)).as("the Put is submitted BEFORE the node reports ACTIVE — a task the cluster learns of only after ACTIVE is a race, not a registration")
                  .isLessThan(activeTransitionIndex());
        // The other end of the same Put: what the cluster does once consensus delivers it.
        var registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        var leaderManager = new StubLeaderManager(SELF, true);
        var scheduler = ScheduledTaskManager.scheduledTaskManager(registry,
                                                                  new StubSliceInvoker(),
                                                                  SELF,
                                                                  _ -> {},
                                                                  _ -> Option.none(),
                                                                  leaderManager);

        manager = Option.some(scheduler);
        // The node's own key and value, re-typed to the narrow key the registry's notification carries.
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>((ScheduledTaskKey) put.key(), put.value()),
                                                   Option.none()));
        scheduler.onQuorumStateChange(ClusterStateNotification.active());
        assertThat(scheduler.activeTimerCount()).as("a SINGLE-mode task starts exactly one timer on the leader")
                  .isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private List<KVCommand.Put<AetherKey, ScheduledTaskValue>> scheduledTaskPuts() {
        return cluster.commands()
                      .stream()
                      .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                         && put.key() instanceof ScheduledTaskKey
                                         && put.value() instanceof ScheduledTaskValue)
                      .map(command -> (KVCommand.Put<AetherKey, ScheduledTaskValue>) command)
                      .toList();
    }

    /// The node's own state put (`NodeArtifactKey` → `NodeArtifactValue`) carrying ACTIVE.
    private List<NodeArtifactValue> activeTransitions() {
        return cluster.commands()
                      .stream()
                      .filter(NodeDeploymentStateScheduledTaskPublishTest::isActiveTransition)
                      .map(command -> (NodeArtifactValue)((KVCommand.Put<AetherKey, ?>) command).value())
                      .toList();
    }

    /// Index of the node's ACTIVE report in submission order, `-1` when it has not been submitted.
    private int activeTransitionIndex() {
        var commands = cluster.commands();

        return IntStream.range(0,
                               commands.size())
                        .filter(index -> isActiveTransition(commands.get(index)))
                        .findFirst()
                        .orElse(-1);
    }

    private static boolean isActiveTransition(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.Put<AetherKey, ?> put
               && put.key() instanceof NodeArtifactKey
               && put.value() instanceof NodeArtifactValue value
               && value.state() == SliceState.ACTIVE;
    }

    private int commandIndexOf(KVCommand<AetherKey> command) {
        return cluster.commands()
                      .indexOf(command);
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> kvStore) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                new ScheduledSliceStore(),
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                kvStore,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> activePutFor(NodeId node) {
        var key = NodeArtifactKey.nodeArtifactKey(node, SLICE);
        var value = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    /// A slice whose class implements a NAMED interface, so `readReactiveBindingsFromManifest` looks
    /// up `META-INF/slice/ScheduledSweepSlice.manifest`.
    interface ScheduledSweepSlice extends Slice {}

    private static final class SweepSlice implements ScheduledSweepSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of(SliceMethod.sliceMethod(EXECUTE,
                                                   (Unit unit) -> Promise.unitPromise(),
                                                   TypeToken.typeToken(Unit.class),
                                                   TypeToken.typeToken(Unit.class))
                                      .unwrap());
        }
    }

    /// Supplies the slice composite the way the loader does: the slice's own `resources.toml` text,
    /// parsed and flattened by [SliceStore#sliceIntrinsicLayer] — so the section shape under test is
    /// the one a real jar ships, not a hand-built map.
    private static final class ScheduledSliceStore implements SliceStore {
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();

        private static LoadedSlice loadedSlice(Artifact artifact) {
            Slice slice = new SweepSlice();

            return new LoadedSlice() {
                @Override
                public Artifact artifact() {
                    return artifact;
                }

                @Override
                public Slice slice() {
                    return slice;
                }
            };
        }

        @Override
        public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            var slice = loadedSlice(artifact);

            loadedSlices.add(slice);

            return Promise.success(slice);
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            return Promise.unitPromise();
        }

        @Override
        public Option<ConfigurationProvider> sliceComposite(Artifact artifact) {
            return SliceStore.sliceIntrinsicLayer(artifact, Option.some(RESOURCES_TOML));
        }
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> commands = new CopyOnWriteArrayList<>();

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        List<KVCommand<AetherKey>> commands() {
            return List.copyOf(commands);
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public TopologyManager topologyManager() {
            return stubTopologyManager(self);
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(List.of());
        }
    }

    /// Leadership is an input here, not a subject: SINGLE-mode eligibility is `leader` and nothing else.
    private static final class StubLeaderManager implements LeaderManager {
        private final NodeId self;
        private final boolean leader;

        StubLeaderManager(NodeId self, boolean leader) {
            this.self = self;
            this.leader = leader;
        }

        @Override
        public Option<NodeId> leader() {
            return leader
                   ? Option.some(self)
                   : Option.none();
        }

        @Override
        public boolean isLeader() {
            return leader;
        }

        @Override
        public Option<Long> currentLeaderEpoch() {
            return Option.none();
        }

        @Override
        public void onLeaderCommitted(NodeId leader) {}

        @Override
        public void triggerElection() {}

        @Override
        public void stop() {}

        @Override
        public void peerJoined(PeerJoined peerJoined) {}

        @Override
        public void peerDisconnected(PeerDisconnected peerDisconnected) {}

        @Override
        public void peerObservedFaulty(PeerObservedFaulty peerObservedFaulty) {}

        @Override
        public void peerReconnected(PeerReconnected peerReconnected) {}

        @Override
        public void selfShutdown(SelfShutdown selfShutdown) {}

        @Override
        public void watchClusterState(ClusterStateNotification clusterState) {}
    }

    /// The manager only asks the invoker whether it hosts the slice (ALL-mode) and fires through it;
    /// this test stops at the started timer, so neither is exercised.
    private static final class StubSliceInvoker implements SliceInvoker {
        @Override
        public Result<Unit> verifyEndpointExists(Artifact artifact, MethodName method) {
            return Result.unitResult();
        }

        @Override
        public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
            return Promise.unitPromise();
        }

        @Override
        public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
            return Promise.promise();
        }

        @Override
        public <R> Promise<R> invokeWithRetry(Artifact slice,
                                              MethodName method,
                                              Object request,
                                              TypeToken<R> responseType,
                                              int maxRetries) {
            return Promise.promise();
        }

        @Override
        public <R> Promise<R> invokeLocal(Artifact slice,
                                          MethodName method,
                                          Object request,
                                          TypeToken<R> responseType) {
            return Promise.promise();
        }

        @Override
        public void onInvokeResponse(InvokeResponse response) {}

        @Override
        public void onNodeRemoved(NodeRemoved event) {}

        @Override
        public void onNodeDecommissioned(NodeDecommissioned event) {}

        @Override
        public void onSelfShutdown(SelfShutdown event) {}

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public int pendingCount() {
            return 0;
        }

        @Override
        public Unit setFailureListener(SliceInvoker.SliceFailureListener listener) {
            return Unit.unit();
        }

        @Override
        public Unit registerAffinityResolver(Artifact artifact,
                                             MethodName method,
                                             SliceInvoker.CacheAffinityResolver resolver) {
            return Unit.unit();
        }

        @Override
        public Unit unregisterAffinityResolver(Artifact artifact, MethodName method) {
            return Unit.unit();
        }
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override
            public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override
            public int clusterSize() {
                return 1;
            }

            @Override
            public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override
            public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override
            public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private InvocationHandler stubInvocationHandler() {
        return new InvocationHandler() {
            @Override
            public void onInvokeRequest(InvokeRequest request) {}

            @Override
            public void registerSlice(Artifact artifact, SliceBridge bridge) {}

            @Override
            public void unregisterSlice(Artifact artifact) {}

            @Override
            public Option<SliceBridge> localSlice(Artifact artifact) {
                return Option.some(stubBridge());
            }

            @Override
            public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override
            public Option<InvocationMetricsCollector> metricsCollector() {
                return Option.none();
            }
        };
    }

    private static SliceBridge stubBridge() {
        return new SliceBridge() {
            @Override
            public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.success(new byte[0]);
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }

            @Override
            public List<String> methodNames() {
                return List.of(EXECUTE.name());
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
