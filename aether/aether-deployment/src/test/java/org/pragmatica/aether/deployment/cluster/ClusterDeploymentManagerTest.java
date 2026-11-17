// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
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
        private final AtomicReference<java.util.Set<NodeId>> countedMembersRef = new AtomicReference<>(Set.of());
        private final AtomicReference<java.util.Set<NodeId>> drainingRef = new AtomicReference<>(Set.of());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            // Membership-v2: presence IS membership. The draining set is the real
            // NodeReportedState.DRAINING source, fed via drainingRef (mutated by the test).
            countedMembersRef.set(Set.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE));
            drainingRef.set(Set.of());
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE);
            var router = MessageRouter.mutable();

            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());

            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands, kvStore);

            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);

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
                                                                     countedMembersSupplier,
                                                                     Set::of,
                                                                     drainingRef::get);
        }

        /// Spec §8 single-writer rule: the CDM is not a membership writer. Membership is
        /// FSM/SWIM-derived, so drain completion issues no KV command at all.
        @Test
        void completeDrain_writesNoKvCommand() throws InterruptedException {
            cdm.activate().await();

            drainingRef.set(Set.of(DRAINING_NODE));
            cdm.onMembershipDecision(MembershipDecision.nodeDraining(
                    DRAINING_NODE,
                    List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE)));

            // Give async operations time to complete
            Thread.sleep(500);

            assertThat(capturedCommands).isEmpty();
        }
    }

    @Nested
    class SnapshotDerivedMembershipTests {
        private ClusterDeploymentManager cdm;
        private final List<KVCommand<AetherKey>> capturedCommands = new ArrayList<>();
        private final AtomicReference<java.util.Set<NodeId>> countedMembersRef = new AtomicReference<>(Set.of());
        private final AtomicReference<java.util.Set<NodeId>> drainingRef = new AtomicReference<>(Set.of());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            countedMembersRef.set(Set.of());
            drainingRef.set(Set.of());
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE);
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands, kvStore);
            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);
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

    /// Activation preserves the role declared by the membership channel, independently of capacity.
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
            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands, kvStore);
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
                                                                     coreCountedRef::get,
                                                                     Set::of,
                                                                     Set::of);
        }

        @Test
        void nodeJoined_belowCoreMax_assignedCoreDirective() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(NEW_NODE, List.of(NODE_1, NODE_2, NEW_NODE)));

            assertThat(directivesFor(NEW_NODE)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

        @Test
        void declaredCoreJoined_atCoreMax_remainsCore() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2, NODE_3));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(NEW_NODE, List.of(NODE_1, NODE_2, NODE_3, NEW_NODE)));

            assertThat(directivesFor(NEW_NODE)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

        @Test
        void declaredWorkerJoined_belowCoreTarget_remainsWorker() {
            cdm.activate().await();
            // Core-scoped supplier: the two workers in the cluster are NOT in this set.
            coreCountedRef.set(Set.of(NODE_1, NODE_2));

            cdm.onWorkerJoin(new org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision(NEW_NODE, "worker", org.pragmatica.hlc.HlcTimestamp.ZERO));

            assertThat(directivesFor(NEW_NODE)).containsExactly(AetherValue.ActivationDirectiveValue.worker("default-w-0", ""));
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

    @Nested
    class ReplacementRoleAssignmentTests {
        private static final NodeId NODE_4 = new NodeId("node-4");
        private static final NodeId NODE_5 = new NodeId("node-5");
        private static final NodeId REPLACEMENT = new NodeId("node-replacement");
        private static final int CORE_TARGET = 5;

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
            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands, kvStore);
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
                                                                     coreCountedRef::get,
                                                                     Set::of,
                                                                     Set::of);
        }

        @Test
        void nodeJoined_joinerAlreadyCounted_countRestoringReplacementAssignedCore() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2, NODE_3, NODE_4, REPLACEMENT));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(REPLACEMENT,
                                                                   List.of(NODE_1, NODE_2, NODE_3, NODE_4, REPLACEMENT)));

            assertThat(directivesFor(REPLACEMENT)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

        @Test
        void declaredCoreJoined_aboveCapacity_isNeverDemoted() {
            cdm.activate().await();
            coreCountedRef.set(Set.of(NODE_1, NODE_2, NODE_3, NODE_4, NODE_5));

            cdm.onMembershipDecision(MembershipDecision.nodeJoined(REPLACEMENT,
                                                                   List.of(NODE_1, NODE_2, NODE_3, NODE_4, NODE_5, REPLACEMENT)));

            assertThat(directivesFor(REPLACEMENT)).containsExactly(AetherValue.ActivationDirectiveValue.core());
        }

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
                                                                      List<KVCommand<AetherKey>> capturedCommands, KVStore<AetherKey, AetherValue> store) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
            new org.pragmatica.cluster.state.kvstore.LeaderValue(self, 1)))));
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
                for (var command : commands) {
                    if (command instanceof KVCommand.LeaderTransaction<?, ?> transaction) {
                        transaction.mutations().forEach(mutation -> mutation.replacement().onPresent(value ->
                            capturedCommands.add(new KVCommand.Put<>((AetherKey) mutation.key(), value))));
                    }
                }
                return Promise.success(store.process(store.createBatch(commands)));
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
