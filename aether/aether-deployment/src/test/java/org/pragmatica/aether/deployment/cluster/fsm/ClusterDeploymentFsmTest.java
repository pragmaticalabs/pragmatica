// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Deactivate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
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
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FSM-level tests for the [`org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager`]
/// state machine. Exercises the explicit `Dormant → Active → Dormant → Stopped` lifecycle via
/// [`FsmTestHarness`], independent from the public `ClusterDeploymentManager` surface.
class ClusterDeploymentFsmTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();

    private ClusterDeploymentContext ctx;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        var ctxHolder = new AtomicReference<ClusterDeploymentContext>();
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster);
        harness = FsmTestHarness.harness("cluster-deployment-fsm-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
    }

    private ClusterDeploymentState buildContext(Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm,
                                                 AtomicReference<ClusterDeploymentContext> ctxHolder,
                                                 MessageRouter router,
                                                 KVStore<AetherKey, AetherValue> kvStore,
                                                 ClusterNode<KVCommand<AetherKey>> cluster) {
        var context = new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    HealthSignalSink.noop(),
                                                    Option::none,
                                                    Set.of(SELF),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds());
        ctxHolder.set(context);
        return context.dormant();
    }

    @Nested
    class HappyPath {
        @Test
        void full_lifecycle_traversesAllStates() {
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Dormant.class);

            harness.dispatch(new Activate());
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Active.class);
            assertThat(ctx.isActive()).isTrue();

            harness.dispatch(new Deactivate());
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Dormant.class);
            assertThat(ctx.isActive()).isFalse();

            harness.dispatch(new Activate());
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Active.class);

            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Stopped.class);
        }
    }

    @Nested
    class CasContention {
        @Test
        void eightConcurrentActivate_singleWinnerAdvancesToActive() throws Exception {
            var events = new ArrayList<ClusterFsmEvent>();
            for (int i = 0; i < 8; i++) {
                events.add(new Activate());
            }
            harness.dispatchConcurrently(events);
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Active.class);
            var dormantToActive = harness.transitions().stream()
                                          .filter(t -> t.from() instanceof ClusterDeploymentState.Dormant
                                                       && t.to() instanceof ClusterDeploymentState.Active)
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
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Dormant.class);
            assertThat(harness.ignored()).isNotEmpty();
        }

        @Test
        void deactivate_inDormant_isIgnored() {
            harness.dispatch(new Deactivate());
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Dormant.class);
            assertThat(harness.ignored()).isNotEmpty();
        }

        @Test
        void shutdown_inStopped_isIgnored() {
            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Stopped.class);
            harness.dispatch(new Shutdown());
            assertThat(harness.state()).isInstanceOf(ClusterDeploymentState.Stopped.class);
            assertThat(harness.ignored()).isNotEmpty();
        }
    }

    /// Theme H clock-injection coverage: confirms the full-arity `ClusterDeploymentContext`
    /// constructor wires an injected `LongSupplier` through to `nowMs()`.
    @Nested
    class ClockInjection {
        @Test
        void nowMs_returnsInjectedClockValue_notSystemClock() {
            var injected = new AtomicLong(7_777L);
            LongSupplier clock = injected::get;
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
            var ctxHolder = new AtomicReference<ClusterDeploymentContext>();
            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> {
                        var context = new ClusterDeploymentContext(fsm,
                                                                    SELF,
                                                                    cluster,
                                                                    kvStore,
                                                                    router,
                                                                    stubTopologyManager(SELF),
                                                                    stubSchemaOrchestrator(),
                                                                    HealthSignalSink.noop(),
                                                                    Option::none,
                                                                    Set.of(SELF),
                                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                                    3,
                                                                    timeSpan(300).seconds(),
                                                                    clock);
                        ctxHolder.set(context);
                        return context.dormant();
                    };
            FsmTestHarness.harness("cluster-deployment-clock-test-" + SELF.id(), factory);

            var injectedCtx = ctxHolder.get();
            assertThat(injectedCtx.nowMs()).isEqualTo(7_777L);
            injected.set(8_888L);
            assertThat(injectedCtx.nowMs()).isEqualTo(8_888L);
        }
    }

    // --- test fixtures ---

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> buildLoadArtifactPut() {
        var key = NodeArtifactKey.nodeArtifactKey(SELF, ARTIFACT);
        var value = NodeArtifactValue.nodeArtifactValue(SliceState.LOAD);
        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
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
