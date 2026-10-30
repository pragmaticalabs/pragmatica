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
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.deployment.config.ConfigReadProtocol;
import org.pragmatica.aether.deployment.config.DefaultConfigChangeFeed;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.slice.SliceClassLoader;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingContext;
import org.pragmatica.aether.slice.SliceStore.EntryState;
import org.pragmatica.aether.slice.SliceStore.LoadedSliceEntry;
import org.pragmatica.aether.slice.SliceStore.sliceStore;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Result;

/// #774 / W-10 (i)(ii), driven through the real FSM and a REAL `SliceStore` with a real feed: a `Watched`
/// subscription survives the instance being deactivated (DEACTIVATE -> LOADED -> ACTIVATE) and the node being
/// suspended and reactivated across a quorum loss, and keeps delivering while the slice is not serving. Only the
/// unload of the declaring artifact releases it (see `SliceStoreConfigWatchReleaseTest`).
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateConfigWatchSurvivalTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final MethodName EXECUTE = MethodName.methodName("execute").unwrap();
    private static final SliceNodeKey SLICE_KEY = SliceNodeKey.sliceNodeKey(ARTIFACT, SELF);
    private static final SliceInvokerFacade STUB_INVOKER = new SliceInvokerFacade() {
        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String artifact, String method, TypeToken<T> requestType, TypeToken<R> responseType) {
            return Causes.cause("Stub invoker").result();
        }
    };

    private RecordingClusterNode cluster;
    private sliceStore store;
    private DefaultConfigChangeFeed feed;
    private final AtomicReference<String> value = new AtomicReference<>("v0");
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final Set<Artifact> registered = ConcurrentHashMap.newKeySet();
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());

        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
                                                                      SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 1)))));
        cluster = new RecordingClusterNode(SELF);
        feed = DefaultConfigChangeFeed.defaultConfigChangeFeed(() -> 0L, ConfigReadProtocol.unprotected(), timeSpan(2).seconds());
        store = (sliceStore) SliceStore.sliceStore(SliceRegistry.sliceRegistry(),
                                                   List.of(),
                                                   new SharedLibraryClassLoader(getClass().getClassLoader()),
                                                   STUB_INVOKER,
                                                   noResources(),
                                                   SliceActionConfig.sliceActionConfig(),
                                                   Option.empty(),
                                                   Option.empty(),
                                                   Option.empty(),
                                                   SliceLoadingContext.noResourceOverlay(),
                                                   Option.some(feed));

        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> buildContext(fsm, router, kvStore);
        harness = FsmTestHarness.harness("config-watch-survival-" + SELF.id(), factory);
    }

    @Test
    void suspendAndReactivate_keepTheSubscription_andDeliveriesContinueMeanwhile() {
        hostAnActiveSliceWithASubscription();

        harness.dispatch(new QuorumDisappeared());
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);
        change("while-suspended");

        await().atMost(5, TimeUnit.SECONDS).until(() -> delivered.contains("while-suspended"));
        assertThat(feed.subscriberCount(ARTIFACT.asString())).as("suspension is not an unload").isEqualTo(1);

        harness.dispatch(new QuorumEstablished());
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(registered).contains(ARTIFACT));
        change("after-reactivation");

        await().atMost(5, TimeUnit.SECONDS).until(() -> delivered.contains("after-reactivation"));
        assertThat(feed.subscriberCount(ARTIFACT.asString())).isEqualTo(1);
    }

    @Test
    void deactivateAndActivate_keepTheSubscription_andDeliveriesContinueWhileLoaded() {
        hostAnActiveSliceWithASubscription();

        harness.dispatch(nodeArtifactPut(SliceState.DEACTIVATE));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(entryState()).isEqualTo(EntryState.LOADED));
        change("while-deactivated");

        await().atMost(5, TimeUnit.SECONDS).until(() -> delivered.contains("while-deactivated"));
        assertThat(feed.subscriberCount(ARTIFACT.asString())).as("deactivation is not an unload").isEqualTo(1);

        harness.dispatch(nodeArtifactPut(SliceState.ACTIVATE));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(entryState()).isEqualTo(EntryState.ACTIVE));
        change("after-reactivation");

        await().atMost(5, TimeUnit.SECONDS).until(() -> delivered.contains("after-reactivation"));
        assertThat(feed.subscriberCount(ARTIFACT.asString())).isEqualTo(1);
    }

    private void hostAnActiveSliceWithASubscription() {
        harness.dispatch(new QuorumEstablished());
        Slice slice = () -> List.of(SliceMethod.sliceMethod(EXECUTE,
                                                            (Unit unit) -> Promise.unitPromise(),
                                                            TypeToken.typeToken(Unit.class),
                                                            TypeToken.typeToken(Unit.class))
                                               .unwrap());
        var entry = new LoadedSliceEntry(ARTIFACT,
                                         slice,
                                         new SliceClassLoader(new URL[0], getClass().getClassLoader()),
                                         SliceLoadingContext.sliceLoadingContext(STUB_INVOKER),
                                         Option.empty(),
                                         EntryState.ACTIVE);

        store.entries().put(ARTIFACT, Promise.success(entry));
        registered.add(ARTIFACT);
        activeState().deployments()
                     .put(SLICE_KEY, NodeDeploymentManager.SliceDeployment.sliceDeployment(SLICE_KEY, SliceState.ACTIVE, 0L));
        assertThat(feed.subscribe(ARTIFACT.asString(), "watched", () -> Result.success(value.get()), this::record).isSuccess()).isTrue();
        delivered.clear();
    }

    private Promise<Unit> record(String seen) {
        delivered.add(seen);
        return Promise.unitPromise();
    }

    private void change(String next) {
        value.set(next);
        feed.publish("watched.key");
    }

    private EntryState entryState() {
        return store.entries().get(ARTIFACT).await().map(LoadedSliceEntry::state).or(EntryState.LOADED);
    }

    private static NodeArtifactPutReceived nodeArtifactPut(SliceState state) {
        return new NodeArtifactPutReceived(new ValuePut<>(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(SELF, ARTIFACT),
                                                                              NodeArtifactValue.nodeArtifactValue(state)),
                                                         Option.none()));
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private static ResourceProviderFacade noResources() {
        return new ResourceProviderFacade() {
            @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return Causes.cause("no resources in this test").promise();
            }

            @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return Causes.cause("no resources in this test").promise();
            }

            @Override public Promise<Unit> releaseAll(String sliceId) {
                return Promise.unitPromise();
            }
        };
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
