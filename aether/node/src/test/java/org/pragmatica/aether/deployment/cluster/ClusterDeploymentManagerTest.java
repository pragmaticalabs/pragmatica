package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.provider.AutoHealConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
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
import java.util.concurrent.atomic.AtomicInteger;

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
        manager = ClusterDeploymentManager.clusterDeploymentManager(self, clusterNode, kvStore, router, List.of(self, node2, node3),
                                                                      clusterNode.topologyManager(), Option.empty(), AutoHealConfig.DEFAULT);
    }

    // === Leader State Tests ===

    @Test
    void manager_starts_in_dormant_state() {
        var artifact = createTestArtifact();

        // Send slice target before becoming leader - should be ignored
        sendSliceTargetPut(artifact, 3);

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void manager_activates_on_becoming_leader() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 3);

        // Should issue LOAD commands
        assertThat(clusterNode.appliedCommands).hasSize(3);
    }

    @Test
    void manager_returns_to_dormant_when_no_longer_leader() {
        becomeLeader();
        addTopology(self, node2, node3);

        loseLeadership();

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 3);

        // Should be ignored in dormant state
        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    // === Slice Target Handling Tests ===

    @Test
    void sliceTarget_put_triggers_allocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 3);

        assertThat(clusterNode.appliedCommands).hasSize(3);
        assertAllCommandsAreLoadFor(artifact);
    }

    @Test
    void sliceTarget_with_zero_instances_triggers_no_allocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 0);

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void sliceTarget_removal_triggers_deallocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();

        // First allocate 2 instances
        sendSliceTargetPut(artifact, 2);
        assertThat(clusterNode.appliedCommands).hasSize(2);

        // Mark the actually allocated nodes as ACTIVE
        for (var command : clusterNode.appliedCommands) {
            var key = extractSliceNodeKey(command);
            trackSliceState(artifact, key.nodeId(), SliceState.ACTIVE);
        }

        clusterNode.appliedCommands.clear();

        // Then remove slice target - should unload both allocated instances
        sendSliceTargetRemove(artifact);

        assertThat(clusterNode.appliedCommands).hasSize(2);
        assertAllCommandsAreUnloadFor(artifact);
    }

    // === Allocation Strategy Tests ===

    @Test
    void allocation_uses_round_robin_across_nodes() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 3);

        var allocatedNodes = clusterNode.appliedCommands.stream()
                                                        .map(cmd -> ((KVCommand.Put<AetherKey, AetherValue>) cmd).key())
                                                        .map(key -> ((SliceNodeKey) key).nodeId())
                                                        .toList();

        // Should allocate to all 3 nodes
        assertThat(allocatedNodes).containsExactlyInAnyOrder(self, node2, node3);
    }

    @Test
    void allocation_limited_to_available_nodes() {
        becomeLeader();
        addTopology(self, node2);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 4);

        // With 2 nodes and 4 requested instances, only 2 can be allocated (max 1 per node per artifact)
        assertThat(clusterNode.appliedCommands).hasSize(2);
    }

    @Test
    void no_allocation_when_no_nodes_available() {
        // Create manager with empty initial topology
        var emptyTopologyManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of(),
            clusterNode.topologyManager(), Option.empty(), AutoHealConfig.DEFAULT);
        emptyTopologyManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
        clusterNode.appliedCommands.clear();

        var artifact = createTestArtifact();
        emptyTopologyManager.onValuePut(sliceTargetPut(artifact, 3));

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    // === Scale Up/Down Tests ===

    @Test
    void scale_up_adds_new_instances() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();

        // Initial allocation of 1 instance
        sendSliceTargetPut(artifact, 1);
        assertThat(clusterNode.appliedCommands).hasSize(1);

        // Get the node that was allocated and mark it as ACTIVE
        var allocatedKey = extractSliceNodeKey(clusterNode.appliedCommands.get(0));
        trackSliceState(artifact, allocatedKey.nodeId(), SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Scale up to 3 instances - should add 2 more (to the 2 remaining nodes)
        sendSliceTargetPut(artifact, 3);

        assertThat(clusterNode.appliedCommands).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private SliceNodeKey extractSliceNodeKey(KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<?, ?> put && put.key() instanceof SliceNodeKey key) {
            return key;
        }
        throw new IllegalArgumentException("Expected Put with SliceNodeKey, got: " + command);
    }

    @Test
    void scale_down_removes_instances() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();

        // Initial allocation
        sendSliceTargetPut(artifact, 3);
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);
        trackSliceState(artifact, node3, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Scale down
        sendSliceTargetPut(artifact, 1);

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
        sendSliceTargetPut(artifact, 3);
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
        sendSliceTargetPut(artifact, 3);
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);
        trackSliceState(artifact, node3, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Remove node3 - this removes slice state for node3 and triggers reconciliation
        // With 2 remaining nodes and slice target wanting 3, we have 2 instances remaining
        // Reconciliation won't add more because we can't exceed node count
        manager.onTopologyChange(TopologyChangeNotification.nodeRemoved(node3, List.of(self, node2)));

        // Expect 1 command: Remove command to clean KVStore for departed node
        // No LOAD command because remaining 2 instances already cover all available nodes
        assertThat(clusterNode.appliedCommands).hasSize(1);

        // Command should be Remove for the departed node's slice
        var removeCmd = clusterNode.appliedCommands.getFirst();
        assertThat(removeCmd).isInstanceOf(KVCommand.Remove.class);
        var removeKey = ((KVCommand.Remove<?>) removeCmd).key();
        assertThat(removeKey).isInstanceOf(SliceNodeKey.class);
        assertThat(((SliceNodeKey) removeKey).nodeId()).isEqualTo(node3);
        assertThat(((SliceNodeKey) removeKey).artifact()).isEqualTo(artifact);
    }

    // === Slice State Tracking Tests ===

    @Test
    void slice_state_updates_are_tracked() {
        becomeLeader();
        addTopology(self, node2);

        var artifact = createTestArtifact();

        // Initial allocation
        sendSliceTargetPut(artifact, 2);

        // Simulate slice becoming active
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Update slice target with same count - no change needed
        sendSliceTargetPut(artifact, 2);

        assertThat(clusterNode.appliedCommands).isEmpty();
    }

    @Test
    void slice_state_remove_is_tracked() {
        becomeLeader();
        addTopology(self, node2);

        var artifact = createTestArtifact();

        // Allocate and track
        sendSliceTargetPut(artifact, 2);
        trackSliceState(artifact, self, SliceState.ACTIVE);
        trackSliceState(artifact, node2, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Remove slice state (simulating unload completion)
        sendSliceStateRemove(artifact, self);

        // Slice target still wants 2, but now only 1 tracked
        // Next reconciliation would add 1 more
    }

    // === Auto-Heal Tests ===

    @Test
    void leader_failover_triggers_immediate_auto_heal_when_cluster_below_target() {
        var provisionCount = new AtomicInteger(0);
        var testTopologyManager = new TestTopologyManager(3);
        var testNodeProvider = new TestNodeProvider(provisionCount);
        var prePopulatedKvStore = new TestKVStore();

        // Pre-populate KVStore with a blueprint to simulate leader failover (not initial startup)
        var artifact = createTestArtifact();
        var targetKey = SliceTargetKey.sliceTargetKey(artifact.base());
        var targetValue = SliceTargetValue.sliceTargetValue(artifact.version(), 2);
        prePopulatedKvStore.put(targetKey, targetValue);

        // Create manager with NodeProvider and TopologyManager that expects 3 nodes
        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, prePopulatedKvStore, router, List.of(self, node2),
            testTopologyManager, Option.option(testNodeProvider), AutoHealConfig.DEFAULT);

        clusterNode.appliedCommands.clear();

        // Become leader with only 2 of 3 expected nodes — failover triggers immediate auto-heal
        healingManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // NodeProvider.provision() should have been called once (deficit = 1)
        assertThat(provisionCount.get()).isEqualTo(1);
    }

    @Test
    void initial_startup_defers_auto_heal_during_cooldown() {
        var provisionCount = new AtomicInteger(0);
        var testTopologyManager = new TestTopologyManager(3);
        var testNodeProvider = new TestNodeProvider(provisionCount);

        // Create manager with NodeProvider, no pre-populated blueprints (initial startup)
        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of(self, node2),
            testTopologyManager, Option.option(testNodeProvider), AutoHealConfig.DEFAULT);

        clusterNode.appliedCommands.clear();

        // Become leader with only 2 of 3 expected nodes — initial startup uses cooldown
        healingManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // No immediate provisioning during cooldown
        assertThat(provisionCount.get()).isZero();
    }

    @Test
    void leader_activation_skips_auto_heal_when_cluster_at_target() {
        var provisionCount = new AtomicInteger(0);
        var testTopologyManager = new TestTopologyManager(3);
        var testNodeProvider = new TestNodeProvider(provisionCount);

        // Create manager with 3 nodes already present (matches target)
        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of(self, node2, node3),
            testTopologyManager, Option.option(testNodeProvider), AutoHealConfig.DEFAULT);

        clusterNode.appliedCommands.clear();

        // Become leader with full topology — no auto-heal needed
        healingManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        assertThat(provisionCount.get()).isZero();
    }

    @Test
    void topology_change_triggers_auto_heal_from_outer_cdm() {
        var provisionCount = new AtomicInteger(0);
        var testTopologyManager = new TestTopologyManager(3);
        var testNodeProvider = new TestNodeProvider(provisionCount);
        var prePopulatedKvStore = new TestKVStore();

        // Pre-populate to simulate failover (immediate auto-heal, no cooldown)
        var artifact = createTestArtifact();
        var targetKey = SliceTargetKey.sliceTargetKey(artifact.base());
        var targetValue = SliceTargetValue.sliceTargetValue(artifact.version(), 2);
        prePopulatedKvStore.put(targetKey, targetValue);

        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, prePopulatedKvStore, router, List.of(self, node2, node3),
            testTopologyManager, Option.option(testNodeProvider), AutoHealConfig.DEFAULT);

        // Become leader with full topology (no deficit)
        healingManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
        assertThat(provisionCount.get()).isZero();

        clusterNode.appliedCommands.clear();

        // Node removed — topology change triggers auto-heal from outer CDM
        healingManager.onTopologyChange(TopologyChangeNotification.nodeRemoved(node3, List.of(self, node2)));

        // Should provision 1 node (deficit = 1)
        assertThat(provisionCount.get()).isEqualTo(1);
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

    private void sendSliceTargetPut(Artifact artifact, int instanceCount) {
        manager.onValuePut(sliceTargetPut(artifact, instanceCount));
    }

    private ValuePut<AetherKey, AetherValue> sliceTargetPut(Artifact artifact, int instanceCount) {
        var key = SliceTargetKey.sliceTargetKey(artifact.base());
        var value = SliceTargetValue.sliceTargetValue(artifact.version(), instanceCount);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        return new ValuePut<>(command, Option.none());
    }

    private void sendSliceTargetRemove(Artifact artifact) {
        var key = SliceTargetKey.sliceTargetKey(artifact.base());
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
        public TopologyManager topologyManager() {
            return null;
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
        private final java.util.Map<AetherKey, AetherValue> entries = new java.util.concurrent.ConcurrentHashMap<>();

        public TestKVStore() {
            super(null, null, null);
        }

        void put(AetherKey key, AetherValue value) {
            entries.put(key, value);
        }

        @Override
        public java.util.Map<AetherKey, AetherValue> snapshot() {
            return new java.util.HashMap<>(entries);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, java.util.function.BiConsumer<KK, VV> consumer) {
            entries.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }

    static class TestTopologyManager implements TopologyManager {
        private final int clusterSize;

        TestTopologyManager(int clusterSize) {
            this.clusterSize = clusterSize;
        }

        @Override
        public org.pragmatica.consensus.net.NodeInfo self() {
            return null;
        }

        @Override
        public Option<org.pragmatica.consensus.net.NodeInfo> get(NodeId id) {
            return Option.empty();
        }

        @Override
        public int clusterSize() {
            return clusterSize;
        }

        @Override
        public Option<NodeId> reverseLookup(java.net.SocketAddress socketAddress) {
            return Option.empty();
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
        public org.pragmatica.lang.io.TimeSpan pingInterval() {
            return org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds();
        }

        @Override
        public org.pragmatica.lang.io.TimeSpan helloTimeout() {
            return org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds();
        }

        @Override
        public Option<org.pragmatica.consensus.topology.NodeState> getState(NodeId id) {
            return Option.empty();
        }

        @Override
        public List<NodeId> topology() {
            return List.of();
        }
    }

    static class TestNodeProvider implements org.pragmatica.aether.provider.NodeProvider {
        private final AtomicInteger provisionCount;

        TestNodeProvider(AtomicInteger provisionCount) {
            this.provisionCount = provisionCount;
        }

        @Override
        public Promise<Unit> provision(org.pragmatica.aether.provider.InstanceType instanceType) {
            provisionCount.incrementAndGet();
            return Promise.success(Unit.unit());
        }
    }

}
