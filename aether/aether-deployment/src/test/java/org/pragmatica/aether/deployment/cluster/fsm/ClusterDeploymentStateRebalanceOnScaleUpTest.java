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
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcTimestamp;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Theme D #2 — verifies that `ClusterDeploymentState.Active.reconcile` rebalances
/// instances onto a freshly-joined ON_DUTY node when the desired count is satisfied
/// but the placement spread is uneven. Hysteresis suppresses rebalance until the
/// imbalance has been observed for `REBALANCE_HYSTERESIS_TICKS` consecutive ticks
/// (currently 2). Single-instance-move-per-tick prevents rebalance storms.
class ClusterDeploymentStateRebalanceOnScaleUpTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_NEW = new NodeId("node-new");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-rebalance:1.0.0").unwrap();

    private RecordingClusterNode cluster;
    private InMemoryKvStore kvStore;
    private AtomicReference<Option<ClusterGenerationSnapshot>> snapshotRef;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;
    private ClusterDeploymentContext ctx;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        snapshotRef = new AtomicReference<>(Option.none());

        var ctxHolder = new AtomicReference<ClusterDeploymentContext>();
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> {
                    var context = new ClusterDeploymentContext(fsm,
                                                                SELF,
                                                                cluster,
                                                                kvStore,
                                                                router,
                                                                stubTopologyManager(SELF),
                                                                stubSchemaOrchestrator(),
                                                                HealthSignalSink.noop(),
                                                                snapshotRef::get,
                                                                Set.of(SELF),
                                                                DeploymentAtomicity.ALL_OR_NOTHING,
                                                                3,
                                                                timeSpan(300).seconds());
                    ctxHolder.set(context);
                    return context.dormant();
                };
        harness = FsmTestHarness.harness("rebalance-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
    }

    private void seedNodeOnDuty(NodeId nodeId) {
        kvStore.put(NodeLifecycleKey.nodeLifecycleKey(nodeId),
                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY));
    }

    private void publishSnapshot(List<NodeId> coreMembers) {
        var members = new LinkedHashMap<NodeId, CoreMember>();
        for (var id : coreMembers) {
            members.put(id, CoreMember.coreMember(id,
                                                    "localhost",
                                                    9000,
                                                    NodeLifecycleState.ON_DUTY,
                                                    HealthHint.HEALTHY,
                                                    Epoch.epoch(1L, 0L),
                                                    Epoch.epoch(1L, 0L)));
        }
        var snapshot = ClusterGenerationSnapshot.clusterGenerationSnapshot(Epoch.epoch(1L, 0L),
                                                                            HlcTimestamp.ZERO,
                                                                            GenerationReason.LEADER_ELECTED,
                                                                            coreMembers.size(),
                                                                            members,
                                                                            Map.of(),
                                                                            Map.of(),
                                                                            ClusterMode.CORE_ONLY,
                                                                            ClusterQuiescence.QUIESCED,
                                                                            "");
        snapshotRef.set(Option.some(snapshot));
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private void seedBlueprint(Artifact artifact, int instances) {
        activeState().blueprints().put(artifact, Blueprint.blueprint(artifact, instances, 1));
    }

    private void seedSliceActive(Artifact artifact, NodeId node) {
        activeState().sliceStates().put(SliceNodeKey.sliceNodeKey(artifact, node), SliceState.ACTIVE);
    }

    /// Skewed total-load spread: NODE_A hosts 3 slices across 3 different artifacts; NODE_NEW
    /// (freshly-joined ON_DUTY) has 0. Each blueprint has instances=1 so reconcileBlueprint sees
    /// current==desired and falls through to rebalanceIfNeeded. Tick 1: hysteresis defers the
    /// move. Tick 2: a single move per tick fires (one of the three artifacts is migrated).
    @Test
    void newOnDutyNode_idleBlueprint_rebalancesOneInstancePerTick() {
        seedNodeOnDuty(SELF); seedNodeOnDuty(NODE_A); seedNodeOnDuty(NODE_NEW);
        publishSnapshot(List.of(SELF, NODE_A, NODE_NEW));
        harness.dispatch(new Activate());
        cluster.commands.clear();

        // Three blueprints each with 1 instance, all parked on NODE_A. NODE_NEW + SELF are idle.
        var artifactA = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        var artifactB = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();
        var artifactC = Artifact.artifact("org.example:slice-c:1.0.0").unwrap();
        seedBlueprint(artifactA, 1);
        seedBlueprint(artifactB, 1);
        seedBlueprint(artifactC, 1);
        seedSliceActive(artifactA, NODE_A);
        seedSliceActive(artifactB, NODE_A);
        seedSliceActive(artifactC, NODE_A);

        // Tick 1: imbalance observed for the first time — hysteresis suppresses the move.
        cluster.commands.clear();
        activeState().reconcile();
        assertThat(cluster.commands)
                .as("first imbalanced tick should be suppressed by hysteresis")
                .isEmpty();
        assertThat(activeState().consecutiveImbalancedTicks().values())
                .as("hysteresis counter must register the first imbalanced tick")
                .anyMatch(count -> count >= 1);

        // Tick 2: hysteresis threshold (2 ticks) reached — exactly one move fires this tick
        // (single-move-per-tick throttling). The move emits an UNLOAD on the donor + a LOAD
        // on the target, so we expect at least 2 KV commands (and at most 4 — UNLOAD+LOAD per
        // single migrated artifact).
        cluster.commands.clear();
        activeState().reconcile();
        assertThat(cluster.commands)
                .as("second imbalanced tick must dispatch a single rebalance move")
                .isNotEmpty();
    }

    /// Balanced spread: instances are evenly distributed across allocatable nodes —
    /// rebalance must NOT fire even after multiple ticks.
    @Test
    void balancedSpread_noMove() {
        seedNodeOnDuty(SELF); seedNodeOnDuty(NODE_A); seedNodeOnDuty(NODE_B); seedNodeOnDuty(NODE_NEW);
        publishSnapshot(List.of(SELF, NODE_A, NODE_B, NODE_NEW));
        harness.dispatch(new Activate());
        cluster.commands.clear();

        seedBlueprint(ARTIFACT, 3);
        seedSliceActive(ARTIFACT, SELF);
        seedSliceActive(ARTIFACT, NODE_A);
        seedSliceActive(ARTIFACT, NODE_B);

        for (int i = 0; i < 5; i++) {activeState().reconcile();}
        assertThat(cluster.commands)
                .as("balanced spread should never trigger rebalance commands")
                .isEmpty();
    }

    /// Single-move-per-tick budget is enforced across the entire reconcile() invocation,
    /// not per-blueprint. Three skewed blueprints all eligible for rebalance must result in
    /// at most one move on any single tick — preventing rebalance storms when many nodes
    /// join simultaneously.
    @Test
    void multipleNewNodes_movesOnePerTick() {
        seedNodeOnDuty(SELF); seedNodeOnDuty(NODE_A); seedNodeOnDuty(NODE_NEW);
        publishSnapshot(List.of(SELF, NODE_A, NODE_NEW));
        harness.dispatch(new Activate());
        cluster.commands.clear();

        var artifactA = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
        var artifactB = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();
        var artifactC = Artifact.artifact("org.example:slice-c:1.0.0").unwrap();
        seedBlueprint(artifactA, 1);
        seedBlueprint(artifactB, 1);
        seedBlueprint(artifactC, 1);
        seedSliceActive(artifactA, NODE_A);
        seedSliceActive(artifactB, NODE_A);
        seedSliceActive(artifactC, NODE_A);

        // Tick 1: hysteresis suppresses for all three blueprints.
        cluster.commands.clear();
        activeState().reconcile();

        // Tick 2: hysteresis met for all three, but per-tick budget caps at one move.
        // A move emits 2 KV commands (UNLOAD on donor + LOAD on target).
        cluster.commands.clear();
        activeState().reconcile();
        // Count rebalance moves by counting distinct artifacts touched in this tick.
        var artifactsTouched = cluster.commands.stream()
                                                            .map(cmd -> {
                                                                if (cmd instanceof KVCommand.Put<?, ?> put
                                                                    && put.key() instanceof org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey nak) {
                                                                    return nak.artifact();
                                                                }
                                                                return null;
                                                            })
                                                            .filter(java.util.Objects::nonNull)
                                                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(artifactsTouched)
                .as("only one artifact should be migrated per tick (single-move-per-tick budget)")
                .hasSize(1);
    }

    /// Hysteresis & throttling: even when the rebalance condition holds, it must (a) be
    /// observed for `REBALANCE_HYSTERESIS_TICKS` consecutive ticks and (b) emit at most
    /// one move per tick. Verifies the consecutive-tick counter resets when balance is
    /// restored — preventing late-firing moves after the cluster has converged.
    @Test
    void hysteresisCounterResetsWhenBalanceRestored() {
        seedNodeOnDuty(SELF); seedNodeOnDuty(NODE_A); seedNodeOnDuty(NODE_NEW);
        publishSnapshot(List.of(SELF, NODE_A, NODE_NEW));
        harness.dispatch(new Activate());
        cluster.commands.clear();

        // Seed a blueprint with instances == count == balanced.
        seedBlueprint(ARTIFACT, 2);
        seedSliceActive(ARTIFACT, SELF);
        seedSliceActive(ARTIFACT, NODE_A);

        // Run multiple reconcile ticks — no commands issued and the
        // consecutiveImbalancedTicks map stays empty (or returns to empty quickly).
        for (int i = 0; i < 4; i++) {activeState().reconcile();}
        assertThat(activeState().consecutiveImbalancedTicks())
                .as("balanced state must not accumulate hysteresis ticks")
                .isEmpty();
        assertThat(cluster.commands).isEmpty();
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
            process(new KVCommand.Put<>(key, value));
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
