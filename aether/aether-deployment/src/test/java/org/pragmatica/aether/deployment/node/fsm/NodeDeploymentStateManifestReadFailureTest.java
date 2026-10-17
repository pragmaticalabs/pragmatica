// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
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


/// s25-inv1277: the three manifest readers on the activation chain caught every read failure and
/// logged it at DEBUG, so a poisoned jar cache silently dropped a slice's subscriptions, scheduled
/// tasks, config-update registrations and stream roles — invisible at INFO. They WARN now, naming
/// the artifact and carrying the cause.
///
/// Drives the real LOAD → ACTIVE chain through the FSM harness (as
/// [NodeDeploymentStateTopicSubscriptionNamespaceTest] does) with a slice whose DEFINING loader
/// fails every resource read the way a poisoned cache does. Every reader catches, so the slice
/// still reaches ACTIVE; the WARNs are the only trace.
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateManifestReadFailureTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:manifest-probe:1.0.0").unwrap();
    private static final Artifact BLUEPRINT = Artifact.artifact("org.example:probe-app:1.0.0").unwrap();
    private static final String CAUSE = "java.lang.IllegalStateException: zip file closed";
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
        harness = FsmTestHarness.harness("manifest-read-failure-" + SELF.id(), factory);
    }

    @Test
    void manifestReadFailures_onTheActivationChain_warnNamingTheArtifactAndTheCause() {
        var warnings = activateCapturingWarnings(true);
        var withCause = warnings.stream().filter(line -> line.contains(CAUSE)).toList();

        // Unfixed: DEBUG, so no WARN carries the cause at all.
        assertThat(withCause).describedAs("captured WARNs: %s", warnings).isNotEmpty();
        assertThat(withCause).allSatisfy(line -> assertThat(line).contains(SLICE.asString()).contains(MANIFEST));
        // One per reader on the chain: reactive bindings are read for topic subscriptions, stream
        // subscriptions and scheduled tasks; config updates once; stream roles once at ACTIVE.
        assertThat(withCause.stream().filter(line -> line.startsWith("Could not read reactive manifest")).count()).isEqualTo(3);
        assertThat(withCause.stream().filter(line -> line.startsWith("Could not read config update manifest")).count()).isEqualTo(1);
        assertThat(withCause.stream().filter(line -> line.startsWith("Could not read stream role declarations")).count()).isEqualTo(1);
        assertThat(withCause).hasSize(5);
    }

    /// The quiet branch: a slice whose jar ships no manifest answers `null`, which is not a read
    /// failure and must not WARN as one.
    @Test
    void absentManifest_onTheActivationChain_isNotAReadFailure_andStaysQuiet() {
        var warnings = activateCapturingWarnings(false);

        assertThat(warnings).describedAs("captured: %s", warnings).noneMatch(line -> line.startsWith("Could not read"));
    }

    private List<String> activateCapturingWarnings(boolean failingReads) {
        store.failingReads = failingReads;
        var warnings = new ArrayList<String>();
        var detach = capturingWarnings(warnings);

        try {
            harness.dispatch(new QuorumEstablished());
            harness.dispatch(new NodeArtifactPutReceived(activePutFor(SELF)));

            await().atMost(5, TimeUnit.SECONDS)
                   .untilAsserted(() -> assertThat(activeTransitions()).describedAs("every reader catches, so the slice still activates")
                                                                       .isNotEmpty());
        } finally {
            detach.run();
        }

        return warnings;
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
        private final boolean failing;

        ThrowingResourceLoader(boolean failing) {
            super(NodeDeploymentStateManifestReadFailureTest.class.getClassLoader());
            this.failing = failing;
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
            if (failing) {
                throw new IllegalStateException("zip file closed");
            }

            return null;
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

    private static Slice probeSlice(boolean failingReads) {
        try {
            return (Slice) new ThrowingResourceLoader(failingReads).loadClass(ProbeSlice.class.getName())
                                                                   .getDeclaredConstructor()
                                                                   .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class ProbeSliceStore implements SliceStore {
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();
        private volatile boolean failingReads = true;

        private LoadedSlice loadedSlice(Artifact artifact) {
            var slice = probeSlice(failingReads);

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

    /// Capture WARN lines of the ACTIVE state's logger (the readers live there); the returned runnable detaches the appender. The
    /// filter on the logger NAME keeps other loggers' WARNs out.
    private static Runnable capturingWarnings(List<String> sink) {
        var context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        var loggerConfig = config.getLoggerConfig(NodeDeploymentState.Active.class.getName());
        var appender = new AbstractAppender("manifest-warn-capture", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN && NodeDeploymentState.Active.class.getName().equals(event.getLoggerName())) {
                    sink.add(event.getMessage().getFormattedMessage());
                }
            }
        };

        appender.start();
        loggerConfig.addAppender(appender, Level.WARN, null);
        context.updateLoggers();

        return () -> {
            loggerConfig.removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        };
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
