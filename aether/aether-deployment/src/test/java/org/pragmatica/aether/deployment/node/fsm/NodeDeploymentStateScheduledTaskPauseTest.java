// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
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
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Covers the scheduled-task PAUSE-preservation fix.
///
/// Operator pause lives only in the cluster-scoped [ScheduledTaskValue#paused] KV atom
/// (`scheduled-task/{section}/{artifact}/{method}` — no `NodeId`, see
/// [`org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey`]). Two FSM paths in
/// [`NodeDeploymentState.Active`] used to destroy it: slice (re)activation republished the key with
/// a hardcoded `paused=false`, and any single replica's deactivate unconditionally Removed the
/// cluster-wide key even while other replicas still hosted the task. The decision seams
/// [`NodeDeploymentState.Active#existingPausedFlag`] and
/// [`NodeDeploymentState.Active#artifactHostedElsewhere`] are exercised on the live `Active` state
/// (reached via [`FsmTestHarness`] on a `QuorumEstablished` dispatch) over a seeded KV store, the
/// same inspection style as the sibling seed-epoch-ack suite.
class NodeDeploymentStateScheduledTaskPauseTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final String SECTION = "click-events";
    private static final MethodName METHOD = MethodName.methodName("onTick").unwrap();
    private static final String INTERVAL = "PT30S";

    private KVStore<AetherKey, AetherValue> kvStore;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        SliceStore sliceStore = stubSliceStore();
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, sliceStore);
        harness = FsmTestHarness.harness("ndm-scheduled-task-pause-test-" + SELF.id(), factory);
        harness.dispatch(new QuorumEstablished());
    }

    @Nested
    class PublishPausePreservation {
        @Test
        void existingPausedFlag_republishOverPausedValue_carriesPausedTrue() {
            seedScheduledTask(true);

            assertThat(activeState().existingPausedFlag(taskKey()))
                    .as("republish over an operator-paused task must carry paused=true")
                    .isTrue();
        }

        @Test
        void existingPausedFlag_republishOverRunningValue_carriesPausedFalse() {
            seedScheduledTask(false);

            assertThat(activeState().existingPausedFlag(taskKey()))
                    .as("republish over a running task must keep paused=false")
                    .isFalse();
        }

        @Test
        void existingPausedFlag_freshPublishWithNoExistingValue_defaultsFalse() {
            assertThat(activeState().existingPausedFlag(taskKey()))
                    .as("a brand-new registration with no prior KV value defaults to paused=false")
                    .isFalse();
        }
    }

    @Nested
    class UnpublishLastReplica {
        @Test
        void artifactHostedElsewhere_anotherLiveReplicaHostsTask_isTrue() {
            seedNodeArtifact(OTHER, SliceState.ACTIVE);

            assertThat(activeState().artifactHostedElsewhere(ARTIFACT))
                    .as("a single replica's deactivate must not remove the cluster-scoped key while OTHER still hosts it")
                    .isTrue();
        }

        @Test
        void artifactHostedElsewhere_onlySelfHostsTask_isFalse() {
            seedNodeArtifact(SELF, SliceState.ACTIVE);

            assertThat(activeState().artifactHostedElsewhere(ARTIFACT))
                    .as("the last hosting replica's deactivate may remove the key")
                    .isFalse();
        }

        @Test
        void artifactHostedElsewhere_otherReplicaUnloading_isFalse() {
            seedNodeArtifact(OTHER, SliceState.UNLOADING);

            assertThat(activeState().artifactHostedElsewhere(ARTIFACT))
                    .as("an UNLOADING replica does not count as still hosting the task")
                    .isFalse();
        }
    }

    @Nested
    class PauseApiWrite {
        @Test
        void withPaused_preservesScheduleFieldsAndSetsPausedTrue() {
            var running = ScheduledTaskValue.intervalTask(SELF, INTERVAL, ExecutionMode.SINGLE);

            var paused = running.withPaused(true);

            assertThat(paused.paused()).isTrue();
            assertThat(paused.registeredBy()).isEqualTo(SELF);
            assertThat(paused.interval()).isEqualTo(INTERVAL);
            assertThat(paused.executionMode()).isEqualTo(ExecutionMode.SINGLE);
        }
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private static ScheduledTaskKey taskKey() {
        return ScheduledTaskKey.scheduledTaskKey(SECTION, ARTIFACT, METHOD);
    }

    private void seedScheduledTask(boolean paused) {
        var value = ScheduledTaskValue.intervalTask(SELF, INTERVAL, ExecutionMode.SINGLE).withPaused(paused);

        applyToKvStore(new KVCommand.Put<>(taskKey(), value));
    }

    private void seedNodeArtifact(NodeId node, SliceState state) {
        var key = NodeArtifactKey.nodeArtifactKey(node, ARTIFACT);
        var value = NodeArtifactValue.nodeArtifactValue(state);

        applyToKvStore(new KVCommand.Put<>(key, value));
    }

    private void applyToKvStore(KVCommand<AetherKey> command) {
        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> store,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             SliceStore sliceStore) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                sliceStore,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                store,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
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
                return List.of(self, OTHER);
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
