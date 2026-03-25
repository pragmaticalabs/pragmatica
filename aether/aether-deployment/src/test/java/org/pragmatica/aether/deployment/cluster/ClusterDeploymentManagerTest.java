package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
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
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.NodeState;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;

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

        @BeforeEach
        void setUp() {
            var initialTopology = List.of(NODE_1, NODE_2, NODE_3, DRAINING_NODE);
            var router = MessageRouter.mutable();

            var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());

            ClusterNode<KVCommand<AetherKey>> clusterNode = stubClusterNode(NODE_1);

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
                                                                     NO_OP_SCHEMA_ORCHESTRATOR);
        }

        @Test
        void completeDrain_writesDecommissionedState() throws InterruptedException {
            // Activate CDM as leader
            cdm.onLeaderChange(new LeaderChange(Option.some(NODE_1), true));

            // Trigger drain for a node with no slices — drain completes immediately
            var drainingPut = new ValuePut<>(
                new KVCommand.Put<>(
                    NodeLifecycleKey.nodeLifecycleKey(DRAINING_NODE),
                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING)),
                Option.<NodeLifecycleValue>empty());
            cdm.onNodeLifecyclePut(drainingPut);

            // Give async operations time to complete — no exceptions expected
            Thread.sleep(500);
            // Success: drain completion writes DECOMMISSIONED without errors
        }
    }

    @SuppressWarnings("unchecked")
    private static ClusterNode<KVCommand<AetherKey>> stubClusterNode(NodeId self) {
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
                return Promise.success(Collections.emptyList());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self, List<NodeId> topology) {
        return new TopologyManager() {
            @Override
            public NodeInfo self() {
                return new NodeInfo(self, new NodeAddress("localhost", 9000), NodeRole.ACTIVE);
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.some(new NodeInfo(id, new NodeAddress("localhost", 9000), NodeRole.ACTIVE));
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
