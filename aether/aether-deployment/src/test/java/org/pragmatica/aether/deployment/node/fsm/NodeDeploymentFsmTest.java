// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
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
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FSM-level tests for the [`org.pragmatica.aether.deployment.node.NodeDeploymentManager`] state
/// machine. Exercises the explicit `Dormant → Active → Stopped` lifecycle via [`FsmTestHarness`],
/// independent from the public `NodeDeploymentManager` surface.
class NodeDeploymentFsmTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();

    private NodeDeploymentContext ctx;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        SliceStore sliceStore = stubSliceStore();
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, sliceStore);
        harness = FsmTestHarness.harness("node-deployment-fsm-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> kvStore,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             SliceStore sliceStore) {
        var context = new NodeDeploymentContext(fsm,
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
                                                timeSpan(2_000).millis());
        ctxHolder.set(context);
        return context.dormant();
    }

    @Nested
    class HappyPath {
        @Test
        void dormant_QuorumEstablished_becomesActive() {
            harness.dispatch(new QuorumEstablished());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);
            assertThat(ctx.isActive()).isTrue();
        }

        @Test
        void active_NodeArtifactPutForThisNode_doesNotChangeFsmState() {
            harness.dispatch(new QuorumEstablished());
            var putEvent = buildLoadArtifactPut();
            harness.dispatch(new NodeArtifactPutReceived(putEvent));
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);
        }

        @Test
        void active_QuorumDisappeared_returnsToDormant() {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new QuorumDisappeared());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);
        }

        @Test
        void full_lifecycle_traversesAllStates() {
            harness.dispatch(new QuorumEstablished());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

            harness.dispatch(new NodeArtifactPutReceived(buildLoadArtifactPut()));
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

            harness.dispatch(new QuorumDisappeared());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);

            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Stopped.class);
        }
    }

    @Nested
    class CasContention {
        @Test
        void eightConcurrentQuorumEstablished_singleWinnerAdvancesToActive() throws Exception {
            var events = new ArrayList<ClusterFsmEvent>();
            for (int i = 0; i < 8; i++) {
                events.add(new QuorumEstablished());
            }
            harness.dispatchConcurrently(events);
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);
            var dormantToActive = harness.transitions().stream()
                    .filter(t -> t.from() instanceof NodeDeploymentState.Dormant
                                 && t.to() instanceof NodeDeploymentState.Active)
                    .count();
            assertThat(dormantToActive)
                    .as("exactly one winner advances Dormant → Active")
                    .isEqualTo(1);
        }
    }

    @Nested
    class IgnoredEvents {
        @Test
        void nodeArtifactPut_inDormant_isIgnoredAndStateUnchanged() {
            harness.dispatch(new NodeArtifactPutReceived(buildLoadArtifactPut()));
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);
            assertThat(harness.ignored()).isNotEmpty();
        }

        @Test
        void quorumDisappeared_inDormant_isIgnored() {
            harness.dispatch(new QuorumDisappeared());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);
            assertThat(harness.ignored()).isNotEmpty();
        }

        @Test
        void shutdown_inStopped_isIgnored() {
            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Stopped.class);
            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Stopped.class);
        }
    }

    /// Theme H clock-injection coverage: confirms the full-arity `NodeDeploymentContext`
    /// constructor wires an injected `LongSupplier` through to `nowMs()`.
    @Nested
    class ClockInjection {
        @Test
        void nowMs_returnsInjectedClockValue_notSystemClock() {
            var injected = new AtomicLong(42_000L);
            LongSupplier clock = injected::get;
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
            SliceStore sliceStore = stubSliceStore();
            var ctxHolder = new AtomicReference<NodeDeploymentContext>();
            Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                    fsm -> {
                        var ctx = new NodeDeploymentContext(fsm,
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
                                                            timeSpan(2_000).millis(),
                                                            clock);
                        ctxHolder.set(ctx);
                        return ctx.dormant();
                    };
            FsmTestHarness.harness("node-deployment-clock-test-" + SELF.id(), factory);

            var injectedCtx = ctxHolder.get();
            assertThat(injectedCtx.nowMs()).isEqualTo(42_000L);
            injected.set(99_999L);
            assertThat(injectedCtx.nowMs()).isEqualTo(99_999L);
        }
    }

    /// Theme M / M1 — `Active.onEntry` invokes the registered active-on-entry callback
    /// deterministically, so consumers (e.g. the lifecycle ON_DUTY registrar) fire on every
    /// Active entry without depending on FSM-dispatch synchrony.
    @Nested
    class ActiveOnEntryCallback {
        @Test
        void onEntry_invokesRegisteredCallback_onceOnQuorumEstablished() {
            var calls = new AtomicLong(0);
            ctx.setActiveOnEntryCallback(calls::incrementAndGet);
            harness.dispatch(new QuorumEstablished());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);
            assertThat(calls.get()).as("callback fires exactly once on entry into Active").isEqualTo(1L);
        }

        @Test
        void onEntry_callbackFiresAgain_onPostDormantRejoin() {
            var calls = new AtomicLong(0);
            ctx.setActiveOnEntryCallback(calls::incrementAndGet);
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new QuorumDisappeared());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);
            harness.dispatch(new QuorumEstablished());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);
            assertThat(calls.get())
                    .as("callback fires once per Active entry — first activation + post-drain rejoin")
                    .isEqualTo(2L);
        }

        @Test
        void onEntry_withoutCallbackRegistered_doesNotThrow() {
            harness.dispatch(new QuorumEstablished());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);
        }
    }

    /// M5 / §5.9 — boot/rejoin as convergence to the KV desired state: a KV-claimed ACTIVE
    /// self-instance observed without a locally loaded slice (wiped node, KV/local divergence)
    /// is redeployed through the standard LOADING → LOADED → ACTIVATE chain; non-self claims are
    /// ignored; an already-deployed instance is a no-op (precondition re-check).
    @Nested
    class KvConvergenceRedeploy {
        private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

        private RecordingSliceStore recordingStore;
        private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> redeployHarness;

        @BeforeEach
        void setUpRedeploy() {
            recordingStore = new RecordingSliceStore();
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
            var ctxHolder = new AtomicReference<NodeDeploymentContext>();
            Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                    fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, recordingStore);
            redeployHarness = FsmTestHarness.harness("ndm-kv-convergence-test-" + SELF.id(), factory);
        }

        @Test
        void kvClaimedActiveSelfInstance_redeploysAfterLocalWipe() {
            redeployHarness.dispatch(new QuorumEstablished());

            redeployHarness.dispatch(new NodeArtifactPutReceived(buildActivePutFor(SELF)));

            assertThat(recordingStore.loadCalls)
                    .as("KV claims ACTIVE, local store empty — the slice must be (re)loaded")
                    .containsExactly(ARTIFACT);
            assertThat(recordingStore.activateCalls)
                    .as("the redeploy chain must drive the slice back to ACTIVE")
                    .containsExactly(ARTIFACT);
        }

        @Test
        void nonSelfActiveClaim_isIgnored() {
            redeployHarness.dispatch(new QuorumEstablished());

            redeployHarness.dispatch(new NodeArtifactPutReceived(buildActivePutFor(OTHER)));

            assertThat(recordingStore.loadCalls).isEmpty();
            assertThat(recordingStore.activateCalls).isEmpty();
        }

        @Test
        void alreadyDeployedActiveInstance_isNoOp() {
            recordingStore.preloadSlice(ARTIFACT);
            redeployHarness.dispatch(new QuorumEstablished());

            redeployHarness.dispatch(new NodeArtifactPutReceived(buildActivePutFor(SELF)));

            assertThat(recordingStore.loadCalls)
                    .as("precondition re-check: an already-deployed instance must not redeploy")
                    .isEmpty();
            assertThat(recordingStore.activateCalls).isEmpty();
        }

        private static ValuePut<NodeArtifactKey, NodeArtifactValue> buildActivePutFor(NodeId node) {
            var key = NodeArtifactKey.nodeArtifactKey(node, ARTIFACT);
            var value = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);
            return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
        }
    }

    /// SliceStore stub that records load/activate calls and succeeds, so the M5 redeploy chain
    /// can run end-to-end (load → activate) inside the FSM harness.
    private static final class RecordingSliceStore implements SliceStore {
        private final List<Artifact> loadCalls = new CopyOnWriteArrayList<>();
        private final List<Artifact> activateCalls = new CopyOnWriteArrayList<>();
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();

        void preloadSlice(Artifact artifact) {
            loadedSlices.add(loadedSlice(artifact));
        }

        private static LoadedSlice loadedSlice(Artifact artifact) {
            Slice slice = List::of;
            return new LoadedSlice() {
                @Override public Artifact artifact() {
                    return artifact;
                }

                @Override public Slice slice() {
                    return slice;
                }
            };
        }

        @Override public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            loadCalls.add(artifact);
            var slice = loadedSlice(artifact);
            loadedSlices.add(slice);
            return Promise.success(slice);
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            activateCalls.add(artifact);
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {
            return Promise.unitPromise();
        }

        @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
            return Option.none();
        }
    }

    // --- test fixtures ---

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> buildLoadArtifactPut() {
        var key = NodeArtifactKey.nodeArtifactKey(SELF, ARTIFACT);
        var value = NodeArtifactValue.nodeArtifactValue(SliceState.LOAD);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static SliceStore stubSliceStore() {
        return new SliceStore() {
            @Override public List<LoadedSlice> loaded() {
                return List.of();
            }

            @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<Unit> unloadSlice(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
                return Option.none();
            }
        };
    }

    private static ClusterNode<KVCommand<AetherKey>> stubClusterNode(NodeId self) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return self;
            }

            @Override public TopologyManager topologyManager() {
                return stubTopologyManager(self);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return Promise.success(Collections.emptyList());
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
                return 1;
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
