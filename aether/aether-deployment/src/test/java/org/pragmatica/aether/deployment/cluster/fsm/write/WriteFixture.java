// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
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
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.netty.buffer.ByteBuf;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Shared fixture of the #2584 write-path tests: a real `KVStore`, a recording `ClusterNode` that applies what it is
/// given into that store as consensus does, and a harness that drives the real leader FSM against both.
final class WriteFixture {
    static final NodeId SELF = new NodeId("node-self");
    static final NodeId OTHER = new NodeId("node-other");

    private WriteFixture() {}

    static KVStore<AetherKey, AetherValue> freshStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void commitLeader(KVStore<AetherKey, AetherValue> store, NodeId leader, long viewSequence) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(leader, viewSequence)))));
    }

    @SafeVarargs
    static void seed(KVStore<AetherKey, AetherValue> store, KVCommand<AetherKey>... commands) {
        store.process(store.createBatch(List.of(commands)));
    }

    static FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> activeLeader(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                               KVStore<AetherKey, AetherValue> store,
                                                                               Set<NodeId> seeds) {
        var router = MessageRouter.mutable();
        LongSupplier clock = () -> 10_000_000L;
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    store,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF, OTHER),
                                                    () -> Set.of(SELF, OTHER),
                                                    Set::of,
                                                    seeds,
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds(),
                                                    clock).dormant();
        var harness = FsmTestHarness.<ClusterDeploymentState, ClusterFsmEvent>harness("write-path-2584-" + SELF.id(), factory);

        harness.dispatch(new Activate());

        return harness;
    }

    static ClusterDeploymentState.Active active(FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness) {
        return (ClusterDeploymentState.Active) harness.state();
    }

    /// Applies every batch into the store, as consensus does, and records it with the applier's results. An optional
    /// interference runs before each batch, and an optional failure replaces the apply.
    static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final KVStore<AetherKey, AetherValue> store;
        private final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();
        private final List<Object> results = new ArrayList<>();
        private Consumer<List<KVCommand<AetherKey>>> interference = _ -> {};
        private Option<Cause> failure = Option.none();

        RecordingClusterNode(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
        }

        void beforeEachBatch(Consumer<List<KVCommand<AetherKey>>> interference) {
            this.interference = interference;
        }

        void failWith(Cause cause) {
            this.failure = Option.some(cause);
        }

        List<List<KVCommand<AetherKey>>> batches() {
            return batches;
        }

        List<Object> results() {
            return results;
        }

        void forget() {
            batches.clear();
            results.clear();
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(SELF);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @SuppressWarnings("unchecked")
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            batches.add(List.copyOf(batch));
            interference.accept(batch);
            if (failure.isPresent()) {
                return failure.unwrap().promise();
            }
            List<Object> applied = store.process(store.createBatch(batch));

            results.addAll(applied);

            return Promise.success((List<R>) applied);
        }
    }

    static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {return Promise.success(Unit.unit());}

            @Override public Promise<Unit> baseline(String datasourceName, int version) {return Promise.success(Unit.unit());}
        };
    }

    static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));}

            @Override public Option<NodeInfo> get(NodeId id) {return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));}

            @Override public int clusterSize() {return 2;}

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}

            @Override public TimeSpan pingInterval() {return timeSpan(5).seconds();}

            @Override public TimeSpan helloTimeout() {return timeSpan(5).seconds();}

            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}

            @Override public List<NodeId> topology() {return List.of(self);}
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {return null;}
        };
    }
}
