// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.ConfigChanged;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.ConfigFacade;
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
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigurationProvider;
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
import org.pragmatica.lang.type.TypeToken;
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


/// #2378: a routed slice's manifest names its JSON codec settings section (`json.settings.section=json`), and the
/// node registers it for config-change notification on activation, so a committed `json.*` change reaches the slice
/// factory's generated `notifyConfigUpdate` with section `json` (whose generated branch refreshes the live settings).
///
/// Drives the real LOAD → ACTIVE chain through the FSM harness, as [NodeDeploymentStateManifestReadFailureTest] does,
/// with a slice whose defining loader serves the manifest.
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateJsonSettingsRegistrationTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:json-probe:1.0.0").unwrap();
    private static final Artifact BLUEPRINT = Artifact.artifact("org.example:probe-app:1.0.0").unwrap();
    private static final String MANIFEST = "META-INF/slice/ManifestProbeSlice.manifest";

    private RecordingClusterNode cluster;
    private ProbeSliceStore store;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

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
        store = new ProbeSliceStore();

        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> buildContext(fsm,
                                                                                                              ctxHolder,
                                                                                                              router,
                                                                                                              kvStore);
        harness = FsmTestHarness.harness("json-settings-registration-" + SELF.id(), factory);
    }

    @Test
    void routedSlice_isNotifiedOnItsJsonSection_initiallyAndOnACommittedChange() {
        JsonProbeFactory.calls.clear();
        activate(true);

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(JsonProbeFactory.calls).containsExactly("json"));

        harness.dispatch(new ConfigChanged("json.default"));
        harness.dispatch(new ConfigChanged("database.pool_size"));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(JsonProbeFactory.calls).containsExactly("json", "json"));
    }

    @Test
    void sliceWithoutTheJsonSection_isNotRegistered() throws InterruptedException {
        JsonProbeFactory.calls.clear();
        activate(false);
        harness.dispatch(new ConfigChanged("json.default"));
        TimeUnit.MILLISECONDS.sleep(300);

        assertThat(JsonProbeFactory.calls).isEmpty();
    }

    private void activate(boolean routed) {
        store.routed = routed;
        harness.dispatch(new QuorumEstablished());
        harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));

        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(activeTransitions()).isNotEmpty());
    }

    /// The slice factory the manifest names; the node calls its `notifyConfigUpdate` reflectively.
    public static final class JsonProbeFactory {
        static final List<String> calls = new CopyOnWriteArrayList<>();

        public static void notifyConfigUpdate(Object sliceInstance, String section, ConfigFacade config) {
            calls.add(section);
        }
    }

    /// The node's own state put (`NodeArtifactKey` → `NodeArtifactValue`) carrying ACTIVE.
    private List<NodeArtifactValue> activeTransitions() {
        return cluster.commands()
                      .stream()
                      .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                         && put.key() instanceof NodeArtifactKey
                                         && put.value() instanceof NodeArtifactValue value
                                         && value.state() == SliceState.ACTIVE)
                      .map(command -> (NodeArtifactValue) ((KVCommand.Put<AetherKey, ?>) command).value())
                      .toList();
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
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
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> activePutFor(NodeId node) {
        var key = NodeArtifactKey.nodeArtifactKey(node, SLICE);
        var value = NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    /// A NAMED interface, so the readers look up `META-INF/slice/ManifestProbeSlice.manifest`. Public,
    /// because [ProbeSlice] is defined in another runtime package (same name, other loader).
    public interface ManifestProbeSlice extends Slice {}

    /// Self-contained on purpose: defined by [ThrowingResourceLoader], it is not a nestmate of this
    /// class at runtime and cannot touch its private members.
    public static final class ProbeSlice implements ManifestProbeSlice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of(SliceMethod.sliceMethod(MethodName.methodName("execute").unwrap(),
                                                   (Unit unit) -> Promise.unitPromise(),
                                                   TypeToken.typeToken(Unit.class),
                                                   TypeToken.typeToken(Unit.class))
                                      .unwrap());
        }
    }

    /// Defines [ProbeSlice] itself (child-first for that one name) so the slice's defining loader is
    /// this one, and either fails every resource read the way a poisoned jar cache does or answers
    /// `null` the way a jar with no manifest does.
    private static final class ThrowingResourceLoader extends ClassLoader {
        private final boolean routed;

        ThrowingResourceLoader(boolean routed) {
            super(NodeDeploymentStateJsonSettingsRegistrationTest.class.getClassLoader());
            this.routed = routed;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!ProbeSlice.class.getName().equals(name)) {
                return super.loadClass(name, resolve);
            }

            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);

                if (loaded != null) {
                    return loaded;
                }

                var bytes = classBytes(name);

                return defineClass(name, bytes, 0, bytes.length);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (!MANIFEST.equals(name)) {
                return null;
            }

            var manifest = "slice.factory=" + JsonProbeFactory.class.getName() + "\n"
                           + (routed
                              ? "json.settings.section=json\n"
                              : "");

            return new java.io.ByteArrayInputStream(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private byte[] classBytes(String name) throws ClassNotFoundException {
            try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }

                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    private static Slice probeSlice(boolean routed) {
        try {
            return (Slice) new ThrowingResourceLoader(routed).loadClass(ProbeSlice.class.getName())
                                                                   .getDeclaredConstructor()
                                                                   .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class ProbeSliceStore implements SliceStore {
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();
        private volatile boolean routed = true;

        private LoadedSlice loadedSlice(Artifact artifact) {
            var slice = probeSlice(routed);

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

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<KVCommand<AetherKey>> commands = new CopyOnWriteArrayList<>();

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        List<KVCommand<AetherKey>> commands() {
            return List.copyOf(commands);
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(List.of());
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

            @Override public void registerSlice(Artifact artifact, SliceBridge bridge) {}

            @Override public void unregisterSlice(Artifact artifact) {}

            @Override public Option<SliceBridge> localSlice(Artifact artifact) {
                return Option.some(stubBridge());
            }

            @Override public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<InvocationMetricsCollector> metricsCollector() {
                return Option.none();
            }
        };
    }

    private static SliceBridge stubBridge() {
        return new SliceBridge() {
            @Override public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.success(new byte[0]);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public ClassLoader classLoader() {
                return getClass().getClassLoader();
            }

            @Override public List<String> methodNames() {
                return List.of("execute");
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
