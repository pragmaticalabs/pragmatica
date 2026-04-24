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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
