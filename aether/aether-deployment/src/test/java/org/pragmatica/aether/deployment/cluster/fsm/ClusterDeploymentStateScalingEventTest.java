// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.metrics.deployment.ReconciliationAdjustment;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// W-17 emitter half (v-2196 nit 2, phase-3 design W-17): the legacy leader's `emitScalingEvent` routes the neutral
/// `ReconciliationAdjustment` through the node's router with the counts it logs. Red when the `route` call is deleted
/// or sends the wrong counts, so the subscribers (`ClusterEventAggregator`, the node's route) are fed by the emitter and
/// not only by the compiler's type identity. Each direction has its own test, and the balanced case routes nothing.
class ClusterDeploymentStateScalingEventTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-scaling:1.0.0").unwrap();

    private final List<ReconciliationAdjustment> routed = Collections.synchronizedList(new ArrayList<>());
    private RecordingClusterNode cluster;
    private InMemoryKvStore kvStore;
    private Set<NodeId> readyNodes;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        router.addRoute(ReconciliationAdjustment.class, routed::add);
        kvStore = new InMemoryKvStore(router);
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
            new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1)))));
        cluster = new RecordingClusterNode(SELF);
        readyNodes = ConcurrentHashMap.newKeySet();
        readyNodes.addAll(Set.of(SELF, NODE_A));

        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, NODE_A),
                                                    () -> readyNodes,
                                                    Set::of,
                                                    Set.of(SELF),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds()).dormant();
        harness = FsmTestHarness.harness("scaling-event-test-" + SELF.id(), factory);
        harness.dispatch(new Activate());
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private void seedBlueprint(int instances) {
        activeState().blueprints().put(ARTIFACT, Blueprint.blueprint(ARTIFACT, instances, 1, Option.empty(), true));
        kvStore.put(AetherKey.SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
                    AetherValue.SliceTargetValue.sliceTargetValue(ARTIFACT.version(), instances));
    }

    private void seedSliceActive(NodeId node) {
        activeState().sliceStates().put(SliceNodeKey.sliceNodeKey(ARTIFACT, node), SliceState.ACTIVE);
    }

    @Test
    void reconcile_routesAdjustment_onScaleUp() {
        seedBlueprint(2);
        seedSliceActive(SELF);

        activeState().reconcile();

        assertThat(routed).containsExactly(ReconciliationAdjustment.reconciliationAdjustment(ARTIFACT, 1, 2));
    }

    @Test
    void reconcile_routesAdjustment_onScaleDown() {
        seedBlueprint(1);
        seedSliceActive(SELF);
        seedSliceActive(NODE_A);

        activeState().reconcile();

        assertThat(routed).containsExactly(ReconciliationAdjustment.reconciliationAdjustment(ARTIFACT, 2, 1));
    }

    @Test
    void reconcile_routesNothing_whenCountMatchesTarget() {
        seedBlueprint(2);
        seedSliceActive(SELF);
        seedSliceActive(NODE_A);

        activeState().reconcile();

        assertThat(routed).isEmpty();
    }

    // --- test fixtures ---

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 1;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            return Promise.success(Collections.emptyList());
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            if (value instanceof org.pragmatica.cluster.state.kvstore.LeaderAuthorized) {
                var leader = getTyped(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
                    org.pragmatica.cluster.state.kvstore.LeaderValue.class).unwrap();
                process(createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                    java.util.UUID.randomUUID().toString(), leader, List.of(),
                    List.of(new KVCommand.Mutation<>(key, get(key), Option.some(value)))))));
            } else {
                process(createBatch(List.of(new KVCommand.Put<>(key, value))));
            }
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
