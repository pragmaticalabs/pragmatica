// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.net.SocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1660 / #1452: a slice suspended on quorum loss whose reactivation FAILS on quorum return used to be
/// unregistered locally and dropped from the deployment map while the committed `NodeArtifactKey` still
/// read ACTIVE for this node. Nothing reconciled the mismatch, so the node silently stopped hosting the
/// slice and nothing redeployed it.
///
/// Driven through the real FSM: ACTIVE slice → `QuorumDisappeared` (suspend, no write) →
/// `QuorumEstablished` (reactivate). The reactivation is made to fail the way #1660 observed it — a
/// consensus write timing out during a quorum flap — by refusing the endpoint-bearing ACTIVE put the
/// chain submits. The pin is on what the node SUBMITS to consensus: a non-fatal FAILED for its own key,
/// which is what the leader's failure path unloads and re-drives, and what the event aggregator turns
/// into a WARNING `DeploymentFailed`.
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateReactivationFailureTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final MethodName EXECUTE = MethodName.methodName("execute").unwrap();
    private static final SliceNodeKey SLICE_KEY = SliceNodeKey.sliceNodeKey(ARTIFACT, SELF);

    private RecordingClusterNode cluster;
    private OneMethodSliceStore store;
    private final Set<Artifact> registered = ConcurrentHashMap.newKeySet();
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());

        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
                                                                      SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 1)))));
        cluster = new RecordingClusterNode(SELF);
        store = new OneMethodSliceStore();

        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> buildContext(fsm,
                                                                                                             router,
                                                                                                             kvStore);
        harness = FsmTestHarness.harness("reactivation-failure-" + SELF.id(), factory);
    }

    @Test
    void reactivationFailure_afterQuorumReturns_writesNonFatalFailedForThisNode() {
        suspendAnActiveSlice();

        cluster.refuseActivePuts.set(true);
        harness.dispatch(new QuorumEstablished());

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(failedPuts()).as("#1660: the node must record that it no longer hosts the slice")
                                                            .isNotEmpty());
        var failed = failedPuts().getFirst();

        assertThat(failed.fatal()).as("a reactivation failure must never condemn the artifact")
                                  .isFalse();
        assertThat(failed.failureReason().or("")).contains("Reactivation after quorum restore failed");
        assertThat(registered).as("the bridge is gone").doesNotContain(ARTIFACT);
        assertThat(activeState().deployments().get(SLICE_KEY))
                .as("kept until the leader's unload removes it, so a second quorum loss re-suspends and re-drives it")
                .isNotNull()
                .satisfies(deployment -> assertThat(deployment.state()).isEqualTo(SliceState.ACTIVE));
    }

    /// The second way reactivation can find nothing to host: the slice left the `SliceStore` while the
    /// node was dormant. Same KV claim, same silence before this fix.
    @Test
    void sliceGoneFromStoreDuringOutage_afterQuorumReturns_writesNonFatalFailedForThisNode() {
        suspendAnActiveSlice();

        store.evict(ARTIFACT);
        harness.dispatch(new QuorumEstablished());

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(failedPuts()).as("#1660: a slice gone from the store must not stay claimed ACTIVE")
                                                            .isNotEmpty());
        assertThat(failedPuts().getFirst().fatal()).isFalse();
        assertThat(failedPuts().getFirst().failureReason().or("")).contains("reactivation after quorum restore");
    }

    /// Control, and the no-false-alert half: a reactivation that succeeds writes no FAILED at all, so the
    /// WARNING the FAILED put produces is raised only for a node that genuinely stopped hosting.
    @Test
    void reactivationSuccess_afterQuorumReturns_writesNoFailed() {
        suspendAnActiveSlice();

        harness.dispatch(new QuorumEstablished());

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(registered).as("the bridge is restored").contains(ARTIFACT));
        await().during(300, TimeUnit.MILLISECONDS)
               .atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(failedPuts()).isEmpty());
    }

    /// The pre-reactivation state #1452 asks about: the suspension itself writes nothing, because a node
    /// that has lost quorum cannot commit a write.
    private void suspendAnActiveSlice() {
        harness.dispatch(new QuorumEstablished());
        store.loadSlice(ARTIFACT);
        registered.add(ARTIFACT);
        activeState().deployments()
                     .put(SLICE_KEY, NodeDeploymentManager.SliceDeployment.sliceDeployment(SLICE_KEY, SliceState.ACTIVE, 0L));

        harness.dispatch(new QuorumDisappeared());

        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);
        assertThat(registered).as("precondition: suspended").doesNotContain(ARTIFACT);
        assertThat(cluster.commands).as("precondition: the suspension submitted nothing").isEmpty();
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private List<NodeArtifactValue> failedPuts() {
        return cluster.nodeArtifactPuts()
                      .stream()
                      .filter(value -> value.state() == SliceState.FAILED)
                      .toList();
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> kvStore) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                store,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                kvStore,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(10).millis());

        return context.dormant();
    }

    /// Records every command this node submits; refuses, on demand, any batch carrying an ACTIVE put for
    /// this node's key — the endpoint publish the reactivation chain submits.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> commands = new CopyOnWriteArrayList<>();
        private final AtomicBoolean refuseActivePuts = new AtomicBoolean();

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        List<NodeArtifactValue> nodeArtifactPuts() {
            return commands.stream()
                           .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                              && put.key() instanceof NodeArtifactKey
                                              && put.value() instanceof NodeArtifactValue)
                           .map(command -> (NodeArtifactValue) ((KVCommand.Put<AetherKey, ?>) command).value())
                           .toList();
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            if (refuseActivePuts.get() && batch.stream().anyMatch(RecordingClusterNode::isActivePut)) {
                return Causes.cause("consensus apply timed out (injected quorum flap)").promise();
            }

            commands.addAll(batch);

            return Promise.success(List.of());
        }

        private static boolean isActivePut(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.Put<AetherKey, ?> put
                   && put.value() instanceof NodeArtifactValue value
                   && value.state() == SliceState.ACTIVE;
        }
    }

    /// Loads a slice exposing exactly one method, `execute`; `evict` models the slice leaving the store.
    private static final class OneMethodSliceStore implements SliceStore {
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();

        private static LoadedSlice loadedSlice(Artifact artifact) {
            var method = SliceMethod.sliceMethod(EXECUTE,
                                                 (Unit unit) -> Promise.unitPromise(),
                                                 TypeToken.typeToken(Unit.class),
                                                 TypeToken.typeToken(Unit.class))
                                    .unwrap();
            Slice slice = () -> List.of(method);

            return new LoadedSlice() {
                @Override public Artifact artifact() {
                    return artifact;
                }

                @Override public Slice slice() {
                    return slice;
                }
            };
        }

        void evict(Artifact artifact) {
            loadedSlices.removeIf(slice -> slice.artifact().equals(artifact));
        }

        @Override public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            var slice = loadedSlice(artifact);

            loadedSlices.add(slice);

            return Promise.success(slice);
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {
            return Promise.unitPromise();
        }

        @Override public Option<ConfigurationProvider> sliceComposite(Artifact artifact) {
            return Option.none();
        }
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

    private InvocationHandler stubInvocationHandler() {
        return new InvocationHandler() {
            @Override public void onInvokeRequest(InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, SliceBridge bridge) {
                registered.add(artifact);
            }

            @Override public void unregisterSlice(Artifact artifact) {
                registered.remove(artifact);
            }

            @Override public Option<SliceBridge> localSlice(Artifact artifact) {
                return Option.none();
            }

            @Override public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<InvocationMetricsCollector> metricsCollector() {
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
