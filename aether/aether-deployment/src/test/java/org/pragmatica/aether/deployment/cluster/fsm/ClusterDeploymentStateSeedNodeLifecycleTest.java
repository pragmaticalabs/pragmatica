// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
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
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision.NodeJoined;
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


/// Regression coverage for the seed-node lifecycle write bug
/// (`ClusterDeploymentState.handleNodeAdded`).
///
/// Before the fix, when `NodeJoined` arrived for a node listed in `ctx.seedNodes()` the
/// state skipped `assignNodeRole` entirely, which meant no `ActivationDirective` was
/// submitted and the `MembershipFsm` never wrote the seed node's `NodeLifecycleKey`. The
/// node was visible via consensus-topology endpoints but missing from
/// `/api/nodes/lifecycle`, `core.members[]`, and the KV-Store atom.
///
/// The fix plants `NodeLifecycleValue(JOINING)` for the seed node when no entry yet
/// exists, then lets the standard MembershipFsm path drive JOINING → ON_DUTY. It must
/// be idempotent: an existing entry (whatever the state) is preserved.
class ClusterDeploymentStateSeedNodeLifecycleTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId SEED_PEER = new NodeId("node-seed-peer");
    private static final NodeId JOINER = new NodeId("node-joiner");

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private AtomicReference<Option<ClusterGenerationSnapshot>> snapshotRef;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        snapshotRef = new AtomicReference<>(Option.some(buildSnapshot(List.of(SELF, SEED_PEER))));

        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    HealthSignalSink.noop(),
                                                    snapshotRef::get,
                                                    Set.of(SELF, SEED_PEER),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    3,
                                                    timeSpan(300).seconds()).dormant();
        harness = FsmTestHarness.harness("seed-lifecycle-test-" + SELF.id(), factory);
        // Become Active so handleNodeAdded is reachable via MembershipDecisionReceived.
        harness.dispatch(new Activate());
        cluster.commands.clear();
    }

    @Test
    void seedNodeJoinedWithoutLifecycleEntry_plantsJoiningInKv() {
        // Pre-condition: no NodeLifecycleKey for the seed peer.
        assertThat(kvStore.get(NodeLifecycleKey.nodeLifecycleKey(SEED_PEER)).isEmpty())
                .as("precondition: seed peer must have no NodeLifecycleKey before NodeJoined")
                .isTrue();

        harness.dispatch(new MembershipDecisionReceived(new NodeJoined(SEED_PEER,
                                                                       List.of(SELF, SEED_PEER),
                                                                       0L,
                                                                       HlcTimestamp.ZERO)));

        var planted = cluster.commands.stream()
                                       .filter(c -> c instanceof KVCommand.Put<?, ?>)
                                       .map(c -> (KVCommand.Put<?, ?>) c)
                                       .filter(c -> c.key() instanceof NodeLifecycleKey lk && lk.nodeId().equals(SEED_PEER))
                                       .toList();
        assertThat(planted)
                .as("seed node missing its lifecycle entry must trigger exactly one JOINING write")
                .hasSize(1);
        assertThat(planted.getFirst().value())
                .as("planted value must be NodeLifecycleValue(JOINING)")
                .isInstanceOfSatisfying(NodeLifecycleValue.class,
                                         v -> assertThat(v.state()).isEqualTo(NodeLifecycleState.JOINING));
    }

    @Test
    void seedNodeJoinedWithExistingLifecycleEntry_isNoOp() {
        // Pre-populate KV with a non-JOINING state to prove we don't overwrite richer state.
        kvStore.put(NodeLifecycleKey.nodeLifecycleKey(SEED_PEER),
                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING));
        cluster.commands.clear();

        harness.dispatch(new MembershipDecisionReceived(new NodeJoined(SEED_PEER,
                                                                       List.of(SELF, SEED_PEER),
                                                                       0L,
                                                                       HlcTimestamp.ZERO)));

        var lifecycleWrites = cluster.commands.stream()
                                               .filter(c -> c instanceof KVCommand.Put<?, ?>)
                                               .map(c -> (KVCommand.Put<?, ?>) c)
                                               .filter(c -> c.key() instanceof NodeLifecycleKey lk && lk.nodeId().equals(SEED_PEER))
                                               .toList();
        assertThat(lifecycleWrites)
                .as("existing seed lifecycle entry must be preserved; no overwrite")
                .isEmpty();
    }

    @Test
    void nonSeedNodeJoined_doesNotPlantLifecycleEntry() {
        // Joiners go through assignNodeRole → submitActivationDirective (which writes an
        // ActivationDirectiveKey, not a NodeLifecycleKey). The new helper must not run
        // for non-seed nodes.
        harness.dispatch(new MembershipDecisionReceived(new NodeJoined(JOINER,
                                                                       List.of(SELF, SEED_PEER, JOINER),
                                                                       0L,
                                                                       HlcTimestamp.ZERO)));

        var lifecycleWrites = cluster.commands.stream()
                                               .filter(c -> c instanceof KVCommand.Put<?, ?>)
                                               .map(c -> (KVCommand.Put<?, ?>) c)
                                               .filter(c -> c.key() instanceof NodeLifecycleKey lk && lk.nodeId().equals(JOINER))
                                               .toList();
        assertThat(lifecycleWrites)
                .as("non-seed joiner must not get a lifecycle write from handleNodeAdded")
                .isEmpty();
    }

    // --- test fixtures (cloned from ClusterDeploymentStateRebalanceOnScaleUpTest) ---

    private static ClusterGenerationSnapshot buildSnapshot(List<NodeId> coreMembers) {
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
        return ClusterGenerationSnapshot.clusterGenerationSnapshot(Epoch.epoch(1L, 0L),
                                                                    HlcTimestamp.ZERO,
                                                                    GenerationReason.LEADER_ELECTED,
                                                                    coreMembers.size(),
                                                                    members,
                                                                    Map.of(),
                                                                    Map.of(),
                                                                    ClusterMode.CORE_ONLY,
                                                                    ClusterQuiescence.QUIESCED,
                                                                    "");
    }

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
                return 2;
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
