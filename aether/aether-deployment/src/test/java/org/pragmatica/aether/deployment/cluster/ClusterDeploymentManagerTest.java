// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        private final AtomicReference<Option<ClusterGenerationSnapshot>> snapshotRef = new AtomicReference<>(Option.none());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            capturedSignals.clear();
            // Pre-drain: all nodes ON_DUTY. The KV event fired in the test transitions the node
            // to DRAINING in both KV (for test) and in the snapshot supplier.
            snapshotRef.set(Option.some(snapshotWithLifecycles(Map.of(NODE_1, NodeLifecycleState.ON_DUTY,
                                                                      NODE_2, NodeLifecycleState.ON_DUTY,
                                                                      NODE_3, NodeLifecycleState.ON_DUTY,
                                                                      DRAINING_NODE, NodeLifecycleState.ON_DUTY))));
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE);
            var router = MessageRouter.mutable();

            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());

            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands);

            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);

            HealthSignalSink capturingSink = capturedSignals::add;
            Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier = snapshotRef::get;

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
                                                                     snapshotSupplier);
        }

        @Test
        void completeDrain_emitsDrainCompletedSignal() throws InterruptedException {
            // Spec §8 single-writer rule: CDM MUST NOT write NodeLifecycleKey directly on drain
            // completion. Instead it emits a DrainCompleted signal so MembershipFsm — the sole
            // membership atom writer — can transition the lifecycle authoritatively.
            cdm.activate().await();

            // Simulate leader's Rabia write of NodeLifecycleKey=DRAINING: snapshot reflects new state
            snapshotRef.set(Option.some(snapshotWithLifecycles(Map.of(NODE_1, NodeLifecycleState.ON_DUTY,
                                                                      NODE_2, NodeLifecycleState.ON_DUTY,
                                                                      NODE_3, NodeLifecycleState.ON_DUTY,
                                                                      DRAINING_NODE, NodeLifecycleState.DRAINING))));
            // RC1 Step 2: drain trigger is now a MembershipDecision.NodeDraining
            // (TopologyObserver's lifecycle-projection walker emits one decision per
            // lifecycle transition). The retired `onNodeLifecyclePut` path is gone.
            cdm.onMembershipDecision(MembershipDecision.nodeDraining(
                    DRAINING_NODE,
                    List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE)));

            // Give async operations time to complete
            Thread.sleep(500);

            // CDM MUST NOT write NodeLifecycleKey directly
            var directLifecyclePuts = capturedCommands.stream()
                .filter(KVCommand.Put.class::isInstance)
                .map(cmd -> (KVCommand.Put<AetherKey, AetherValue>) cmd)
                .filter(put -> put.key() instanceof NodeLifecycleKey)
                .toList();
            assertThat(directLifecyclePuts)
                .as("CDM must not write NodeLifecycleKey directly (spec §8 single-writer)")
                .isEmpty();

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
        private final AtomicReference<Option<ClusterGenerationSnapshot>> snapshotRef = new AtomicReference<>(Option.none());

        @BeforeEach
        void setUp() {
            capturedCommands.clear();
            capturedSignals.clear();
            snapshotRef.set(Option.none());
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE);
            var router = MessageRouter.mutable();
            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1, capturedCommands);
            TopologyManager topologyManager = stubTopologyManager(NODE_1, initialTopology);
            HealthSignalSink capturingSink = capturedSignals::add;
            Supplier<Option<ClusterGenerationSnapshot>> snapshotSupplier = snapshotRef::get;
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
                                                                     snapshotSupplier);
        }

        @Test
        void drainingNodes_derived_from_snapshot_lifecycle() throws Exception {
            var active = activateAndGetActive();
            snapshotRef.set(Option.some(snapshotWithLifecycles(Map.of(NODE_1, NodeLifecycleState.ON_DUTY,
                                                                      DRAINING_NODE, NodeLifecycleState.DRAINING))));
            var draining = invokeDrainingNodes(active);
            assertThat(draining).containsExactly(DRAINING_NODE);

            snapshotRef.set(Option.some(snapshotWithLifecycles(Map.of(NODE_1, NodeLifecycleState.ON_DUTY,
                                                                      DRAINING_NODE, NodeLifecycleState.ON_DUTY))));
            var drainingAfter = invokeDrainingNodes(active);
            assertThat(drainingAfter).isEmpty();
        }

        @Test
        void activeNodes_derived_from_snapshot_onDutyMemberIds() throws Exception {
            var active = activateAndGetActive();
            snapshotRef.set(Option.some(snapshotWithLifecycles(Map.of(NODE_1, NodeLifecycleState.ON_DUTY,
                                                                      NODE_2, NodeLifecycleState.ON_DUTY,
                                                                      NODE_3, NodeLifecycleState.ON_DUTY,
                                                                      DRAINING_NODE, NodeLifecycleState.DRAINING))));
            var activeIds = invokeActiveNodes(active);
            // activeNodes excludes DECOMMISSIONED; DRAINING is still returned (leader tracks drain in progress)
            assertThat(activeIds).containsExactlyInAnyOrder(NODE_1, NODE_2, NODE_3, DRAINING_NODE);

            snapshotRef.set(Option.some(snapshotWithLifecycles(Map.of(NODE_1, NodeLifecycleState.ON_DUTY,
                                                                      NODE_2, NodeLifecycleState.ON_DUTY,
                                                                      NODE_3, NodeLifecycleState.ON_DUTY,
                                                                      DRAINING_NODE, NodeLifecycleState.STOPPED))));
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

    private static ClusterGenerationSnapshot snapshotWithLifecycles(Map<NodeId, NodeLifecycleState> lifecycles) {
        var members = new LinkedHashMap<NodeId, CoreMember>();
        lifecycles.forEach((id, state) -> members.put(id,
                                                       CoreMember.coreMember(id,
                                                                             "host-" + id.id(),
                                                                             9000,
                                                                             state,
                                                                             HealthHint.HEALTHY,
                                                                             Epoch.epoch(1L, 0L),
                                                                             Epoch.epoch(1L, 0L))));
        return ClusterGenerationSnapshot.empty(1L).withDesiredCoreSize(lifecycles.size())
                                                    .withCoreMembers(members);
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
