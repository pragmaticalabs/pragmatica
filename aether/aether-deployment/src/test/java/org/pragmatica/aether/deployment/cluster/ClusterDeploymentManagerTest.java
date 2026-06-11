// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.topology.NodeState;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class ClusterDeploymentManagerTest {
    private static final NodeId NODE_1 = new NodeId("node-1");
    private static final NodeId NODE_2 = new NodeId("node-2");
    private static final NodeId NODE_3 = new NodeId("node-3");
    private static final NodeId DRAINING_NODE = new NodeId("node-drain");
    private static final SchemaOrchestratorService NO_OP_SCHEMA_ORCHESTRATOR = noOpSchemaOrchestrator();

    private static SchemaOrchestratorService noOpSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override
            public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    @Nested
    class DrainCompletionTests {
        private ClusterDeploymentManager cdm;
        private final List<KVCommand<AetherKey>> capturedCommands = new ArrayList<>();
        private final CopyOnWriteArrayList<HealthSignal> capturedSignals = new CopyOnWriteArrayList<>();
        private final AtomicReference<java.util.Set<NodeId>> countedMembersRef = new AtomicReference<>(Set.of());
        private final AtomicReference<java.util.Set<NodeId>> drainingRef = new AtomicReference<>(Set.of());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            capturedSignals.clear();
            // Membership-v2: presence IS membership. The draining set is the real
            // NodeReportedState.DRAINING source, fed via drainingRef (mutated by the test).
            countedMembersRef.set(Set.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE));
            drainingRef.set(Set.of());
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE);
            var router = MessageRouter.mutable();

            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());

            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands);

            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);

            HealthSignalSink capturingSink = capturedSignals::add;
            Supplier<java.util.Set<NodeId>> countedMembersSupplier = countedMembersRef::get;

            cdm = ClusterDeploymentManager.clusterDeploymentManager(NODE_1,
                                                                     clusterNode,
                                                                     kvStore,
                                                                     router,
                                                                     initialTopology,
                                                                     topologyManager,
                                                                     ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING,
                                                                     3,
                                                                     timeSpan(300).seconds(),
                                                                     NO_OP_SCHEMA_ORCHESTRATOR,
                                                                     capturingSink,
                                                                     countedMembersSupplier,
                                                                     Set::of,
                                                                     drainingRef::get);
        }

        @Test
        void completeDrain_emitsDrainCompletedSignal() throws InterruptedException {
            // Spec §8 single-writer rule: CDM MUST NOT write the membership atom directly on drain
            // completion. Instead it emits a DrainCompleted signal.
            cdm.activate().await();

            // Membership-v2: the target now reports DRAINING via the real pong-readiness source.
            drainingRef.set(Set.of(DRAINING_NODE));
            cdm.onMembershipDecision(MembershipDecision.nodeDraining(
                    DRAINING_NODE,
                    List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE)));

            // Give async operations time to complete
            Thread.sleep(500);

            // DrainCompleted MUST be emitted via the health sink
            var drainCompletedSignals = capturedSignals.stream()
                .filter(HealthSignal.DrainCompleted.class::isInstance)
                .map(HealthSignal.DrainCompleted.class::cast)
                .toList();
            assertThat(drainCompletedSignals).hasSize(1);
            assertThat(drainCompletedSignals.getFirst().nodeId()).isEqualTo(DRAINING_NODE);
        }
    }

    @Nested
    class SnapshotDerivedMembershipTests {
        private ClusterDeploymentManager cdm;
        private final List<KVCommand<AetherKey>> capturedCommands = new ArrayList<>();
        private final CopyOnWriteArrayList<HealthSignal> capturedSignals = new CopyOnWriteArrayList<>();
        private final AtomicReference<java.util.Set<NodeId>> countedMembersRef = new AtomicReference<>(Set.of());
        private final AtomicReference<java.util.Set<NodeId>> drainingRef = new AtomicReference<>(Set.of());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            capturedSignals.clear();
            countedMembersRef.set(Set.of());
            drainingRef.set(Set.of());
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE);
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands);
            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);
            HealthSignalSink capturingSink = capturedSignals::add;
            Supplier<java.util.Set<NodeId>> countedMembersSupplier = countedMembersRef::get;
            cdm = ClusterDeploymentManager.clusterDeploymentManager(NODE_1,
                                                                     clusterNode,
                                                                     kvStore,
                                                                     router,
                                                                     initialTopology,
                                                                     topologyManager,
                                                                     ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING,
                                                                     3,
                                                                     timeSpan(300).seconds(),
                                                                     NO_OP_SCHEMA_ORCHESTRATOR,
                                                                     capturingSink,
                                                                     countedMembersSupplier,
                                                                     Set::of,
                                                                     drainingRef::get);
        }

        @Test
        void drainingNodes_derived_from_reported_state() throws Exception {
            var active = activateAndGetActive();
            countedMembersRef.set(Set.of(NODE_1, DRAINING_NODE));
            drainingRef.set(Set.of(DRAINING_NODE));
            var draining = invokeDrainingNodes(active);
            assertThat(draining).containsExactly(DRAINING_NODE);

            drainingRef.set(Set.of());
            var drainingAfter = invokeDrainingNodes(active);
            assertThat(drainingAfter).isEmpty();
        }

        @Test
        void activeNodes_derived_from_snapshot_presence() throws Exception {
            var active = activateAndGetActive();
            countedMembersRef.set(Set.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE));
            var activeIds = invokeActiveNodes(active);
            // Presence-derived: every present member is active (DRAINING is still tracked while
            // drain is in progress).
            assertThat(activeIds).containsExactlyInAnyOrder(NODE_1, NODE_2, NODE_3, DRAINING_NODE);

            countedMembersRef.set(Set.of(NODE_1, NODE_2, NODE_3));
            var activeIdsAfter = invokeActiveNodes(active);
            assertThat(activeIdsAfter).containsExactlyInAnyOrder(NODE_1, NODE_2, NODE_3);
        }

        private org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active activateAndGetActive() {
            cdm.activate().await();
            var adapter = (ClusterDeploymentManager.ClusterDeploymentManagerAdapter) cdm;
            return (org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active) adapter.context().fsm().current();
        }

        private List<NodeId> invokeActiveNodes(org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active active) {
            return active.activeNodes();
        }

        private java.util.Set<NodeId> invokeDrainingNodes(org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState.Active active) {
            return active.drainingNodes();
        }
    }

    /// Wave 2 / W3 (cluster-topology-overhaul spec): role assignment counts CORES only. The
    /// `currentCoreCount` denominator is `activeNodes()` (the CORE-SCOPED supplier, workers
    /// excluded at the `MembershipFsm.coreCountedMembers()` seam) minus the joiner itself (see
    /// `ReplacementRoleAssignmentTests`) — so a would-be core below `coreMax` is never
    /// mis-assigned WORKER because workers inflated the count, and a join at `coreMax` cores is
    /// assigned WORKER.
    @Nested
    class RoleAssignmentTests {
        private static final NodeId NEW_NODE = new NodeId("node-new");

        private ClusterDeploymentManager cdm;
        private final List<KVCommand<AetherKey>> capturedCommands = new ArrayList<>();
        private final AtomicReference<java.util.Set<NodeId>> coreCountedRef = new AtomicReference<>(Set.of());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            coreCountedRef.set(Set.of());
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3);
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands);
            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);

            cdm = ClusterDeploymentManager.clusterDeploymentManager(NODE_1,
                                                                     clusterNode,
                                                                     kvStore,
                                                                     router,
                                                                     initialTopology,
                                                                     topologyManager,
                                                                     ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING,
                                                                     3,
                                                                     timeSpan(300).seconds(),
                                                                     NO_OP_SCHEMA_ORCHESTRATOR,
                                                                     capturedSignals::add,
                                                                     coreCountedRef::get,
                                                                     Set::of,
                                                                     Set::of);
        }

        private final CopyOnWriteArrayList<HealthSignal> capturedSignals = new CopyOnWriteArrayList<>();

        @Test
        void nodeJoined_belowCoreMax_assignedCoreDirective() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(NEW_NODE, List.of(NODE_1, NODE_2, NEW_NODE)));

            assertThat(directivesFor(NEW_NODE)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

        @Test
        void nodeJoined_atCoreMax_assignedWorkerDirective() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2, NODE_3));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(NEW_NODE, List.of(NODE_1, NODE_2, NODE_3, NEW_NODE)));

            assertThat(directivesFor(NEW_NODE)).containsExactly(AetherValue.ActivationDirectiveValue.worker());
        }

        /// The W3 regression shape: 2 cores + 2 workers in a role-BLIND count (4 ≥ coreMax 3)
        /// would mis-assign the joiner as WORKER. The core-scoped supplier sees 2 cores, so the
        /// joiner is correctly promoted to CORE.
        @Test
        void nodeJoined_workersDoNotInflateCoreCount_joinerStillPromotedToCore() {
            cdm.activate().await();
            // Core-scoped supplier: the two workers in the cluster are NOT in this set.
            coreCountedRef.set(Set.of(NODE_1, NODE_2));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(NEW_NODE, List.of(NODE_1, NODE_2, NEW_NODE)));

            assertThat(directivesFor(NEW_NODE)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

        @SuppressWarnings("unchecked")
        private List<AetherValue.ActivationDirectiveValue> directivesFor(NodeId node) {
            var key = AetherKey.ActivationDirectiveKey.activationDirectiveKey(node);

            return capturedCommands.stream()
                                   .filter(KVCommand.Put.class::isInstance)
                                   .map(cmd -> (KVCommand.Put<AetherKey, AetherValue>) cmd)
                                   .filter(put -> put.key().equals(key))
                                   .map(KVCommand.Put::value)
                                   .filter(AetherValue.ActivationDirectiveValue.class::isInstance)
                                   .map(AetherValue.ActivationDirectiveValue.class::cast)
                                   .toList();
        }
    }

    /// Regression for the replacement-demotion bug: `assignNodeRole`'s denominator,
    /// `activeNodes()`, derives from `MembershipFsm.coreCountedMembers()`, which already
    /// includes the joiner by the time the NodeJoined decision reaches role assignment (the FSM
    /// stamps it Member first; deterministic ordering with Wave-4's edge-driven emission). A
    /// self-inclusive count made every count-restoring replacement on a 5-target cluster see
    /// "core count at max: 5" and demoted it to WORKER — observer-mode engine, stuck SYNCING,
    /// voter-set decay. The joiner must be classified by the cluster's state WITHOUT it.
    @Nested
    class ReplacementRoleAssignmentTests {
        private static final NodeId NODE_4 = new NodeId("node-4");
        private static final NodeId NODE_5 = new NodeId("node-5");
        private static final NodeId REPLACEMENT = new NodeId("node-replacement");
        private static final int CORE_TARGET = 5;

        private ClusterDeploymentManager cdm;
        private final List<KVCommand<AetherKey>> capturedCommands = new ArrayList<>();
        private final CopyOnWriteArrayList<HealthSignal> capturedSignals = new CopyOnWriteArrayList<>();
        private final AtomicReference<java.util.Set<NodeId>> coreCountedRef = new AtomicReference<>(Set.of());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            capturedSignals.clear();
            coreCountedRef.set(Set.of());
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3);
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands);
            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);

            cdm = ClusterDeploymentManager.clusterDeploymentManager(NODE_1,
                                                                     clusterNode,
                                                                     kvStore,
                                                                     router,
                                                                     initialTopology,
                                                                     topologyManager,
                                                                     ClusterDeploymentManager.DeploymentAtomicity.ALL_OR_NOTHING,
                                                                     CORE_TARGET,
                                                                     timeSpan(300).seconds(),
                                                                     NO_OP_SCHEMA_ORCHESTRATOR,
                                                                     capturedSignals::add,
                                                                     coreCountedRef::get,
                                                                     Set::of,
                                                                     Set::of);
        }

        /// The live bug shape: single kill on a 5-target cluster, replacement joins. The FSM
        /// already counts the joiner — 4 survivors + joiner = 5 in the supplier set. The
        /// self-inclusive count saw 5 ≥ max 5 → WORKER; with self-exclusion the cluster WITHOUT
        /// the joiner has 4 cores → CORE.
        @Test
        void nodeJoined_joinerAlreadyCounted_countRestoringReplacementAssignedCore() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2, NODE_3, NODE_4, REPLACEMENT));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(REPLACEMENT,
                                                                   List.of(NODE_1, NODE_2, NODE_3, NODE_4, REPLACEMENT)));

            assertThat(directivesFor(REPLACEMENT)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

        /// Genuinely at capacity: 5 counted cores NOT including the joiner — a true 6th node.
        /// Self-exclusion removes nothing; the joiner is correctly assigned WORKER.
        @Test
        void nodeJoined_clusterGenuinelyAtCapacity_sixthNodeAssignedWorker() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2, NODE_3, NODE_4, NODE_5));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(REPLACEMENT,
                                                                   List.of(NODE_1, NODE_2, NODE_3, NODE_4, NODE_5, REPLACEMENT)));

            assertThat(directivesFor(REPLACEMENT)).containsExactly(AetherValue.ActivationDirectiveValue.worker());
        }

        /// Double-kill heal (the only shape that got a CORE promotion live, because the count
        /// happened to be 4): 3 survivors + joiner already counted → 3 < 5 → CORE.
        @Test
        void nodeJoined_doubleKillHealJoinerAlreadyCounted_assignedCore() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2, NODE_3, REPLACEMENT));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(REPLACEMENT,
                                                                   List.of(NODE_1, NODE_2, NODE_3, REPLACEMENT)));

            assertThat(directivesFor(REPLACEMENT)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

        @SuppressWarnings("unchecked")
        private List<AetherValue.ActivationDirectiveValue> directivesFor(NodeId node) {
            var key = AetherKey.ActivationDirectiveKey.activationDirectiveKey(node);

            return capturedCommands.stream()
                                   .filter(KVCommand.Put.class::isInstance)
                                   .map(cmd -> (KVCommand.Put<AetherKey, AetherValue>) cmd)
                                   .filter(put -> put.key().equals(key))
                                   .map(KVCommand.Put::value)
                                   .filter(AetherValue.ActivationDirectiveValue.class::isInstance)
                                   .map(AetherValue.ActivationDirectiveValue.class::cast)
                                   .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static ClusterNode<KVCommand<AetherKey>> stubClusterNode(NodeId self,
                                                                      List<KVCommand<AetherKey>> capturedCommands) {
        return new ClusterNode<>() {
            @Override
            public NodeId self() {
                return self;
            }

            @Override
            public TopologyManager topologyManager() {
                return stubTopologyManager(self, List.of(self));
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
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                capturedCommands.addAll(commands);
                return Promise.success(Collections.emptyList());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self, List<NodeId> topology) {
        return new TopologyManager() {
            @Override
            public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override
            public int clusterSize() {
                return topology.size();
            }

            @Override
            public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
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
            public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override
            public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override
            public List<NodeId> topology() {
                return topology;
            }
        };
    }

    private static org.pragmatica.serialization.Serializer stubSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private static org.pragmatica.serialization.Deserializer stubDeserializer() {
        return new org.pragmatica.serialization.Deserializer() {
            @Override
            public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
