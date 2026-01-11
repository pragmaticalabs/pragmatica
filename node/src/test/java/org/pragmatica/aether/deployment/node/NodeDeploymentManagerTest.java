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
    void manager_starts_in_dormant_state() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Send ValuePut before quorum - should be ignored
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    @Test
    void manager_activates_on_quorum_established() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Establish quorum
        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);

        // Now ValuePut should be processed
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    @Test
    void manager_returns_to_dormant_on_quorum_disappeared() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Establish then lose quorum
        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        manager.onQuorumStateChange(QuorumStateNotification.DISAPPEARED);

        // ValuePut should be ignored again
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    // === Key Filtering Tests ===

    @Test
    void manager_ignores_keys_for_other_nodes() {
        var artifact = createTestArtifact();
        var otherNode = NodeId.randomNodeId();
        var key = new SliceNodeKey(artifact, otherNode);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).isEmpty();
    }

    @Test
    void manager_processes_keys_for_own_node() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    // === State Transition Tests ===

    @Test
    void load_state_triggers_loading() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.LOAD);

        assertThat(sliceStore.loadCalls).containsExactly(artifact);
    }

    @Test
    void activate_state_triggers_activation() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Slice must be loaded before it can be activated
        sliceStore.markAsLoaded(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.ACTIVATE);

        assertThat(sliceStore.activateCalls).containsExactly(artifact);
    }

    @Test
    void deactivate_state_triggers_deactivation() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.DEACTIVATE);

        assertThat(sliceStore.deactivateCalls).containsExactly(artifact);
    }

    @Test
    void unload_state_triggers_unloading() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.UNLOAD);

        assertThat(sliceStore.unloadCalls).containsExactly(artifact);
    }

    @Test
    void transitional_states_are_ignored() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);

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
    void loaded_state_is_recorded_but_no_action() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.LOADED);

        // LOADED is a stable state, no action required
        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.activateCalls).isEmpty();
    }

    @Test
    void active_state_is_recorded_but_no_action() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.ACTIVE);

        // ACTIVE is a stable state, no action required
        assertThat(sliceStore.loadCalls).isEmpty();
        assertThat(sliceStore.deactivateCalls).isEmpty();
    }

    @Test
    void failed_state_is_recorded_but_no_action() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.FAILED);

        // FAILED awaits UNLOAD command
        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    // === Consensus Integration Tests ===

    @Test
    void successful_load_transitions_to_loaded_state() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.LOAD);

        // After successful load, should submit LOADED state to consensus
        assertThat(clusterNode.appliedCommands).hasSize(1);
        var command = clusterNode.appliedCommands.getFirst();
        assertThat(command).isInstanceOf(KVCommand.Put.class);

        var putCommand = (KVCommand.Put<AetherKey, AetherValue>) command;
        assertThat(putCommand.key()).isEqualTo(key);
        assertThat(putCommand.value()).isEqualTo(new SliceNodeValue(SliceState.LOADED));
    }

    @Test
    void successful_activation_transitions_to_active_state() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Slice must be loaded before it can be activated
        sliceStore.markAsLoaded(artifact);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.ACTIVATE);

        assertThat(clusterNode.appliedCommands).hasSize(1);
        var putCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.getFirst();
        assertThat(putCommand.value()).isEqualTo(new SliceNodeValue(SliceState.ACTIVE));
    }

    @Test
    void successful_deactivation_transitions_to_loaded_state() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.DEACTIVATE);

        assertThat(clusterNode.appliedCommands).hasSize(1);
        var putCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.getFirst();
        assertThat(putCommand.value()).isEqualTo(new SliceNodeValue(SliceState.LOADED));
    }

    @Test
    void failed_load_transitions_to_failed_state() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        sliceStore.failNextLoad = true;

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);
        sendValuePut(key, SliceState.LOAD);

        assertThat(clusterNode.appliedCommands).hasSize(1);
        var putCommand = (KVCommand.Put<AetherKey, AetherValue>) clusterNode.appliedCommands.getFirst();
        assertThat(putCommand.value()).isEqualTo(new SliceNodeValue(SliceState.FAILED));
    }

    // === ValueRemove Tests ===

    @Test
    void value_remove_for_active_slice_triggers_cleanup() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);

        // First, record an ACTIVE state
        sendValuePut(key, SliceState.ACTIVE);
        sliceStore.deactivateCalls.clear(); // Clear the list

        // Now send remove
        sendValueRemove(key);

        // Should trigger force cleanup (deactivate + unload)
        assertThat(sliceStore.deactivateCalls).containsExactly(artifact);
    }

    @Test
    void value_remove_for_non_active_slice_no_cleanup() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        manager.onQuorumStateChange(QuorumStateNotification.ESTABLISHED);

        // Record LOADED state (not ACTIVE)
        sendValuePut(key, SliceState.LOADED);

        // Now send remove
        sendValueRemove(key);

        // No force cleanup needed
        assertThat(sliceStore.deactivateCalls).isEmpty();
    }

    @Test
    void value_remove_in_dormant_state_is_ignored() {
        var artifact = createTestArtifact();
        var key = new SliceNodeKey(artifact, self);

        // Don't establish quorum
        sendValueRemove(key);

        assertThat(sliceStore.deactivateCalls).isEmpty();
        assertThat(sliceStore.unloadCalls).isEmpty();
    }

    // === Helper Methods ===

    private Artifact createTestArtifact() {
        return Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    private void sendValuePut(SliceNodeKey key, SliceState state) {
        var value = new SliceNodeValue(state);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        manager.onValuePut(notification);
    }

    private void sendValueRemove(SliceNodeKey key) {
        var command = new KVCommand.Remove<AetherKey>(key);
        var notification = new ValueRemove<AetherKey, AetherValue>(command, Option.none());
        manager.onValueRemove(notification);
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
            return Promise.success(Unit.unit());
        }

        @Override
        public List<LoadedSlice> loaded() {
            return List.copyOf(loadedSlices);
        }

        // Helper to simulate a pre-loaded slice without calling loadSlice
        void markAsLoaded(Artifact artifact) {
            loadedSlices.add(new TestLoadedSlice(artifact, null));
        }
    }

    record TestLoadedSlice(Artifact artifact, org.pragmatica.aether.slice.Slice sliceInstance) implements LoadedSlice {
        @Override
        public org.pragmatica.aether.slice.Slice slice() {
            return sliceInstance;
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
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
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
        public Option<SliceBridge> getLocalSlice(Artifact artifact) {
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
