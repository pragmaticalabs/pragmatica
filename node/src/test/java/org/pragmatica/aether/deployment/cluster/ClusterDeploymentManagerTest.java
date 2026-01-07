package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterDeploymentManagerTest {

    private NodeId self;
    private NodeId node2;
    private NodeId node3;
    private TestClusterNode clusterNode;
    private TestKVStore kvStore;
    private MessageRouter router;
    private ClusterDeploymentManager manager;

    @BeforeEach
    void setUp() {
        self = NodeId.randomNodeId();
        node2 = NodeId.randomNodeId();
        node3 = NodeId.randomNodeId();
        clusterNode = new TestClusterNode(self);
        kvStore = new TestKVStore();
        router = MessageRouter.mutable();
        manager = ClusterDeploymentManager.clusterDeploymentManager(self, clusterNode, kvStore, router, List.of(self, node2, node3));
    }

    // === Leader State Tests ===

    @Test
    void manager_starts_in_dormant_state() {
        var artifact = createTestArtifact();

        // Send blueprint before becoming leader - should be ignored
        sendBlueprintPut(artifact, 3);

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void manager_activates_on_becoming_leader() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 3);

        // Should issue LOAD commands
        assertThat(clusterNode.appliedCommands).hasSize(3);
    }

    @Test
    void manager_returns_to_dormant_when_no_longer_leader() {
        becomeLeader();
        addTopology(self, node2, node3);

        loseLeadership();

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 3);

        // Should be ignored in dormant state
        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    // === Blueprint Handling Tests ===

    @Test
    void blueprint_put_triggers_allocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 3);

        assertThat(clusterNode.appliedCommands).hasSize(3);
        assertAllCommandsAreLoadFor(artifact);
    }

    @Test
    void blueprint_with_zero_instances_triggers_no_allocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 0);

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void blueprint_removal_triggers_deallocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();

        // First allocate
        sendBlueprintPut(artifact, 2);
        // Simulate slices are tracked
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Then remove blueprint
        sendBlueprintRemove(artifact);

        assertThat(clusterNode.appliedCommands).hasSize(2);
        assertAllCommandsAreUnloadFor(artifact);
    }

    // === Allocation Strategy Tests ===

    @Test
    void allocation_uses_round_robin_across_nodes() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 3);

        var allocatedNodes = clusterNode.appliedCommands.stream()
                                                        .map(cmd -> ((KVCommand.Put<AetherKey, AetherValue>) cmd).key())
                                                        .map(key -> ((SliceNodeKey) key).nodeId())
                                                        .toList();

        // Should allocate to all 3 nodes
        assertThat(allocatedNodes).containsExactlyInAnyOrder(self, node2, node3);
    }

    @Test
    void allocation_wraps_around_when_instances_exceed_nodes() {
        becomeLeader();
        addTopology(self, node2);

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 4);

        // With 2 nodes and 4 instances, each node gets 2
        assertThat(clusterNode.appliedCommands).hasSize(4);
    }

    @Test
    void no_allocation_when_no_nodes_available() {
        // Create manager with empty initial topology
        var emptyTopologyManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of());
        emptyTopologyManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
        clusterNode.appliedCommands.clear();

        var artifact = createTestArtifact();
        emptyTopologyManager.onValuePut(blueprintPut(artifact, 3));

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    // === Scale Up/Down Tests ===

    @Test
    void scale_up_adds_new_instances() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();

        // Initial allocation
        sendBlueprintPut(artifact, 1);
        trackSliceState(artifact, self, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Scale up
        sendBlueprintPut(artifact, 3);

        // Should add 2 more instances
        assertThat(clusterNode.appliedCommands).hasSize(2);
    }

    @Test
    void scale_down_removes_instances() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();

        // Initial allocation
        sendBlueprintPut(artifact, 3);
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);
        trackSliceState(artifact, node3, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Scale down
        sendBlueprintPut(artifact, 1);

        // Should issue 2 UNLOAD commands
        assertThat(clusterNode.appliedCommands).hasSize(2);
        assertAllCommandsAreUnloadFor(artifact);
    }

    // === Topology Change Tests ===

    @Test
    void node_added_triggers_reconciliation() {
        becomeLeader();
        addTopology(self, node2);

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 3);
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Add third node
        manager.onTopologyChange(TopologyChangeNotification.nodeAdded(node3, List.of(self, node2, node3)));

        // Should allocate 1 more instance to reach desired 3
        assertThat(clusterNode.appliedCommands).hasSize(1);
    }

    @Test
    void node_removed_cleans_up_state_and_reconciles() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendBlueprintPut(artifact, 3);
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);
        trackSliceState(artifact, node3, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Remove node3 - this removes slice state for node3 and triggers reconciliation
        // With 2 remaining nodes and blueprint wanting 3, we need 1 more on one of the remaining nodes
        manager.onTopologyChange(TopologyChangeNotification.nodeRemoved(node3, List.of(self, node2)));

        // After removing node3's state (1 instance), we have 2 tracked, want 3
        // So should issue 1 LOAD command to replace lost instance
        assertThat(clusterNode.appliedCommands).hasSize(1);
        assertAllCommandsAreLoadFor(artifact);
    }

    // === Slice State Tracking Tests ===

    @Test
    void slice_state_updates_are_tracked() {
        becomeLeader();
        addTopology(self, node2);

        var artifact = createTestArtifact();

        // Initial allocation
        sendBlueprintPut(artifact, 2);

        // Simulate slice becoming active
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Update blueprint with same count - no change needed
        sendBlueprintPut(artifact, 2);

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void slice_state_remove_is_tracked() {
        becomeLeader();
        addTopology(self, node2);

        var artifact = createTestArtifact();

        // Allocate and track
        sendBlueprintPut(artifact, 2);
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Remove slice state (simulating unload completion)
        sendSliceStateRemove(artifact, self);

        // Blueprint still wants 2, but now only 1 tracked
        // Next reconciliation would add 1 more
    }

    // === Helper Methods ===

    private Artifact createTestArtifact() {
        return Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    private void becomeLeader() {
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
    }

    private void loseLeadership() {
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.option(node2), false));
    }

    private void addTopology(NodeId... nodes) {
        var topology = List.of(nodes);
        manager.onTopologyChange(TopologyChangeNotification.nodeAdded(nodes[nodes.length - 1], topology));
    }

    private void sendBlueprintPut(Artifact artifact, int instanceCount) {
        manager.onValuePut(blueprintPut(artifact, instanceCount));
    }

    private ValuePut<AetherKey, AetherValue> blueprintPut(Artifact artifact, int instanceCount) {
        var key = new BlueprintKey(artifact);
        var value = new BlueprintValue(instanceCount);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        return new ValuePut<>(command, Option.none());
    }

    private void sendBlueprintRemove(Artifact artifact) {
        var key = new BlueprintKey(artifact);
        var command = new KVCommand.Remove<AetherKey>(key);
        var notification = new ValueRemove<AetherKey, AetherValue>(command, Option.none());
        manager.onValueRemove(notification);
    }

    private void trackSliceState(Artifact artifact, NodeId nodeId, SliceState state) {
        var key = new SliceNodeKey(artifact, nodeId);
        var value = new SliceNodeValue(state);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        manager.onValuePut(notification);
    }

    private void sendSliceStateRemove(Artifact artifact, NodeId nodeId) {
        var key = new SliceNodeKey(artifact, nodeId);
        var command = new KVCommand.Remove<AetherKey>(key);
        var notification = new ValueRemove<AetherKey, AetherValue>(command, Option.none());
        manager.onValueRemove(notification);
    }

    private void assertAllCommandsAreLoadFor(Artifact artifact) {
        for (var cmd : clusterNode.appliedCommands) {
            assertThat(cmd).isInstanceOf(KVCommand.Put.class);
            var putCmd = (KVCommand.Put<AetherKey, AetherValue>) cmd;
            assertThat(putCmd.key()).isInstanceOf(SliceNodeKey.class);
            var sliceKey = (SliceNodeKey) putCmd.key();
            assertThat(sliceKey.artifact()).isEqualTo(artifact);
            assertThat(putCmd.value()).isEqualTo(new SliceNodeValue(SliceState.LOAD));
        }
    }

    private void assertAllCommandsAreUnloadFor(Artifact artifact) {
        for (var cmd : clusterNode.appliedCommands) {
            assertThat(cmd).isInstanceOf(KVCommand.Put.class);
            var putCmd = (KVCommand.Put<AetherKey, AetherValue>) cmd;
            assertThat(putCmd.key()).isInstanceOf(SliceNodeKey.class);
            var sliceKey = (SliceNodeKey) putCmd.key();
            assertThat(sliceKey.artifact()).isEqualTo(artifact);
            assertThat(putCmd.value()).isEqualTo(new SliceNodeValue(SliceState.UNLOAD));
        }
    }

    // === Test Doubles ===

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

    static class TestKVStore extends KVStore<AetherKey, AetherValue> {
        public TestKVStore() {
            super(null, null, null);
        }

        @Override
        public java.util.Map<AetherKey, AetherValue> snapshot() {
            return new java.util.HashMap<>();
        }
    }

}
