package org.pragmatica.aether.deployment.node.fsm;

import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.ConfigChanged;
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
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
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

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2162: a slice replica unloading must not remove the cluster-scoped `StreamRegistrationKey` while a peer still
/// hosts the artifact (the scheduled-task unpublish is guarded the same way). #2163: a consensus write failure of the
/// LOADING or LOADED transition is infrastructure, not a verdict on the artifact, so it is not recorded fatal; a genuine
/// load failure still is (#930). #2164: a slice reactivated after a quorum flap still receives runtime config changes.
/// Stream registrations and refs need no re-publication on reactivation (cluster KV state / refcounts that suspension
/// does not touch): the refuted arm of #2164 is pinned by `streamRegistrationSurvivesQuorumFlap_withoutRepublish`.
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStateStreamGuardAndLoadChainTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final MethodName EXECUTE = MethodName.methodName("execute").unwrap();
    private static final SliceNodeKey SLICE_KEY = SliceNodeKey.sliceNodeKey(ARTIFACT, SELF);
    private static final String TOML = """
            [streams.orders]
            partitions = 4
            """;

    interface DepFsmStreamMarkerSlice {}

    interface DepFsmConfigMarkerSlice {}

    static final class StreamSlice implements Slice, DepFsmStreamMarkerSlice {
        @Override public List<SliceMethod<?, ?>> methods() {return List.of();}
    }

    static final class ConfigSlice implements Slice, DepFsmConfigMarkerSlice {
        @Override public List<SliceMethod<?, ?>> methods() {return List.of();}
    }

    public static final class ConfigProbeFactory {
        static final List<String> calls = new CopyOnWriteArrayList<>();

        public static void notifyConfigUpdate(Object sliceInstance, String section, org.pragmatica.aether.slice.ConfigFacade config) {
            calls.add(section);
        }
    }

    private KVStore<AetherKey, AetherValue> kvStore;
    private RecordingClusterNode cluster;
    private ProbeStore store;
    private final Set<Artifact> registered = ConcurrentHashMap.newKeySet();
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        ConfigService.setInstance(ProviderBasedConfigService.providerBasedConfigService(
                ConfigurationProvider.builder().withSource(TomlConfigSource.tomlConfigSource(TOML).unwrap()).build()));
        ConfigProbeFactory.calls.clear();
        var router = MessageRouter.mutable();
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
                                                                      SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 1)))));
        cluster = new RecordingClusterNode(SELF);
        store = new ProbeStore();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> buildContext(fsm, router);
        harness = FsmTestHarness.harness("depfsm-fix-" + SELF.id() + "-" + System.nanoTime(), factory);
        harness.dispatch(new QuorumEstablished());
    }

    @AfterEach
    void tearDown() {
        ConfigService.clear();
    }

    // ---------------------------------------------------------------- #2162

    /// CONTROL (harness can observe a stream removal): the only hosting replica unloads -> registrations go.
    @Test
    void lastReplicaUnload_removesStreamRegistrations() throws Exception {
        store.slice = new StreamSlice();
        invoke("doPublishStreamSubscriptions", new StreamSlice());
        assertThat(liveStreamRegistrations()).as("precondition: published").hasSize(1);
        seed(SELF, SliceState.ACTIVE);

        invoke("doUnpublishStreamSubscriptions", new StreamSlice());

        assertThat(liveStreamRegistrations()).as("last replica: registration removed").isEmpty();
    }

    /// CONTROL (the sibling the suspect is compared with): scheduled-task unpublish IS guarded.
    @Test
    void scheduledTaskUnpublish_withPeerHosting_removesNothing() throws Exception {
        seed(SELF, SliceState.ACTIVE);
        seed(OTHER, SliceState.ACTIVE);

        invoke("doUnpublishScheduledTasks", new StreamSlice());

        assertThat(cluster.commands.stream().filter(c -> c instanceof KVCommand.Remove<AetherKey> r && r.key() instanceof ScheduledTaskKey).toList())
                .as("scheduled task is guarded by artifactHostedElsewhere").isEmpty();
    }

    /// PROBE: OTHER still hosts the slice; SELF unloads. The cluster-scoped registration must survive.
    @Test
    void peerStillHosts_streamRegistrationSurvivesThisReplicasUnload() throws Exception {
        invoke("doPublishStreamSubscriptions", new StreamSlice());
        assertThat(liveStreamRegistrations()).as("precondition: published").hasSize(1);
        seed(SELF, SliceState.ACTIVE);
        seed(OTHER, SliceState.ACTIVE);

        invoke("doUnpublishStreamSubscriptions", new StreamSlice());

        assertThat(liveStreamRegistrations())
                .as("OTHER still hosts %s: removing the cluster-scoped StreamRegistrationKey stops its stream consumption", ARTIFACT)
                .hasSize(1);
    }

    // ---------------------------------------------------------------- #2163

    /// CONTROL: a genuine load failure (untyped) keeps its ruled PERMANENT disposition (#930) -> fatal.
    @Test
    void genuineLoadFailure_isStillFatal() {
        store.loadFailure = Causes.cause("malformed manifest");

        harness.dispatch(new NodeArtifactPutReceived(put(SliceState.LOAD)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(failedPuts()).isNotEmpty());
        assertThat(failedPuts().getFirst().fatal()).as("a bad artifact is PERMANENT per #930").isTrue();
    }

    /// PROBE: the LOADING consensus write itself fails (infrastructure), the artifact is fine.
    @Test
    void loadingWriteFails_isNotFatal() {
        cluster.refuse = c -> c instanceof KVCommand.Put<AetherKey, ?> p && p.value() instanceof NodeArtifactValue v && v.state() == SliceState.LOADING;

        harness.dispatch(new NodeArtifactPutReceived(put(SliceState.LOAD)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(failedPuts()).isNotEmpty());
        assertThat(failedPuts().getFirst().fatal())
                .as("the artifact never loaded: the LOADING consensus write failed (%s) -- infrastructure, must not condemn the artifact",
                    failedPuts().getFirst().failureReason().or("?"))
                .isFalse();
    }

    /// PROBE: same for the LOADED write (load succeeded, write of LOADED fails).
    @Test
    void loadedWriteFails_isNotFatal() {
        cluster.refuse = c -> c instanceof KVCommand.Put<AetherKey, ?> p && p.value() instanceof NodeArtifactValue v && v.state() == SliceState.LOADED;

        harness.dispatch(new NodeArtifactPutReceived(put(SliceState.LOAD)));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(failedPuts()).isNotEmpty());
        assertThat(failedPuts().getFirst().fatal())
                .as("the artifact loaded: the LOADED consensus write failed (%s) -- infrastructure, must not condemn the artifact",
                    failedPuts().getFirst().failureReason().or("?"))
                .isFalse();
    }

    // ---------------------------------------------------------------- #2164

    /// CONTROL + REFUTATION of the stream-subscription arm: the registration is cluster KV state; suspension writes
    /// nothing and reactivation neither removes nor needs to re-put it, so it is still live afterwards.
    @Test
    void streamRegistrationSurvivesQuorumFlap_withoutRepublish() throws Exception {
        store.slice = new StreamSlice();
        invoke("doPublishStreamSubscriptions", new StreamSlice());
        assertThat(liveStreamRegistrations()).hasSize(1);
        store.loadSlice(ARTIFACT);
        seedActiveDeployment();
        var before = cluster.commands.size();

        harness.dispatch(new QuorumDisappeared());
        harness.dispatch(new QuorumEstablished());
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(registered).contains(ARTIFACT));
        await().during(300, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(failedPuts()).isEmpty());

        assertThat(liveStreamRegistrations()).as("stream registration still live after the flap").hasSize(1);
        assertThat(cluster.commands.subList(before, cluster.commands.size()).stream()
                          .filter(c -> c instanceof KVCommand.Remove<AetherKey> r && r.key() instanceof StreamRegistrationKey).toList())
                .as("the flap removed no stream registration").isEmpty();
    }

    /// CONTROL: before any flap, ConfigChanged reaches the slice registered by the activation chain.
    /// PROBE (second half): after suspend + reactivate, ConfigChanged must still reach the slice.
    @Test
    void configChangeStillReachesSliceAfterQuorumFlap() {
        store.slice = new ConfigSlice();
        harness.dispatch(new NodeArtifactPutReceived(put(SliceState.ACTIVE)));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(activePuts()).as("chain reached ACTIVE").isNotEmpty());
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(ConfigProbeFactory.calls).as("initial notify").isNotEmpty());
        ConfigProbeFactory.calls.clear();

        harness.dispatch(new ConfigChanged("database.pool_size"));
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(ConfigProbeFactory.calls).as("CONTROL: before the flap").containsExactly("database"));

        seedActiveDeployment();
        harness.dispatch(new QuorumDisappeared());
        harness.dispatch(new QuorumEstablished());
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(registered).as("bridge restored").contains(ARTIFACT));
        await().during(300, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(failedPuts()).isEmpty());
        ConfigProbeFactory.calls.clear();

        harness.dispatch(new ConfigChanged("database.pool_size"));

        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(ConfigProbeFactory.calls)
                       .as("a reactivated slice must still receive runtime config changes for its declared section")
                       .containsExactly("database"));
    }

    // ---------------------------------------------------------------- helpers

    private void seedActiveDeployment() {
        ((NodeDeploymentState.Active) harness.state()).deployments()
                .put(SLICE_KEY, NodeDeploymentManager.SliceDeployment.sliceDeployment(SLICE_KEY, SliceState.ACTIVE, 0L));
    }

    private Set<StreamRegistrationKey> liveStreamRegistrations() {
        return cluster.live.stream().filter(k -> k instanceof StreamRegistrationKey).map(k -> (StreamRegistrationKey) k)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void seed(NodeId node, SliceState state) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(node, ARTIFACT),
                                                                      NodeArtifactValue.nodeArtifactValue(state)))));
    }

    private Object invoke(String name, Slice slice) throws Exception {
        Method m = NodeDeploymentState.Active.class.getDeclaredMethod(name, Artifact.class, Slice.class);
        m.setAccessible(true);
        var result = m.invoke(harness.state(), ARTIFACT, slice);
        if (result instanceof Promise<?> p) {
            p.await();
        }
        return result;
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> put(SliceState state) {
        return new ValuePut<>(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(SELF, ARTIFACT), NodeArtifactValue.nodeArtifactValue(state)),
                              Option.none());
    }

    private List<NodeArtifactValue> failedPuts() {
        return cluster.nodeArtifactPuts().stream().filter(v -> v.state() == SliceState.FAILED).toList();
    }

    private List<NodeArtifactValue> activePuts() {
        return cluster.nodeArtifactPuts().stream().filter(v -> v.state() == SliceState.ACTIVE).toList();
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm, MessageRouter router) {
        return new NodeDeploymentContext(fsm, SELF, new NodeAddress("localhost", 9000), store, SliceActionConfig.sliceActionConfig(),
                                         SliceCodec.sliceCodec(List.of()), cluster, kvStore, stubInvocationHandler(), router,
                                         Option.none(), Option.none(), timeSpan(120_000).millis(), timeSpan(10).millis()).dormant();
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        final List<KVCommand<AetherKey>> commands = new CopyOnWriteArrayList<>();
        final Set<AetherKey> live = ConcurrentHashMap.newKeySet();
        volatile Predicate<KVCommand<AetherKey>> refuse = c -> false;

        RecordingClusterNode(NodeId self) {this.self = self;}

        List<NodeArtifactValue> nodeArtifactPuts() {
            return commands.stream()
                           .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> p && p.key() instanceof NodeArtifactKey && p.value() instanceof NodeArtifactValue)
                           .map(c -> (NodeArtifactValue) ((KVCommand.Put<AetherKey, ?>) c).value())
                           .toList();
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            if (batch.stream().anyMatch(refuse)) {
                return Causes.cause("consensus apply failed (injected infrastructure fault)").promise();
            }
            commands.addAll(batch);
            for (var c : batch) {
                if (c instanceof KVCommand.Put<AetherKey, ?> p) {live.add(p.key());}
                if (c instanceof KVCommand.Remove<AetherKey> r) {live.remove(r.key());}
            }
            return Promise.success(List.of());
        }
    }

    private static final class ProbeStore implements SliceStore {
        private final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();
        volatile Slice slice = null;
        volatile org.pragmatica.lang.Cause loadFailure = null;

        private LoadedSlice loadedSlice(Artifact artifact) {
            var method = SliceMethod.sliceMethod(EXECUTE, (Unit unit) -> Promise.unitPromise(), TypeToken.typeToken(Unit.class), TypeToken.typeToken(Unit.class)).unwrap();
            Slice s = slice != null ? slice : () -> List.of(method);
            return new LoadedSlice() {
                @Override public Artifact artifact() {return artifact;}

                @Override public Slice slice() {return s;}
            };
        }

        @Override public List<LoadedSlice> loaded() {return List.copyOf(loadedSlices);}

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            if (loadFailure != null) {
                return loadFailure.promise();
            }
            var ls = loadedSlice(artifact);
            loadedSlices.add(ls);
            return Promise.success(ls);
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {return Promise.success(loadedSlice(artifact));}

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {return Promise.success(loadedSlice(artifact));}

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {return Promise.unitPromise();}

        @Override public Option<ConfigurationProvider> sliceComposite(Artifact artifact) {return Option.none();}
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));}

            @Override public Option<NodeInfo> get(NodeId id) {return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));}

            @Override public int clusterSize() {return 1;}

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}

            @Override public TimeSpan pingInterval() {return timeSpan(5).seconds();}

            @Override public TimeSpan helloTimeout() {return timeSpan(5).seconds();}

            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}

            @Override public List<NodeId> topology() {return List.of(self);}
        };
    }

    private InvocationHandler stubInvocationHandler() {
        return new InvocationHandler() {
            @Override public void onInvokeRequest(InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, SliceBridge bridge) {registered.add(artifact);}

            @Override public void unregisterSlice(Artifact artifact) {registered.remove(artifact);}

            @Override public Option<SliceBridge> localSlice(Artifact artifact) {return Option.none();}

            @Override public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {return Option.none();}

            @Override public Option<InvocationMetricsCollector> metricsCollector() {return Option.none();}
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {return null;}
        };
    }
}
