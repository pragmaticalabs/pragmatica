package org.pragmatica.aether.deployment.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.SliceStore.LoadedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent.*;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.*;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
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
    void onSliceNodePut_ignoresCommand_beforeQuorumEstablished() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Send ValuePut before quorum - should be ignored
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    @Test
    void onSliceNodePut_processesCommand_afterQuorumEstablished() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Establish quorum
        manager.onQuorumStateChange(QuorumStateNotification.established());

        // Now ValuePut should be processed
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    @Test
    void onSliceNodePut_ignoresCommand_afterQuorumDisappeared() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Establish then lose quorum
        manager.onQuorumStateChange(QuorumStateNotification.established());
        manager.onQuorumStateChange(QuorumStateNotification.disappeared());

        // ValuePut should be ignored again
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    // === Key Filtering Tests ===

    @Test
    void onSliceNodePut_ignoresKey_forOtherNodes() {
        var artifact = createTestArtifact();
        var otherNode = NodeId.randomNodeId();
        var key = new SliceNodeKey(artifact, otherNode);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    @Test
    void onSliceNodePut_processesKey_forOwnNode() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    // === State Transition Tests ===

    @Test
    void onSliceNodePut_triggersLoading_forLoadState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    @Test
    void onSliceNodePut_triggersActivation_forActivateState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Slice must be loaded before it can be activated - with mock slice for methods()
        sliceStore.markAsLoadedWithSlice(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.ACTIVATE);

        assertThat(sliceStore.activateCalls).containsExactly(artifact);
    }

    @Test
    void onSliceNodePut_triggersDeactivation_forDeactivateState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Slice must be loaded for deactivation to find it - with mock slice for methods()
        sliceStore.markAsLoadedWithSlice(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.DEACTIVATE);

        assertThat(sliceStore.deactivateCalls).containsExactly(artifact);
    }

    @Test
    void onSliceNodePut_triggersUnloading_forUnloadState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.UNLOAD);

        assertThat(sliceStore.unloadCalls).containsExactly(artifact);
    }

    @Test
    void onSliceNodePut_ignoresTransitionalStates() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());

        // Transitional states should not trigger any action
        sendValuePut(key, SliceState.LOADING);
        sendValuePut(key, SliceState.ACTIVATING);
        sendValuePut(key, SliceState.DEACTIVATING);
        sendValuePut(key, SliceState.UNLOADING);

        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.activateCalls).isEmpty();
        assertThat(sliceStore.deactivateCalls).isEmpty();
        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    @Test
    void onSliceNodePut_recordsButNoAction_forLoadedState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.LOADED);

        // LOADED is a stable state, no action required
        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.activateCalls).isEmpty();
    }

    @Test
    void onSliceNodePut_recordsButNoAction_forActiveState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.ACTIVE);

        // ACTIVE is a stable state, no action required
        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.deactivateCalls).isEmpty();
    }

    @Test
    void onSliceNodePut_recordsButNoAction_forFailedState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        sendValuePut(key, SliceState.FAILED);

        // FAILED awaits UNLOAD command
        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    // === Consensus Integration Tests ===

    @Test
    void onSliceNodePut_transitionsToLoaded_afterSuccessfulLoad() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        // First command is lifecycle ON_DUTY registration
        var sliceCommandOffset = clusterNode.appliedCommands.size();
        sendValuePut(key, SliceState.LOAD);

        // Now writes LOADING first, then LOADED after success (after lifecycle command)
        assertThat(clusterNode.appliedCommands).hasSize(sliceCommandOffset + 2);

        var loadingCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.get(sliceCommandOffset);
        assertThat(loadingCommand.key()).isEqualTo(key);
        assertThat(loadingCommand.value()).isEqualTo(new SliceNodeValue(SliceState.LOADING));

        var loadedCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.get(sliceCommandOffset + 1);
        assertThat(loadedCommand.key()).isEqualTo(key);
        assertThat(loadedCommand.value()).isEqualTo(new SliceNodeValue(SliceState.LOADED));
    }

    @Test
    void onSliceNodePut_transitionsToActive_afterSuccessfulActivation() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Slice must be loaded before it can be activated - with mock slice that has methods()
        sliceStore.markAsLoadedWithSlice(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        // First command is lifecycle ON_DUTY registration
        var sliceCommandOffset = clusterNode.appliedCommands.size();
        sendValuePut(key, SliceState.ACTIVATE);

        // Now writes ACTIVATING first, then ACTIVE after success (plus endpoint publish commands, after lifecycle command)
        assertThat(clusterNode.appliedCommands).hasSizeGreaterThanOrEqualTo(sliceCommandOffset + 2);

        var activatingCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.get(sliceCommandOffset);
        assertThat(activatingCommand.key()).isEqualTo(key);
        assertThat(activatingCommand.value()).isEqualTo(new SliceNodeValue(SliceState.ACTIVATING));

        // Find the ACTIVE state command (may be after endpoint commands)
        var activeCommand = clusterNode.appliedCommands.stream()
            .filter(cmd -> cmd instanceof KVCommand.Put)
            .map(cmd -> (KVCommand.Put<AetherKey, AetherValue>) cmd)
            .filter(cmd -> cmd.value().equals(new SliceNodeValue(SliceState.ACTIVE)))
            .findFirst()
            .orElseThrow();
        assertThat(activeCommand.key()).isEqualTo(key);
    }

    @Test
    void onSliceNodePut_transitionsToLoaded_afterSuccessfulDeactivation() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());
        // First command is lifecycle ON_DUTY registration
        var sliceCommandOffset = clusterNode.appliedCommands.size();
        sendValuePut(key, SliceState.DEACTIVATE);

        assertThat(clusterNode.appliedCommands).hasSize(sliceCommandOffset + 1);
        var putCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.get(sliceCommandOffset);
        assertThat(putCommand.value()).isEqualTo(new SliceNodeValue(SliceState.LOADED));
    }

    @Test
    void onSliceNodePut_transitionsToFailed_afterFailedLoad() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        sliceStore.failNextLoad = true;

        manager.onQuorumStateChange(QuorumStateNotification.established());
        // First command is lifecycle ON_DUTY registration
        var sliceCommandOffset = clusterNode.appliedCommands.size();
        sendValuePut(key, SliceState.LOAD);

        // Now writes LOADING first, then FAILED after failure (after lifecycle command)
        assertThat(clusterNode.appliedCommands).hasSize(sliceCommandOffset + 2);

        var loadingCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.get(sliceCommandOffset);
        assertThat(loadingCommand.value()).isEqualTo(new SliceNodeValue(SliceState.LOADING));

        var failedCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.get(sliceCommandOffset + 1);
        assertThat(failedCommand.value()).isEqualTo(new SliceNodeValue(SliceState.FAILED));
    }

    // === ValueRemove Tests ===

    @Test
    void onSliceNodeRemove_triggersCleanup_forActiveSlice() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());

        // First, record an ACTIVE state
        sendValuePut(key, SliceState.ACTIVE);
        sliceStore.deactivateCalls.clear(); // Clear the list

        // Now send remove
        sendValueRemove(key);

        // Should trigger force cleanup (deactivate + unload)
        assertThat(sliceStore.deactivateCalls).containsExactly(artifact);
    }

    @Test
    void onSliceNodeRemove_noCleanup_forNonActiveSlice() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.established());

        // Record LOADED state (not ACTIVE)
        sendValuePut(key, SliceState.LOADED);

        // Now send remove
        sendValueRemove(key);

        // No force cleanup needed
        assertThat(sliceStore.deactivateCalls).isEmpty();
    }

    @Test
    void onSliceNodeRemove_ignoresCommand_inDormantState() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Don't establish quorum
        sendValueRemove(key);

        assertThat(sliceStore.deactivateCalls).isEmpty();
        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    @Test
    void onQuorumStateChange_reversedDeliveryOrder_ignoresStaleDisappeared() {
        // Reset for proper simulation
        setUp();

        // Create notifications in the order they would be created by processViewChange
        // Thread A (ADD operation) creates ESTABLISHED first (lower seq)
        var earlyEstablished = QuorumStateNotification.established();
        // Thread B (REMOVE operation) creates DISAPPEARED second (higher seq)
        var lateDisappeared = QuorumStateNotification.disappeared();

        // But Thread B delivers first (lighter handler chain)
        manager.onQuorumStateChange(lateDisappeared);
        assertThat(manager.isActive()).isFalse();

        // Thread A delivers second - should be REJECTED because it has lower sequence
        manager.onQuorumStateChange(earlyEstablished);
        assertThat(manager.isActive()).isFalse(); // Still dormant - stale ESTABLISHED was ignored
    }

    // === Helper Methods ===

    private Artifact createTestArtifact() {
        return Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    private void sendValuePut(SliceNodeKey key, SliceState state) {
        var value = new SliceNodeValue(state);
        var command = new KVCommand.Put<SliceNodeKey, SliceNodeValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        manager.onSliceNodePut(notification);
    }

    private void sendValueRemove(SliceNodeKey key) {
        var command = new KVCommand.Remove<SliceNodeKey>(key);
        var notification = new ValueRemove<SliceNodeKey, SliceNodeValue>(command, Option.none());
        manager.onSliceNodeRemove(notification);
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

        // Helper to simulate a pre-loaded slice without calling loadSlice
        void markAsLoaded(Artifact artifact) {
            loadedSlices.add(new TestLoadedSlice(artifact, null));
        }

        // Helper to simulate a pre-loaded slice with a mock slice that has methods()
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

    // Mock slice that returns empty methods list for testing
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
        public void onInvokeRequest(org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest request) {
            // Not used in these tests
        }

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
