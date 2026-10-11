// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.DeploymentCompleted;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
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
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1117: both stuck-slice remediation timers (ROUTING 30 s, ACTIVATING 90 s) used to write a bare ACTIVE on the
/// node's LOCAL view, which moves only when the node's own apply delivers its notification. A node whose last write
/// had committed but whose view still read ROUTING/ACTIVATING wrote a second ACTIVE over the committed one; the extra
/// put is what moved a durable group's assignee (#1333, #1385).
///
/// The timers now read the node's own COMMITTED `NodeArtifactKey` (this node's applied mirror) first:
///  - committed ACTIVE: adopt it locally, write NOTHING;
///  - EARLIER in the activation chain (LOAD..ROUTING): re-drive the chain's FULL last write (ACTIVE plus the stream-ref
///    commands, in ONE batch), while a committed SliceTarget still names this version;
///  - DEACTIVATE, DEACTIVATING, FAILED, UNLOAD, UNLOADING, UNKNOWN or no entry: the slice has left the chain, so ACTIVE
///    is NEVER written over it; the value is adopted and the remediation stops.
/// Every line carries the node id, the local state and the committed state; the log tests below pin that.
///
/// The slice here declares one stream access (`ReconcileStreamMarkerSlice.manifest`) with a committed binding, so the
/// full write is distinguishable from a bare ACTIVE put: only it carries a `StreamRegistryKey` command.
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateStuckReconcileTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final BlueprintId BLUEPRINT = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();

    private final Set<Artifact> serving = ConcurrentHashMap.newKeySet();
    private final List<DeploymentCompleted> completed = Collections.synchronizedList(new ArrayList<>());
    private RecordingClusterNode cluster;
    private StreamStubStore store;
    private KVStore<AetherKey, AetherValue> kvStore;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private Runnable detachLog = () -> {};

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        router.addRoute(DeploymentCompleted.class, completed::add);
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        var address = ResourceAddress.resourceAddress("org.example", "events", "1.0.0").unwrap();

        applyToKv(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
                                      SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 1, 1, Option.some(BLUEPRINT))));
        applyToKv(new KVCommand.Put<>(BlueprintStreamBindingsKey.blueprintStreamBindingsKey(BLUEPRINT),
                                      BlueprintStreamBindingsValue.blueprintStreamBindingsValue(List.of(NamedAddress.namedAddress("events",
                                                                                                                                  address)))));
        cluster = new RecordingClusterNode(SELF);
        store = new StreamStubStore();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore);
        harness = FsmTestHarness.harness("stuck-reconcile-" + SELF.id(), factory);
        harness.dispatch(new QuorumEstablished());
        store.loaded.add(StreamStubStore.loadedSlice(ARTIFACT));
        cluster.batches.clear();
        detachLog = capturingWarnings(warnings);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        detachLog.run();
    }

    @Test
    void stuckRouting_committedActive_adoptsLocallyAndWritesNothing() {
        commit(SliceState.ACTIVE);
        var key = seed(SliceState.ROUTING);

        activeState().remediateStuckRouting(key);

        assertThat(cluster.batches).as("a committed ACTIVE must not be written over").isEmpty();
        assertThat(localState(key)).as("the committed ACTIVE is adopted into the local view").isEqualTo(SliceState.ACTIVE);
        assertThat(completed).as("adoption drives the ACTIVE handler exactly as the missed notification would")
                             .hasSize(1);
    }

    @Test
    void stuckActivating_committedActive_adoptsLocallyAndWritesNothing() {
        commit(SliceState.ACTIVE);
        serving.add(ARTIFACT);
        var key = seed(SliceState.ACTIVATING);

        activeState().remediateStuckActivating(key);

        assertThat(cluster.batches).as("a committed ACTIVE must not be written over").isEmpty();
        assertThat(localState(key)).isEqualTo(SliceState.ACTIVE);
        assertThat(completed).hasSize(1);
    }

    @Test
    void stuckRouting_committedRouting_redrivesTheFullLastWriteInOneBatch() {
        commit(SliceState.ROUTING);
        var key = seed(SliceState.ROUTING);

        activeState().remediateStuckRouting(key);

        assertFullLastWrite();
    }

    @Test
    void stuckActivating_committedActivating_redrivesTheFullLastWriteInOneBatch() {
        commit(SliceState.ACTIVATING);
        serving.add(ARTIFACT);
        var key = seed(SliceState.ACTIVATING);

        activeState().remediateStuckActivating(key);

        assertFullLastWrite();
    }

    @Test
    void stuckActivating_notServing_writesNothingEvenWhenCommittedIsLower() {
        commit(SliceState.ACTIVATING);
        var key = seed(SliceState.ACTIVATING);

        activeState().remediateStuckActivating(key);

        assertThat(cluster.batches).as("the serving gate still holds: no phantom ACTIVE").isEmpty();
    }


    @ParameterizedTest
    @EnumSource(value = SliceState.class, names = {"DEACTIVATE", "DEACTIVATING", "FAILED", "UNLOAD", "UNLOADING", "UNKNOWN"})
    void stuckRouting_committedStateBeyondTheChain_neverWritesActive_andAdoptsIt(SliceState beyond) {
        commit(beyond);
        var key = seed(SliceState.ROUTING);

        activeState().remediateStuckRouting(key);

        assertThat(activePuts()).as("ACTIVE must never be written over a committed %s", beyond).isEmpty();
        assertAdopted(key, beyond);
        assertThat(warnings).anySatisfy(line -> assertThat(line).contains("Node self")
                                                                .contains("still ROUTING")
                                                                .contains("COMMITTED state is " + beyond)
                                                                .contains("NOT writing ACTIVE"));
    }

    @ParameterizedTest
    @EnumSource(value = SliceState.class, names = {"DEACTIVATE", "DEACTIVATING", "FAILED", "UNLOAD", "UNLOADING", "UNKNOWN"})
    void stuckActivating_committedStateBeyondTheChain_neverWritesActive_andAdoptsIt(SliceState beyond) {
        commit(beyond);
        serving.add(ARTIFACT);
        var key = seed(SliceState.ACTIVATING);

        activeState().remediateStuckActivating(key);

        assertThat(activePuts()).as("ACTIVE must never be written over a committed %s", beyond).isEmpty();
        assertAdopted(key, beyond);
    }

    @Test
    void stuckRouting_noCommittedEntry_writesNothing_andHandlesItAsTheRemovalItIs() {
        var key = seed(SliceState.ROUTING);

        activeState().remediateStuckRouting(key);

        assertThat(cluster.batches).as("an absent entry is a removed slice: nothing may be written").isEmpty();
        assertThat(activeState().deployments()).as("the local entry goes with it").doesNotContainKey(key);
        assertThat(warnings).anySatisfy(line -> assertThat(line).contains("Node self")
                                                                .contains("still ROUTING")
                                                                .contains("ABSENT"));
    }

    @Test
    void stuckActivating_noCommittedEntry_writesNothing() {
        serving.add(ARTIFACT);
        var key = seed(SliceState.ACTIVATING);

        activeState().remediateStuckActivating(key);

        assertThat(cluster.batches).isEmpty();
        assertThat(activeState().deployments()).doesNotContainKey(key);
    }

    @Test
    void stuckRouting_committedRouting_butNoTargetNamesThisVersion_doesNotRedrive() {
        commit(SliceState.ROUTING);
        applyToKv(new KVCommand.Remove<>(SliceTargetKey.sliceTargetKey(ARTIFACT.base())));
        var key = seed(SliceState.ROUTING);

        activeState().remediateStuckRouting(key);

        assertThat(cluster.batches).as("a rolled-back or replaced version must not be started again (#1068)").isEmpty();
        assertThat(warnings).anySatisfy(line -> assertThat(line).contains("Node self")
                                                                .contains("still ROUTING")
                                                                .contains("committed state is ROUTING")
                                                                .contains("NOT re-driving"));
    }

    @Test
    void remediationLines_nameTheNodeTheLocalStateAndTheCommittedState() {
        commit(SliceState.ACTIVE);
        activeState().remediateStuckRouting(seed(SliceState.ROUTING));
        commit(SliceState.ROUTING);
        activeState().remediateStuckRouting(seed(SliceState.ROUTING));
        commit(SliceState.ACTIVATING);
        activeState().remediateStuckActivating(servingSeed(SliceState.ACTIVATING));

        assertThat(warnings).anySatisfy(line -> assertThat(line).contains("Node self")
                                                                .contains("still ROUTING locally")
                                                                .contains("COMMITTED state is ACTIVE — adopting the committed ACTIVE into local state, no write"));
        assertThat(warnings).anySatisfy(line -> assertThat(line).contains("Node self")
                                                                .contains("still ROUTING locally")
                                                                .contains("committed state is ROUTING — re-driving"));
        assertThat(warnings).anySatisfy(line -> assertThat(line).contains("Node self")
                                                                .contains("still ACTIVATING locally")
                                                                .contains("committed state is ACTIVATING — re-driving"));
    }

    @Test
    void stuckActivating_notServing_lineNamesTheNodeAndTheCommittedState() {
        commit(SliceState.ACTIVATING);
        var key = seed(SliceState.ACTIVATING);

        activeState().remediateStuckActivating(key);

        assertThat(warnings).anySatisfy(line -> assertThat(line).contains("Node self")
                                                                .contains("still ACTIVATING locally")
                                                                .contains("committed state ACTIVATING")
                                                                .contains("NOT registered for invocation"));
    }

    /// The committed state is adopted into the local view; UNLOAD is the one whose handler runs the unload to completion
    /// (the stub store succeeds at once) and drops the entry, which is the adopted state's own effect.
    private void assertAdopted(SliceNodeKey key, SliceState beyond) {
        var local = activeState().deployments().get(key);

        if (local == null) {
            assertThat(beyond).as("only UNLOAD's handler removes the entry").isEqualTo(SliceState.UNLOAD);

            return;
        }

        assertThat(local.state()).as("the committed %s is adopted into the local view", beyond).isEqualTo(beyond);
    }

    /// The late-original hazard: the chain's own write (built from the mirror at the time) lands AFTER the timer's
    /// re-drive, or its stream-ref Put is already in the mirror when the re-drive is built. `acquireCommand` is an
    /// absolute Put (refCount = mirror + 1); a re-drive rebuilt from the advanced mirror would write 2 for the one
    /// instance. Both writes of one activation attempt must carry the SAME Put.
    @Test
    void theRedriveCarriesTheSameStreamRefPutAsTheOriginalWrite_evenWhenTheOriginalsPutIsAlreadyInTheMirror() {
        commit(SliceState.ROUTING);
        var key = seed(SliceState.ROUTING);

        activeState().remediateStuckRouting(key);
        var original = streamRefPuts(cluster.batches.getFirst());

        assertThat(original).as("precondition: the re-drive carries one stream-ref Put").hasSize(1);
        assertThat(refCount(original.getFirst())).as("one instance, one reference").isEqualTo(1);
        // the original's Put lands in the mirror before the second timer fire builds its batch
        applyToKv(original.getFirst());
        seed(SliceState.ROUTING);
        commit(SliceState.ROUTING);
        cluster.batches.clear();

        activeState().remediateStuckRouting(key);

        var second = streamRefPuts(cluster.batches.getFirst());

        assertThat(second).hasSize(1);
        assertThat(refCount(second.getFirst()))
                .as("rebuilt from the advanced mirror this would be 2: the instance would hold two references and never release")
                .isEqualTo(1);
    }

    @Test
    void aNewActivationAttemptBuildsItsStreamRefPutAgainFromTheMirror() {
        commit(SliceState.ROUTING);
        var key = seed(SliceState.ROUTING);

        activeState().remediateStuckRouting(key);
        applyToKv(streamRefPuts(cluster.batches.getFirst()).getFirst());
        // the node observes its slice ACTIVE (the attempt ends), the slice later restarts and re-drives a fresh attempt
        commit(SliceState.ACTIVE);
        activeState().remediateStuckRouting(seed(SliceState.ROUTING));
        commit(SliceState.ROUTING);
        seed(SliceState.ROUTING);
        cluster.batches.clear();

        activeState().remediateStuckRouting(key);

        assertThat(refCount(streamRefPuts(cluster.batches.getFirst()).getFirst()))
                .as("a new attempt reads the mirror afresh: the earlier attempt's reference is already counted, this is the second")
                .isEqualTo(2);
    }

    private static List<KVCommand<AetherKey>> streamRefPuts(List<KVCommand<AetherKey>> batch) {
        return batch.stream().filter(command -> command.key() instanceof StreamRegistryKey).toList();
    }

    private static int refCount(KVCommand<AetherKey> command) {
        return ((StreamRegistryValue) ((KVCommand.Put<AetherKey, ?>) command).value()).entry().refCount();
    }

    private SliceNodeKey servingSeed(SliceState state) {
        serving.add(ARTIFACT);

        return seed(state);
    }

    private List<NodeArtifactValue> activePuts() {
        return cluster.batches.stream()
                              .flatMap(List::stream)
                              .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                 && put.key() instanceof NodeArtifactKey
                                                 && put.value() instanceof NodeArtifactValue value
                                                 && value.state() == SliceState.ACTIVE)
                              .map(command -> (NodeArtifactValue) ((KVCommand.Put<AetherKey, ?>) command).value())
                              .toList();
    }

    /// Capture the WARN lines of the ACTIVE state's logger; the returned runnable detaches the appender.
    private static Runnable capturingWarnings(List<String> sink) {
        var context = (LoggerContext) LogManager.getContext(false);
        var loggerConfig = context.getConfiguration().getLoggerConfig(NodeDeploymentState.Active.class.getName());
        var appender = new AbstractAppender("stuck-reconcile-capture", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
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

    private void assertFullLastWrite() {
        assertThat(cluster.batches).as("exactly one re-driven batch").hasSize(1);
        var batch = cluster.batches.getFirst();

        assertThat(batch.stream()
                        .anyMatch(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                             && put.key() instanceof NodeArtifactKey
                                             && put.value() instanceof NodeArtifactValue value
                                             && value.state() == SliceState.ACTIVE))
                .as("the batch carries the ACTIVE put")
                .isTrue();
        assertThat(batch.stream().anyMatch(command -> command.key() instanceof StreamRegistryKey))
                .as("the SAME batch carries the stream-ref commands: a bare ACTIVE would leave the refcounts unacquired")
                .isTrue();
    }

    private void commit(SliceState state) {
        applyToKv(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(SELF, ARTIFACT), NodeArtifactValue.nodeArtifactValue(state)));
    }

    private void applyToKv(KVCommand<AetherKey> command) {
        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    private SliceNodeKey seed(SliceState state) {
        var key = SliceNodeKey.sliceNodeKey(ARTIFACT, SELF);

        activeState().deployments().put(key, NodeDeploymentManager.SliceDeployment.sliceDeployment(key, state, 0L));

        return key;
    }

    private SliceState localState(SliceNodeKey key) {
        return activeState().deployments().get(key).state();
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> kv) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                store,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                kv,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    /// A non-`Slice` marker interface whose only job is to name `META-INF/slice/ReconcileStreamMarkerSlice.manifest`.
    private interface ReconcileStreamMarkerSlice {}

    private static final class StreamStub implements Slice, ReconcileStreamMarkerSlice {
        @Override public List<org.pragmatica.aether.slice.SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    private static final class StreamStubStore implements SliceStore {
        private final List<LoadedSlice> loaded = new java.util.concurrent.CopyOnWriteArrayList<>();

        static LoadedSlice loadedSlice(Artifact artifact) {
            var slice = new StreamStub();

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
            return List.copyOf(loaded);
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
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

    /// Records every submitted batch whole, in submission order.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<List<KVCommand<AetherKey>>> batches = new java.util.concurrent.CopyOnWriteArrayList<>();

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            batches.add(List.copyOf(batch));

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
                return serving.contains(artifact)
                       ? Option.some(stubBridge())
                       : Option.none();
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
                return List.of();
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
