package org.pragmatica.aether.deployment.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.SliceStore.LoadedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class NodeDeploymentManagerTest {

    private NodeId self;
    private MessageRouter.MutableRouter router;
    private TestSliceStore sliceStore;
    private TestClusterNode clusterNode;
    private TestKVStore kvStore;
    private TestInvocationHandler invocationHandler;
    private NodeDeploymentManager manager;

    @BeforeEach
    void setUp() {
        self = NodeId.randomNodeId();
        router = MessageRouter.mutable();
        sliceStore = new TestSliceStore();
        clusterNode = new TestClusterNode(self);
        kvStore = new TestKVStore();
        invocationHandler = new TestInvocationHandler();
        manager = NodeDeploymentManager.nodeDeploymentManager(
                self, router, sliceStore, clusterNode, kvStore, invocationHandler
                                                             );
    }

    // === Quorum State Tests ===

    @Test
    void onNodeArtifactPut_ignoresCommand_beforeQuorumEstablished() {
        var artifact = createTestArtifact();

        sendNodeArtifactPut(artifact, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    @Test
    void onNodeArtifactPut_processesCommand_afterQuorumEstablished() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    @Test
    void onNodeArtifactPut_ignoresCommand_afterQuorumDisappeared() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        manager.onQuorumStateChange(QuorumStateNotification.disappeared());

        sendNodeArtifactPut(artifact, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    // === Key Filtering Tests ===

    @Test
    void onNodeArtifactPut_ignoresKey_forOtherNodes() {
        var artifact = createTestArtifact();
        var otherNode = NodeId.randomNodeId();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(otherNode, artifact, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    @Test
    void onNodeArtifactPut_processesKey_forOwnNode() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    // === State Transition Tests ===

    @Test
    void onNodeArtifactPut_triggersLoading_forLoadState() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    @Test
    void onNodeArtifactPut_triggersActivation_forActivateState() {
        var artifact = createTestArtifact();

        sliceStore.markAsLoadedWithSlice(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.ACTIVATE);

        assertThat(sliceStore.activateCalls).containsExactly(artifact);
    }

    @Test
    void onNodeArtifactPut_triggersDeactivation_forDeactivateState() {
        var artifact = createTestArtifact();

        sliceStore.markAsLoadedWithSlice(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.DEACTIVATE);

        assertThat(sliceStore.deactivateCalls).containsExactly(artifact);
    }

    @Test
    void onNodeArtifactPut_triggersUnloading_forUnloadState() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.UNLOAD);

        assertThat(sliceStore.unloadCalls).containsExactly(artifact);
    }

    @Test
    void onNodeArtifactPut_ignoresTransitionalStates() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());

        sendNodeArtifactPut(artifact, SliceState.LOADING);
        sendNodeArtifactPut(artifact, SliceState.ACTIVATING);
        sendNodeArtifactPut(artifact, SliceState.DEACTIVATING);
        sendNodeArtifactPut(artifact, SliceState.UNLOADING);

        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.activateCalls).isEmpty();
        assertThat(sliceStore.deactivateCalls).isEmpty();
        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    @Test
    void onNodeArtifactPut_recordsButNoAction_forLoadedState() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.LOADED);

        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.activateCalls).isEmpty();
    }

    @Test
    void onNodeArtifactPut_recordsButNoAction_forActiveState() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.ACTIVE);

        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.deactivateCalls).isEmpty();
    }

    @Test
    void onNodeArtifactPut_recordsButNoAction_forFailedState() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.FAILED);

        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    // === Consensus Integration Tests ===

    @Test
    void onNodeArtifactPut_transitionsToLoaded_afterSuccessfulLoad() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendNodeArtifactPut(artifact, SliceState.LOAD);

        // State transitions written via consensus
        assertThat(clusterNode.appliedCommands).hasSizeGreaterThanOrEqualTo(2);
        assertContainsNodeArtifactState(SliceState.LOADING);
        assertContainsNodeArtifactState(SliceState.LOADED);
    }

    @Test
    void onNodeArtifactPut_transitionsToActive_afterSuccessfulActivation() {
        var artifact = createTestArtifact();

        sliceStore.markAsLoadedWithSlice(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        clusterNode.appliedCommands.clear();
        sendNodeArtifactPut(artifact, SliceState.ACTIVATE);

        assertContainsNodeArtifactState(SliceState.ACTIVATING);
        assertContainsNodeArtifactState(SliceState.ACTIVE);
    }

    @Test
    void onNodeArtifactPut_transitionsToLoaded_afterSuccessfulDeactivation() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());
        clusterNode.appliedCommands.clear();
        sendNodeArtifactPut(artifact, SliceState.DEACTIVATE);

        assertContainsNodeArtifactState(SliceState.LOADED);
    }

    @Test
    void onNodeArtifactPut_transitionsToFailed_afterFailedLoad() {
        var artifact = createTestArtifact();

        sliceStore.failNextLoad = true;

        manager.onQuorumStateChange(QuorumStateNotification.established());
        clusterNode.appliedCommands.clear();
        sendNodeArtifactPut(artifact, SliceState.LOAD);

        assertContainsNodeArtifactState(SliceState.LOADING);
        assertContainsNodeArtifactState(SliceState.FAILED);
    }

    // === ValueRemove Tests ===

    @Test
    void onNodeArtifactRemove_triggersCleanup_forActiveSlice() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());

        sendNodeArtifactPut(artifact, SliceState.ACTIVE);
        sliceStore.deactivateCalls.clear();

        sendNodeArtifactRemove(artifact);

        assertThat(sliceStore.deactivateCalls).containsExactly(artifact);
    }

    @Test
    void onNodeArtifactRemove_noCleanup_forNonActiveSlice() {
        var artifact = createTestArtifact();

        manager.onQuorumStateChange(QuorumStateNotification.established());

        sendNodeArtifactPut(artifact, SliceState.LOADED);

        sendNodeArtifactRemove(artifact);

        assertThat(sliceStore.deactivateCalls).isEmpty();
    }

    @Test
    void onNodeArtifactRemove_ignoresCommand_inDormantState() {
        var artifact = createTestArtifact();

        sendNodeArtifactRemove(artifact);

        assertThat(sliceStore.deactivateCalls).isEmpty();
        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    @Test
    void onQuorumStateChange_reversedDeliveryOrder_ignoresStaleDisappeared() {
        setUp();

        var earlyEstablished = QuorumStateNotification.established();
        var lateDisappeared = QuorumStateNotification.disappeared();

        manager.onQuorumStateChange(lateDisappeared);
        assertThat(manager.isActive()).isFalse();

        manager.onQuorumStateChange(earlyEstablished);
        assertThat(manager.isActive()).isFalse();
    }

    // === Helper Methods ===

    private Artifact createTestArtifact() {
        return Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    private void sendNodeArtifactPut(Artifact artifact, SliceState state) {
        sendNodeArtifactPut(self, artifact, state);
    }

    private void sendNodeArtifactPut(NodeId nodeId, Artifact artifact, SliceState state) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var value = NodeArtifactValue.nodeArtifactValue(state);
        var command = new KVCommand.Put<NodeArtifactKey, NodeArtifactValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        manager.onNodeArtifactPut(notification);
    }

    private void sendNodeArtifactRemove(Artifact artifact) {
        var key = NodeArtifactKey.nodeArtifactKey(self, artifact);
        var command = new KVCommand.Remove<NodeArtifactKey>(key);
        var notification = new ValueRemove<NodeArtifactKey, NodeArtifactValue>(command, Option.none());
        manager.onNodeArtifactRemove(notification);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertContainsNodeArtifactState(SliceState expectedState) {
        var found = clusterNode.appliedCommands.stream()
                                .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof NodeArtifactKey)
                                .map(cmd -> ((KVCommand.Put) cmd).value())
                                .map(NodeArtifactValue.class::cast)
                                .anyMatch(value -> value.state() == expectedState);
        assertThat(found).as("Expected NodeArtifactValue with state " + expectedState).isTrue();
    }

    // === Test Doubles ===

    static class TestSliceStore implements SliceStore {
        final List<Artifact> loadCalls = new CopyOnWriteArrayList<>();
        final List<Artifact> activateCalls = new CopyOnWriteArrayList<>();
        final List<Artifact> deactivateCalls = new CopyOnWriteArrayList<>();
        final List<Artifact> unloadCalls = new CopyOnWriteArrayList<>();
        final List<LoadedSlice> loadedSlices = new CopyOnWriteArrayList<>();
        boolean failNextLoad = false;

        @Override
        public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            loadCalls.add(artifact);
            if (failNextLoad) {
                failNextLoad = false;
                return Promise.failure(org.pragmatica.lang.utils.Causes.cause("Load failed"));
            }
            var loadedSlice = new TestLoadedSlice(artifact, null);
            loadedSlices.add(loadedSlice);
            return Promise.success(loadedSlice);
        }

        @Override
        public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            activateCalls.add(artifact);
            return Promise.success(new TestLoadedSlice(artifact, null));
        }

        @Override
        public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            deactivateCalls.add(artifact);
            return Promise.success(new TestLoadedSlice(artifact, null));
        }

        @Override
        public Promise<Unit> unloadSlice(Artifact artifact) {
            unloadCalls.add(artifact);
            loadedSlices.removeIf(ls -> ls.artifact().equals(artifact));
            return Promise.unitPromise();
        }

        @Override
        public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }

        void markAsLoaded(Artifact artifact) {
            loadedSlices.add(new TestLoadedSlice(artifact, null));
        }

        void markAsLoadedWithSlice(Artifact artifact) {
            loadedSlices.add(new TestLoadedSlice(artifact, new MockSlice()));
        }
    }

    record TestLoadedSlice(Artifact artifact, org.pragmatica.aether.slice.Slice sliceInstance) implements LoadedSlice {
        @Override
        public org.pragmatica.aether.slice.Slice slice() {
            return sliceInstance;
        }
    }

    static class MockSlice implements org.pragmatica.aether.slice.Slice {
        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public List<org.pragmatica.aether.slice.SliceMethod<?, ?>> methods() {
            return List.of();
        }
    }

    static class TestClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        final List<KVCommand<AetherKey>> appliedCommands = new CopyOnWriteArrayList<>();

        TestClusterNode(NodeId self) {
            this.self = self;
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            appliedCommands.addAll(commands);
            return Promise.success((List<R>) commands.stream().map(_ -> Unit.unit()).toList());
        }
    }

    static class TestInvocationHandler implements InvocationHandler {
        final List<Artifact> registeredSlices = new CopyOnWriteArrayList<>();
        final List<Artifact> unregisteredSlices = new CopyOnWriteArrayList<>();

        @Override
        public void onInvokeRequest(org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest request) {}

        @Override
        public void registerSlice(Artifact artifact, SliceBridge bridge) {
            registeredSlices.add(artifact);
        }

        @Override
        public void unregisterSlice(Artifact artifact) {
            unregisteredSlices.add(artifact);
        }

        @Override
        public Option<SliceBridge> localSlice(Artifact artifact) {
            return Option.none();
        }

        @Override
        public Option<org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector> metricsCollector() {
            return Option.none();
        }

        @Override
        public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
            return Option.none();
        }
    }

    static class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final java.util.Map<AetherKey, AetherValue> storage = new java.util.concurrent.ConcurrentHashMap<>();

        public TestKVStore() {
            super(null, null, null);
        }

        @Override
        public java.util.Map<AetherKey, AetherValue> snapshot() {
            return new java.util.HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        public void put(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        public void remove(AetherKey key) {
            storage.remove(key);
        }
    }
}
