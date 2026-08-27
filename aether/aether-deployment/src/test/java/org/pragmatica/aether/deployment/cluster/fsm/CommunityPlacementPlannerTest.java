// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
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
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #241 (worker-membership-spec D1 / §3.3): the placement planner's DESIRED community set is the
/// committed [`CommunityKey`]/[`CommunityValue`] facts, gated on the strictly `ACTIVE` state. Slice
/// 2's per-community FSM drives a community to `ACTIVE` once observed live membership reaches the
/// viability floor, so `FORMING` (not yet viable), `DEGRADED` (lost quorum), and terminal
/// `DISSOLVING`/`DISSOLVED` are all excluded from placement.
class CommunityPlacementPlannerTest {
    private static final NodeId SELF = new NodeId("node-self");

    private InMemoryKvStore kvStore;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        var cluster = new RecordingClusterNode(SELF);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    () -> Set.of(SELF),
                                                    () -> Set.of(SELF),
                                                    Set::of,
                                                    Set.of(SELF),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    1,
                                                    timeSpan(300).seconds()).dormant();
        harness = FsmTestHarness.harness("planner-desired-" + SELF.id(), factory);
        harness.dispatch(new Activate());
    }

    private CommunityPlacementPlanner planner() {
        return ((ClusterDeploymentState.Active) harness.state()).communityPlanner();
    }

    private void seedCommunity(String communityId, CommunityState state) {
        kvStore.put(CommunityKey.communityKey(communityId),
                    new CommunityValue("src", "WORKER", 100, state, 1L, Option.none()));
    }

    @Test
    void activeCommunityIds_committedActiveCommunity_isIncludedInDesiredSet() {
        seedCommunity("src-w-0", CommunityState.ACTIVE);

        assertThat(planner().activeCommunityIds())
                .as("a committed ACTIVE community is desired")
                .containsExactly("src-w-0");
    }

    @Test
    void activeCommunityIds_formingAndDegraded_areExcluded() {
        seedCommunity("forming-w-0", CommunityState.FORMING);
        seedCommunity("degraded-w-0", CommunityState.DEGRADED);

        assertThat(planner().activeCommunityIds())
                .as("FORMING (not yet viable) and DEGRADED (lost quorum) are excluded under strict-ACTIVE gating")
                .isEmpty();
    }

    @Test
    void activeCommunityIds_onlyActiveAmongMixedStates_isIncluded() {
        seedCommunity("forming-w-0", CommunityState.FORMING);
        seedCommunity("active-w-0", CommunityState.ACTIVE);
        seedCommunity("degraded-w-0", CommunityState.DEGRADED);

        assertThat(planner().activeCommunityIds())
                .as("only the ACTIVE community survives strict-ACTIVE gating")
                .containsExactly("active-w-0");
    }

    @Test
    void activeCommunityIds_dissolvedCommunity_isExcluded() {
        seedCommunity("live-w-0", CommunityState.ACTIVE);
        seedCommunity("dead-w-0", CommunityState.DISSOLVED);

        assertThat(planner().activeCommunityIds())
                .as("a DISSOLVED community must be excluded from the desired set")
                .containsExactly("live-w-0");
    }

    @Test
    void activeCommunityIds_dissolvingCommunity_isExcluded() {
        seedCommunity("tearing-w-0", CommunityState.DISSOLVING);

        assertThat(planner().activeCommunityIds())
                .as("a DISSOLVING community is in terminal teardown and must be excluded")
                .isEmpty();
    }

    @Test
    void activeCommunityIds_noCommittedCommunities_isEmpty() {
        assertThat(planner().activeCommunityIds())
                .as("with no committed CommunityKey the desired set is empty")
                .isEmpty();
    }

    // --- #590 at the placement grain: a community's self-reported member list FREEZES under partition ---

    private void seedGovernor(String communityId, NodeId governor, List<NodeId> members) {
        kvStore.put(GovernorAnnouncementKey.forCommunity(communityId),
                    GovernorAnnouncementValue.governorAnnouncementValue(governor, members, "tcp://stub"));
    }

    private void livenessAbsent(NodeId... absent) {
        var absentSet = Set.of(absent);
        ((ClusterDeploymentState.Active) harness.state()).ctx().setCommunityLiveness(absentSet::contains);
    }

    @Test
    void buildCommunityWorkerMap_partitionedMembers_arePlacedOnOnlyTheLiveOnes() {
        var governor = new NodeId("w-0");
        var live = new NodeId("w-1");
        var gone = new NodeId("w-2");
        seedCommunity("src-w-0", CommunityState.ACTIVE);
        seedGovernor("src-w-0", governor, List.of(governor, live, gone));
        livenessAbsent(gone);

        assertThat(planner().buildCommunityWorkerMap())
                .as("a member positively observed absent must not be handed work — its announcement entry froze, it did not expire")
                .containsEntry("src-w-0", List.of(governor, live));
    }

    @Test
    void buildCommunityWorkerMap_wholeCommunityAbsent_isOmittedEntirely() {
        var governor = new NodeId("w-0");
        var other = new NodeId("w-1");
        seedCommunity("src-w-0", CommunityState.ACTIVE);
        seedGovernor("src-w-0", governor, List.of(governor, other));
        livenessAbsent(governor, other);

        assertThat(planner().buildCommunityWorkerMap())
                .as("a community with nothing reachable left must be omitted, not placed on unreachable nodes")
                .isEmpty();
    }

    @Test
    void buildCommunityWorkerMap_livenessUnwired_placesExactlyAsBefore() {
        var governor = new NodeId("w-0");
        var other = new NodeId("w-1");
        seedCommunity("src-w-0", CommunityState.ACTIVE);
        seedGovernor("src-w-0", governor, List.of(governor, other));

        assertThat(planner().buildCommunityWorkerMap())
                .as("with no liveness collector wired NOTHING is positively absent, so placement is unchanged")
                .containsEntry("src-w-0", List.of(governor, other));
    }

    // --- test fixtures (mirrors ClusterDeploymentStateActiveTest) ---

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

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            return Promise.success(List.of());
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
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
