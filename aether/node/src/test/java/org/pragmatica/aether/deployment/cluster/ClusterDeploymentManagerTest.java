package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;

import static org.pragmatica.lang.utils.Causes.cause;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                                                                      clusterNode.topologyManager(), Option.empty(), AutoHealConfig.DEFAULT, ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT, 0);
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

        // Should issue LOAD commands via consensus
        assertThat(filterNodeArtifactPuts()).hasSize(3);
    }

    @Test
    void manager_returns_to_dormant_when_no_longer_leader() {
        becomeLeader();
        addTopology(self, node2, node3);

        loseLeadership();

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 3);

        // Should be ignored in dormant state
        assertThat(filterNodeArtifactPuts()).isEmpty();
    }

    // === Slice Target Handling Tests ===

    @Test
    void sliceTarget_put_triggers_allocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 3);

        assertThat(filterNodeArtifactPuts()).hasSize(3);
        assertAllDhtPutsAreLoadFor(artifact);
    }

    @Test
    void sliceTarget_with_zero_instances_triggers_no_allocation() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 0);

        assertThat(filterNodeArtifactPuts()).isEmpty();
    }

    // === Allocation Strategy Tests ===

    @Test
    void allocation_uses_round_robin_across_nodes() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        sendSliceTargetPut(artifact, 3);

        var allocatedNodes = filterNodeArtifactPuts().stream()
                                                 .map(put -> put.key().nodeId())
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
        assertThat(filterNodeArtifactPuts()).hasSize(2);
    }

    @Test
    void no_allocation_when_no_nodes_available() {
        // Create manager with empty initial topology
        var emptyTopologyManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of(),
            clusterNode.topologyManager(), Option.empty(), AutoHealConfig.DEFAULT, ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT, 0);
        emptyTopologyManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
        clusterNode.appliedCommands.clear();

        var artifact = createTestArtifact();
        emptyTopologyManager.onSliceTargetPut(sliceTargetPut(artifact, 3));

        assertThat(filterNodeArtifactPuts()).isEmpty();
    }

    // === Scale Up/Down Tests ===

    @Test
    void scale_up_adds_new_instances() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();

        // Initial allocation of 1 instance
        sendSliceTargetPut(artifact, 1);
        assertThat(filterNodeArtifactPuts()).hasSize(1);

        // Get the node that was allocated and mark it as ACTIVE
        var allocatedNode = filterNodeArtifactPuts().getFirst().key().nodeId();
        trackSliceState(artifact, allocatedNode, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Scale up to 3 instances - should add 2 more (to the 2 remaining nodes)
        sendSliceTargetPut(artifact, 3);

        assertThat(filterNodeArtifactPuts()).hasSize(2);
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

        // Should issue 2 UNLOAD commands via consensus
        assertThat(filterNodeArtifactPuts()).hasSize(2);
        assertAllDhtPutsAreUnloadFor(artifact);
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

        // Add third node with ON_DUTY lifecycle
        kvStore.put(AetherKey.NodeLifecycleKey.nodeLifecycleKey(node3),
                    AetherValue.NodeLifecycleValue.nodeLifecycleValue(AetherValue.NodeLifecycleState.ON_DUTY));
        manager.onTopologyChange(TopologyChangeNotification.nodeAdded(node3, List.of(self, node2, node3)));

        // Should allocate 1 more instance to reach desired 3
        assertThat(filterNodeArtifactPuts()).hasSize(1);
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
        manager.onTopologyChange(TopologyChangeNotification.nodeRemoved(node3, List.of(self, node2)));

        // NodeArtifactKey removes for node3
        var naRemoves = filterNodeArtifactRemoves();
        assertThat(naRemoves).hasSize(1);
        assertThat(naRemoves.getFirst().key().nodeId()).isEqualTo(node3);
        assertThat(naRemoves.getFirst().key().artifact()).isEqualTo(artifact);

        // Consensus commands should include Remove for lifecycle key
        var removeCommands = clusterNode.appliedCommands.stream()
                                                         .filter(cmd -> cmd instanceof KVCommand.Remove<?>)
                                                         .map(cmd -> ((KVCommand.Remove<?>) cmd).key())
                                                         .toList();
        assertThat(removeCommands).anySatisfy(key ->
            assertThat(key).isInstanceOf(AetherKey.NodeLifecycleKey.class));
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

        assertThat(filterNodeArtifactPuts()).isEmpty();
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
        var testComputeProvider = new TestComputeProvider(provisionCount);
        var prePopulatedKvStore = new TestKVStore();

        // Pre-populate KVStore with a blueprint to simulate leader failover (not initial startup)
        var artifact = createTestArtifact();
        var targetKey = SliceTargetKey.sliceTargetKey(artifact.base());
        var targetValue = SliceTargetValue.sliceTargetValue(artifact.version(), 2);
        prePopulatedKvStore.put(targetKey, targetValue);

        // Create manager with ComputeProvider and TopologyManager that expects 3 nodes
        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, prePopulatedKvStore, router, List.of(self, node2),
            testTopologyManager, Option.option(testComputeProvider), AutoHealConfig.DEFAULT, ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT, 0);

        clusterNode.appliedCommands.clear();

        // Become leader with only 2 of 3 expected nodes — failover triggers immediate auto-heal
        healingManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // ComputeProvider.provision() should have been called once (deficit = 1)
        assertThat(provisionCount.get()).isEqualTo(1);
    }

    @Test
    void initial_startup_defers_auto_heal_during_cooldown() {
        var provisionCount = new AtomicInteger(0);
        var testTopologyManager = new TestTopologyManager(3);
        var testComputeProvider = new TestComputeProvider(provisionCount);

        // Create manager with ComputeProvider, no pre-populated blueprints (initial startup)
        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of(self, node2),
            testTopologyManager, Option.option(testComputeProvider), AutoHealConfig.DEFAULT, ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT, 0);

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
        var testComputeProvider = new TestComputeProvider(provisionCount);

        // Create manager with 3 nodes already present (matches target)
        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of(self, node2, node3),
            testTopologyManager, Option.option(testComputeProvider), AutoHealConfig.DEFAULT, ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT, 0);

        clusterNode.appliedCommands.clear();

        // Become leader with full topology — no auto-heal needed
        healingManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        assertThat(provisionCount.get()).isZero();
    }

    @Test
    void topology_change_triggers_auto_heal_from_outer_cdm() {
        var provisionCount = new AtomicInteger(0);
        var testTopologyManager = new TestTopologyManager(3);
        var testComputeProvider = new TestComputeProvider(provisionCount);
        var prePopulatedKvStore = new TestKVStore();

        // Pre-populate to simulate failover (immediate auto-heal, no cooldown)
        var artifact = createTestArtifact();
        var targetKey = SliceTargetKey.sliceTargetKey(artifact.base());
        var targetValue = SliceTargetValue.sliceTargetValue(artifact.version(), 2);
        prePopulatedKvStore.put(targetKey, targetValue);

        var healingManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, prePopulatedKvStore, router, List.of(self, node2, node3),
            testTopologyManager, Option.option(testComputeProvider), AutoHealConfig.DEFAULT, ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT, 0);

        // Become leader with full topology (no deficit)
        healingManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
        assertThat(provisionCount.get()).isZero();

        clusterNode.appliedCommands.clear();

        // Node removed — topology change triggers auto-heal from outer CDM
        healingManager.onTopologyChange(TopologyChangeNotification.nodeRemoved(node3, List.of(self, node2)));

        // Should provision 1 node (deficit = 1)
        assertThat(provisionCount.get()).isEqualTo(1);
    }

    // === Blueprint Atomicity Tests ===

    @Test
    void allOrNothing_deterministicFailure_issuesUnloadForAllSlices() {
        var mgr = createAllOrNothingManager();
        becomeLeader(mgr);
        addTopology(mgr, self, node2, node3);

        var blueprint = createTestBlueprint("slice-a", "slice-b");
        sendAppBlueprintPut(mgr, blueprint);

        // Mark both slices as LOADED then ACTIVE on one node so they're tracked
        var sliceA = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        var sliceB = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();
        trackSliceState(mgr, sliceA, self, SliceState.LOADED);
        trackSliceState(mgr, sliceB, self, SliceState.LOADED);

        clusterNode.appliedCommands.clear();

        // Simulate deterministic failure on slice-a (Fatal = non-retriable)
        trackSliceStateWithFailure(mgr, sliceA, node2,
            new SliceLoadingFailure.Fatal.ClassLoadFailed("com.example.Missing",
                cause("ClassNotFoundException")));

        // ALL_OR_NOTHING should unload ALL slices in the blueprint via consensus
        var unloadArtifacts = filterNodeArtifactPuts().stream()
            .filter(put -> put.value().state() == SliceState.UNLOAD)
            .map(put -> put.key().artifact())
            .toList();

        assertThat(unloadArtifacts).anySatisfy(a -> assertThat(a).isEqualTo(sliceA));
        assertThat(unloadArtifacts).anySatisfy(a -> assertThat(a).isEqualTo(sliceB));

        // Verify consensus Remove commands include SliceTargetKeys and AppBlueprintKey
        var removeKeys = clusterNode.appliedCommands.stream()
            .filter(cmd -> cmd instanceof KVCommand.Remove<?>)
            .map(cmd -> ((KVCommand.Remove<?>) cmd).key())
            .toList();

        assertThat(removeKeys).anySatisfy(key -> assertThat(key).isInstanceOf(SliceTargetKey.class));
        assertThat(removeKeys).anySatisfy(key -> assertThat(key).isInstanceOf(AppBlueprintKey.class));
    }

    @Test
    void bestEffort_deterministicFailure_noRollbackOfSiblingSlices() {
        becomeLeader();
        addTopology(self, node2, node3);

        var blueprint = createTestBlueprint("slice-a", "slice-b");
        sendAppBlueprintPut(manager, blueprint);

        var sliceA = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        var sliceB = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();
        trackSliceState(sliceA, self, SliceState.LOADED);
        trackSliceState(sliceB, self, SliceState.LOADED);

        clusterNode.appliedCommands.clear();

        // Simulate deterministic failure on slice-a in BEST_EFFORT mode
        trackSliceStateWithFailure(manager, sliceA, node2,
            new SliceLoadingFailure.Fatal.ClassLoadFailed("com.example.Missing",
                cause("ClassNotFoundException")));

        // In BEST_EFFORT, only the failed slice gets UNLOAD — no sibling rollback
        var unloadArtifacts = filterNodeArtifactPuts().stream()
            .filter(put -> put.value().state() == SliceState.UNLOAD)
            .map(put -> put.key().artifact())
            .toList();

        // Only sliceA should be unloaded, not sliceB
        assertThat(unloadArtifacts).allSatisfy(a -> assertThat(a).isEqualTo(sliceA));

        // No Remove for AppBlueprintKey (blueprint stays)
        var blueprintRemoves = clusterNode.appliedCommands.stream()
            .filter(cmd -> cmd instanceof KVCommand.Remove<?>)
            .map(cmd -> ((KVCommand.Remove<?>) cmd).key())
            .filter(key -> key instanceof AppBlueprintKey)
            .toList();
        assertThat(blueprintRemoves).isEmpty();
    }

    @Test
    void allOrNothing_transientFailure_noRollback() {
        var mgr = createAllOrNothingManager();
        becomeLeader(mgr);
        addTopology(mgr, self, node2, node3);

        var blueprint = createTestBlueprint("slice-a", "slice-b");
        sendAppBlueprintPut(mgr, blueprint);

        var sliceA = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        var sliceB = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();
        trackSliceState(mgr, sliceA, self, SliceState.LOADED);
        trackSliceState(mgr, sliceB, self, SliceState.LOADED);

        clusterNode.appliedCommands.clear();

        // Simulate transient failure (Intermittent = retriable)
        trackSliceStateWithFailure(mgr, sliceA, node2,
            new SliceLoadingFailure.Intermittent.ResourceUnavailable("database",
                cause("Connection timed out")));

        // Transient failure should NOT trigger blueprint rollback
        var blueprintRemoves = clusterNode.appliedCommands.stream()
            .filter(cmd -> cmd instanceof KVCommand.Remove<?>)
            .map(cmd -> ((KVCommand.Remove<?>) cmd).key())
            .filter(key -> key instanceof AppBlueprintKey)
            .toList();
        assertThat(blueprintRemoves).isEmpty();

        // sliceB should NOT receive any UNLOAD
        var sliceBUnloads = filterNodeArtifactPuts().stream()
            .filter(put -> put.value().state() == SliceState.UNLOAD)
            .filter(put -> put.key().artifact().equals(sliceB))
            .toList();
        assertThat(sliceBUnloads).isEmpty();
    }

    @Test
    void allOrNothing_allSlicesActive_clearsTracking() {
        var mgr = createAllOrNothingManager();
        becomeLeader(mgr);
        addTopology(mgr, self, node2, node3);

        var blueprint = createTestBlueprint("slice-a", "slice-b");
        sendAppBlueprintPut(mgr, blueprint);

        var sliceA = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        var sliceB = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();

        // Simulate both slices becoming ACTIVE (LOADED -> ACTIVE transition)
        trackSliceState(mgr, sliceA, self, SliceState.LOADED);
        trackSliceState(mgr, sliceA, self, SliceState.ACTIVE);
        trackSliceState(mgr, sliceB, self, SliceState.LOADED);
        trackSliceState(mgr, sliceB, self, SliceState.ACTIVE);

        clusterNode.appliedCommands.clear();

        // Now simulate a deterministic failure on a different artifact not in the blueprint
        var unrelatedArtifact = Artifact.artifact("org.example:unrelated:1.0.0").unwrap();
        trackSliceStateWithFailure(mgr, unrelatedArtifact, node2,
            new SliceLoadingFailure.Fatal.ClassLoadFailed("com.example.Other",
                cause("ClassNotFoundException")));

        // Since tracking was cleared when all slices became ACTIVE,
        // the unrelated failure should NOT trigger any blueprint rollback for slice-a/slice-b
        var sliceAUnloads = filterNodeArtifactPuts().stream()
            .filter(put -> put.value().state() == SliceState.UNLOAD)
            .filter(put -> put.key().artifact().equals(sliceA))
            .toList();
        assertThat(sliceAUnloads).isEmpty();

        var sliceBUnloads = filterNodeArtifactPuts().stream()
            .filter(put -> put.value().state() == SliceState.UNLOAD)
            .filter(put -> put.key().artifact().equals(sliceB))
            .toList();
        assertThat(sliceBUnloads).isEmpty();
    }

    // === Multi-Blueprint Lifecycle Tests ===

    @Test
    void multiBlueprint_deployTwoWithDifferentArtifacts_bothSucceed() {
        becomeLeader();
        addTopology(self, node2, node3);

        var blueprintA = createNamedBlueprint("app-a", "service-a");
        var blueprintB = createNamedBlueprint("app-b", "service-b");

        sendAppBlueprintPut(manager, blueprintA);
        sendAppBlueprintPut(manager, blueprintB);

        var serviceA = Artifact.artifact("org.example:service-a:1.0.0").unwrap();
        var serviceB = Artifact.artifact("org.example:service-b:1.0.0").unwrap();

        var loadedArtifacts = filterLoadArtifacts();

        assertThat(loadedArtifacts).contains(serviceA);
        assertThat(loadedArtifacts).contains(serviceB);
    }

    @Test
    void multiBlueprint_deployWithOverlappingArtifact_secondRejected() {
        becomeLeader();
        addTopology(self, node2, node3);

        var blueprintA = createNamedBlueprint("app-a", "shared-lib");
        var blueprintB = createNamedBlueprint("app-b", "shared-lib");

        sendAppBlueprintPut(manager, blueprintA);

        var loadCountAfterA = filterLoadArtifacts().size();

        sendAppBlueprintPut(manager, blueprintB);

        // No additional LOADs after Blueprint B — it was rejected
        assertThat(filterLoadArtifacts()).hasSize(loadCountAfterA);

        var sharedLib = Artifact.artifact("org.example:shared-lib:1.0.0").unwrap();
        var loadedArtifacts = filterLoadArtifacts();

        assertThat(loadedArtifacts).allSatisfy(a -> assertThat(a).isEqualTo(sharedLib));
    }

    @Test
    void multiBlueprint_deleteOne_otherUnaffected() {
        becomeLeader();
        addTopology(self, node2, node3);

        var blueprintA = createNamedBlueprint("app-a", "service-a");
        var blueprintB = createNamedBlueprint("app-b", "service-b");

        sendAppBlueprintPut(manager, blueprintA);
        sendAppBlueprintPut(manager, blueprintB);

        // Clear tracking to isolate deletion effects
        clusterNode.appliedCommands.clear();

        sendAppBlueprintRemove(manager, blueprintA);

        var serviceA = Artifact.artifact("org.example:service-a:1.0.0").unwrap();
        var serviceB = Artifact.artifact("org.example:service-b:1.0.0").unwrap();

        // service-a should get UNLOAD commands
        var unloadArtifacts = filterUnloadArtifacts();
        assertThat(unloadArtifacts).contains(serviceA);

        // service-b should NOT get any UNLOAD commands
        assertThat(unloadArtifacts).doesNotContain(serviceB);
    }

    @Test
    void multiBlueprint_deleteDuringRollingUpdate_rejected() {
        becomeLeader();
        addTopology(self, node2, node3);

        var blueprintA = createNamedBlueprint("app-a", "service-a");
        sendAppBlueprintPut(manager, blueprintA);

        // Simulate active rolling update by adding version routing
        var serviceA = Artifact.artifact("org.example:service-a:1.0.0").unwrap();
        sendVersionRoutingPut(serviceA);

        // Clear tracking to isolate deletion effects
        clusterNode.appliedCommands.clear();

        sendAppBlueprintRemove(manager, blueprintA);

        // Blueprint should NOT be deleted — no UNLOAD commands issued
        assertThat(filterNodeArtifactPuts()).isEmpty();
    }

    @Test
    void multiBlueprint_republishSameBlueprint_allowed() {
        becomeLeader();
        addTopology(self, node2, node3);

        // Deploy blueprint with same BlueprintId — v1 artifact
        var bpId = BlueprintId.blueprintId("org.example:app-a:1.0.0").unwrap();
        var sliceV1 = ResolvedSlice.resolvedSlice(
            Artifact.artifact("org.example:service-a:1.0.0").unwrap(), 3, false).unwrap();
        var blueprintV1 = ExpandedBlueprint.expandedBlueprint(bpId, List.of(sliceV1));
        sendAppBlueprintPut(manager, blueprintV1);

        // Clear tracking to isolate re-deploy effects
        clusterNode.appliedCommands.clear();

        // Re-deploy same BlueprintId with updated artifact version — this is an update, not a conflict
        var sliceV2 = ResolvedSlice.resolvedSlice(
            Artifact.artifact("org.example:service-a:2.0.0").unwrap(), 3, false).unwrap();
        var blueprintV2 = ExpandedBlueprint.expandedBlueprint(bpId, List.of(sliceV2));
        sendAppBlueprintPut(manager, blueprintV2);

        var serviceAv2 = Artifact.artifact("org.example:service-a:2.0.0").unwrap();
        var loadedArtifacts = filterLoadArtifacts();

        assertThat(loadedArtifacts).contains(serviceAv2);
    }

    @Test
    void multiBlueprint_restoreFromKVStore_ownerPopulated() {
        // Pre-populate KVStore with Blueprint A's data
        var blueprintA = createNamedBlueprint("app-a", "service-a");
        var bpKey = new AppBlueprintKey(blueprintA.id());
        var bpValue = new AppBlueprintValue(blueprintA);
        kvStore.put(bpKey, bpValue);

        // Also store SliceTargetKey so the blueprint's artifact is tracked
        var serviceA = Artifact.artifact("org.example:service-a:1.0.0").unwrap();
        var targetKey = SliceTargetKey.sliceTargetKey(serviceA.base());
        var targetValue = SliceTargetValue.sliceTargetValue(serviceA.version(), 3, 0, Option.some(blueprintA.id()));
        kvStore.put(targetKey, targetValue);

        // Create a new CDM that will rebuild from KVStore on leader activation
        var restoredManager = ClusterDeploymentManager.clusterDeploymentManager(
            self, clusterNode, kvStore, router, List.of(self, node2, node3),
            clusterNode.topologyManager(), Option.empty(), AutoHealConfig.DEFAULT,
            ClusterDeploymentManager.DeploymentAtomicity.BEST_EFFORT, 0);

        // Become leader triggers rebuildStateFromKVStore
        restoredManager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));

        // Clear tracking from restoration
        clusterNode.appliedCommands.clear();

        // Add topology so allocations can happen
        for (var nodeId : List.of(self, node2, node3)) {
            kvStore.put(AetherKey.NodeLifecycleKey.nodeLifecycleKey(nodeId),
                        AetherValue.NodeLifecycleValue.nodeLifecycleValue(AetherValue.NodeLifecycleState.ON_DUTY));
        }
        restoredManager.onTopologyChange(TopologyChangeNotification.nodeAdded(node3, List.of(self, node2, node3)));

        clusterNode.appliedCommands.clear();

        // Deploy Blueprint B with overlapping artifact "service-a"
        var blueprintB = createNamedBlueprint("app-b", "service-a");
        sendAppBlueprintPut(restoredManager, blueprintB);

        // Blueprint B should be rejected — "service-a" already owned by Blueprint A from restore
        assertThat(filterNodeArtifactPuts()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void handleAppBlueprintChange_ownerPopulatedInSliceTargetValue() {
        becomeLeader();
        addTopology(self, node2, node3);

        var blueprintA = createNamedBlueprint("app-owner", "service-owned");
        sendAppBlueprintPut(manager, blueprintA);

        // Find the SliceTargetKey Put command in applied commands
        var targetValues = clusterNode.appliedCommands.stream()
            .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof SliceTargetKey)
            .map(cmd -> ((KVCommand.Put<?, ?>) cmd).value())
            .map(SliceTargetValue.class::cast)
            .toList();

        assertThat(targetValues).isNotEmpty();

        var targetValue = targetValues.getFirst();
        assertThat(targetValue.owningBlueprint().isPresent()).isTrue();
        assertThat(targetValue.owningBlueprint().unwrap()).isEqualTo(blueprintA.id());
    }

    @Test
    void handleSliceTargetChange_preservesOwner() {
        becomeLeader();
        addTopology(self, node2, node3);

        var artifact = createTestArtifact();
        var bpId = BlueprintId.blueprintId("org.example:owner-bp:1.0.0").unwrap();

        // Send a slice target with explicit owner
        var key = SliceTargetKey.sliceTargetKey(artifact.base());
        var value = SliceTargetValue.sliceTargetValue(artifact.version(), 3, 0, Option.some(bpId));
        var command = new KVCommand.Put<>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        manager.onSliceTargetPut(notification);

        // Verify LOAD commands were issued (owner didn't break allocation)
        assertThat(filterNodeArtifactPuts()).hasSize(3);
        assertAllDhtPutsAreLoadFor(artifact);
    }

    // === Helper Methods ===

    private Artifact createTestArtifact() {
        return Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    private void becomeLeader() {
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
    }

    private void becomeLeader(ClusterDeploymentManager mgr) {
        mgr.onLeaderChange(LeaderNotification.leaderChange(Option.option(self), true));
    }

    private void loseLeadership() {
        manager.onLeaderChange(LeaderNotification.leaderChange(Option.option(node2), false));
    }

    private void addTopology(NodeId... nodes) {
        addTopology(manager, nodes);
    }

    private void addTopology(ClusterDeploymentManager mgr, NodeId... nodes) {
        var topology = List.of(nodes);
        // Register ON_DUTY lifecycle for each node so CDM considers them allocatable
        for (var nodeId : nodes) {
            kvStore.put(AetherKey.NodeLifecycleKey.nodeLifecycleKey(nodeId),
                        AetherValue.NodeLifecycleValue.nodeLifecycleValue(AetherValue.NodeLifecycleState.ON_DUTY));
        }
        mgr.onTopologyChange(TopologyChangeNotification.nodeAdded(nodes[nodes.length - 1], topology));
    }

    private void sendSliceTargetPut(Artifact artifact, int instanceCount) {
        manager.onSliceTargetPut(sliceTargetPut(artifact, instanceCount));
    }

    private ValuePut<SliceTargetKey, SliceTargetValue> sliceTargetPut(Artifact artifact, int instanceCount) {
        var key = SliceTargetKey.sliceTargetKey(artifact.base());
        var value = SliceTargetValue.sliceTargetValue(artifact.version(), instanceCount);
        var command = new KVCommand.Put<>(key, value);
        return new ValuePut<>(command, Option.none());
    }

    private void trackSliceState(Artifact artifact, NodeId nodeId, SliceState state) {
        trackSliceState(manager, artifact, nodeId, state);
    }

    private void trackSliceState(ClusterDeploymentManager mgr, Artifact artifact, NodeId nodeId, SliceState state) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var value = NodeArtifactValue.nodeArtifactValue(state);
        var command = new KVCommand.Put<>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        mgr.onNodeArtifactPut(notification);
    }

    private void trackSliceStateWithFailure(ClusterDeploymentManager mgr, Artifact artifact, NodeId nodeId, Cause failureReason) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var value = NodeArtifactValue.failedNodeArtifactValue(failureReason);
        var command = new KVCommand.Put<>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        mgr.onNodeArtifactPut(notification);
    }

    private void sendSliceStateRemove(Artifact artifact, NodeId nodeId) {
        var key = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var command = new KVCommand.Remove<NodeArtifactKey>(key);
        var notification = new ValueRemove<NodeArtifactKey, NodeArtifactValue>(command, Option.none());
        manager.onNodeArtifactRemove(notification);
    }

    private ClusterDeploymentManager createAllOrNothingManager() {
        return ClusterDeploymentManager.clusterDeploymentManager(self, clusterNode, kvStore, router, List.of(self, node2, node3),
            clusterNode.topologyManager(), Option.empty(), AutoHealConfig.DEFAULT, ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING, 0);
    }

    private void sendAppBlueprintPut(ClusterDeploymentManager mgr, ExpandedBlueprint blueprint) {
        var key = new AppBlueprintKey(blueprint.id());
        var value = new AppBlueprintValue(blueprint);
        var command = new KVCommand.Put<>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        mgr.onAppBlueprintPut(notification);
    }

    private ExpandedBlueprint createTestBlueprint(String... sliceNames) {
        var bpId = BlueprintId.blueprintId("org.example:test-app:1.0.0").unwrap();
        var slices = Arrays.stream(sliceNames)
            .map(name -> ResolvedSlice.resolvedSlice(
                Artifact.artifact("org.example:" + name + ":1.0.0").unwrap(), 3, false).unwrap())
            .toList();
        return ExpandedBlueprint.expandedBlueprint(bpId, slices);
    }

    private ExpandedBlueprint createNamedBlueprint(String blueprintName, String... sliceNames) {
        var bpId = BlueprintId.blueprintId("org.example:" + blueprintName + ":1.0.0").unwrap();
        var slices = Arrays.stream(sliceNames)
            .map(name -> ResolvedSlice.resolvedSlice(
                Artifact.artifact("org.example:" + name + ":1.0.0").unwrap(), 3, false).unwrap())
            .toList();
        return ExpandedBlueprint.expandedBlueprint(bpId, slices);
    }

    private ExpandedBlueprint createVersionedBlueprint(String blueprintName, String version, String... sliceNames) {
        var bpId = BlueprintId.blueprintId("org.example:" + blueprintName + ":" + version).unwrap();
        var slices = Arrays.stream(sliceNames)
            .map(name -> ResolvedSlice.resolvedSlice(
                Artifact.artifact("org.example:" + name + ":" + version).unwrap(), 3, false).unwrap())
            .toList();
        return ExpandedBlueprint.expandedBlueprint(bpId, slices);
    }

    private void sendAppBlueprintRemove(ClusterDeploymentManager mgr, ExpandedBlueprint blueprint) {
        var key = new AppBlueprintKey(blueprint.id());
        var command = new KVCommand.Remove<AppBlueprintKey>(key);
        var notification = new ValueRemove<AppBlueprintKey, AppBlueprintValue>(command, Option.none());
        mgr.onAppBlueprintRemove(notification);
    }

    private void sendVersionRoutingPut(Artifact artifact) {
        var routingKey = VersionRoutingKey.versionRoutingKey(artifact.base());
        var routingValue = VersionRoutingValue.versionRoutingValue(artifact.version(), artifact.version());
        var command = new KVCommand.Put<>(routingKey, routingValue);
        var notification = new ValuePut<>(command, Option.none());
        manager.onVersionRoutingPut(notification);
    }

    private List<Artifact> filterLoadArtifacts() {
        return filterNodeArtifactPuts().stream()
            .filter(put -> put.value().state() == SliceState.LOAD)
            .map(put -> put.key().artifact())
            .toList();
    }

    private List<Artifact> filterUnloadArtifacts() {
        return filterNodeArtifactPuts().stream()
            .filter(put -> put.value().state() == SliceState.UNLOAD)
            .map(put -> put.key().artifact())
            .toList();
    }

    private void assertAllDhtPutsAreLoadFor(Artifact artifact) {
        var puts = filterNodeArtifactPuts();
        assertThat(puts).isNotEmpty();
        for (var put : puts) {
            assertThat(put.key().artifact()).isEqualTo(artifact);
            assertThat(put.value().state()).isEqualTo(SliceState.LOAD);
        }
    }

    private void assertAllDhtPutsAreUnloadFor(Artifact artifact) {
        var puts = filterNodeArtifactPuts();
        assertThat(puts).isNotEmpty();
        for (var put : puts) {
            assertThat(put.key().artifact()).isEqualTo(artifact);
            assertThat(put.value().state()).isEqualTo(SliceState.UNLOAD);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<KVCommand.Put<NodeArtifactKey, NodeArtifactValue>> filterNodeArtifactPuts() {
        return clusterNode.appliedCommands.stream()
            .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof NodeArtifactKey)
            .map(cmd -> (KVCommand.Put) cmd)
            .map(cmd -> (KVCommand.Put<NodeArtifactKey, NodeArtifactValue>) cmd)
            .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<KVCommand.Remove<NodeArtifactKey>> filterNodeArtifactRemoves() {
        return clusterNode.appliedCommands.stream()
            .filter(cmd -> cmd instanceof KVCommand.Remove<?> rem && rem.key() instanceof NodeArtifactKey)
            .map(cmd -> (KVCommand.Remove) cmd)
            .map(cmd -> (KVCommand.Remove<NodeArtifactKey>) cmd)
            .toList();
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
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(entries.get(key));
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

    static class TestComputeProvider implements ComputeProvider {
        private final AtomicInteger provisionCount;

        TestComputeProvider(AtomicInteger provisionCount) {
            this.provisionCount = provisionCount;
        }

        @Override
        public Promise<InstanceInfo> provision(InstanceType instanceType) {
            var id = new InstanceId("test-" + provisionCount.incrementAndGet());
            var info = new InstanceInfo(
                id,
                InstanceStatus.RUNNING,
                List.of("localhost:9999"),
                instanceType,
                Map.of());
            return Promise.success(info);
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.success(new InstanceInfo(
                instanceId,
                InstanceStatus.RUNNING,
                List.of("localhost:9999"),
                InstanceType.ON_DEMAND,
                Map.of()));
        }
    }
}
