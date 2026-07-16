// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
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
import org.pragmatica.consensus.topology.MembershipDecision;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #241 configurable community sizing: the leader reads its per-community `targetSize` and
/// `viabilityFloor` from [CommunitySizing] (threaded through [ClusterDeploymentContext]) instead of
/// the former hardcoded constants. This proves the override flows end-to-end:
///   - the minted FORMING [CommunityValue] carries the configured `targetSize`;
///   - the per-community FSM promotes FORMING → ACTIVE at the configured (lower-than-default) floor;
///   - the production default (floor 3) is unchanged — members below 3 stay FORMING.
///
/// The harness drives the real `Active` state and inspects the commands recorded by the stub cluster
/// — mirrors `ClusterDeploymentStateCommunityMintTest` / `ClusterDeploymentStateCommunityFsmTest`.
class ClusterDeploymentStateCommunitySizingTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId GOVERNOR = new NodeId("node-governor");
    private static final NodeId WORKER_1 = new NodeId("node-worker-1");
    private static final String SOURCE_EU = "eu-west";
    private static final String COMMUNITY_EU = SOURCE_EU + "-w-0";
    private static final Map<NodeId, String> SOURCES = Map.of(WORKER_1, SOURCE_EU);
    private static final CommunitySizing SMALL = CommunitySizing.communitySizing(7, 2);

    @Test
    void mint_stampsConfiguredTargetSize() {
        var fixture = fixture(SMALL);

        fixture.joinWorker(WORKER_1);

        var puts = fixture.communityPutsFor(COMMUNITY_EU);

        assertThat(puts).as("the first WORKER of a source mints exactly one FORMING community").hasSize(1);
        assertThat(puts.getFirst().targetSize())
                .as("the minted community carries the configured target size, not the default 100")
                .isEqualTo(7);
    }

    @Test
    void reconcile_promotesFormingCommunityAtOverriddenFloor() {
        var fixture = fixture(SMALL);

        fixture.seedCommunity(COMMUNITY_EU, CommunityState.FORMING);
        fixture.seedAnnouncement(COMMUNITY_EU, 2);

        fixture.reconcile();

        var puts = fixture.communityPutsFor(COMMUNITY_EU);

        assertThat(puts)
                .as("with floor 2 a FORMING community of 2 live members reaches the floor and is promoted")
                .hasSize(1);
        assertThat(puts.getFirst().state()).isEqualTo(CommunityState.ACTIVE);
    }

    @Test
    void reconcile_defaultFloor_doesNotPromoteBelowThree() {
        var fixture = fixture(CommunitySizing.DEFAULT);

        fixture.seedCommunity(COMMUNITY_EU, CommunityState.FORMING);
        fixture.seedAnnouncement(COMMUNITY_EU, 2);

        fixture.reconcile();

        assertThat(fixture.communityPutsFor(COMMUNITY_EU))
                .as("the default floor (3) is unchanged — 2 live members stay FORMING, no Put emitted")
                .isEmpty();
    }

    private static Fixture fixture(CommunitySizing sizing) {
        var router = MessageRouter.mutable();
        var kvStore = new InMemoryKvStore(router);
        var cluster = new RecordingClusterNode(SELF, kvStore);
        Function<NodeId, Option<String>> memberSourceSupplier = nodeId -> Option.option(SOURCES.get(nodeId));
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
                                                    SELF,
                                                    cluster,
                                                    kvStore,
                                                    router,
                                                    stubTopologyManager(SELF),
                                                    stubSchemaOrchestrator(),
                                                    HealthSignalSink.noop(),
                                                    () -> Set.of(SELF),
                                                    () -> Set.of(SELF),
                                                    Set::of,
                                                    Set.of(SELF),
                                                    DeploymentAtomicity.ALL_OR_NOTHING,
                                                    1,
                                                    timeSpan(300).seconds(),
                                                    System::currentTimeMillis,
                                                    memberSourceSupplier,
                                                    sizing).dormant();
        var harness = FsmTestHarness.harness("community-sizing-" + SELF.id(), factory);

        harness.dispatch(new Activate());

        return new Fixture(cluster, kvStore, harness);
    }

    private record Fixture(RecordingClusterNode cluster,
                           InMemoryKvStore kvStore,
                           FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness) {
        void joinWorker(NodeId nodeId) {
            harness.dispatch(new MembershipDecisionReceived(MembershipDecision.nodeJoined(nodeId, List.of(SELF, nodeId))));
        }

        void seedCommunity(String communityId, CommunityState state) {
            kvStore.put(CommunityKey.communityKey(communityId),
                        new CommunityValue(SOURCE_EU, "WORKER", 7, state, 1L, Option.none()));
        }

        void seedAnnouncement(String communityId, int memberCount) {
            kvStore.put(GovernorAnnouncementKey.forCommunity(communityId),
                        GovernorAnnouncementValue.governorAnnouncementValue(GOVERNOR, memberCount));
        }

        void reconcile() {
            ((ClusterDeploymentState.Active) harness.state()).reconcile();
        }

        List<CommunityValue> communityPutsFor(String communityId) {
            var key = CommunityKey.communityKey(communityId);

            return cluster.commands.stream()
                                   .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                      && put.key().equals(key)
                                                      && put.value() instanceof CommunityValue)
                                   .map(command -> (CommunityValue) ((KVCommand.Put<AetherKey, AetherValue>) command).value())
                                   .toList();
        }
    }

    // --- test fixtures (mirrors ClusterDeploymentStateCommunityFsmTest) ---

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
        private final InMemoryKvStore committed;

        RecordingClusterNode(NodeId self, InMemoryKvStore committed) {
            this.self = self;
            this.committed = committed;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        // Mirror the real consensus → KV replication loop: an applied batch is recorded AND committed
        // into the shared KV store, so a subsequent community read observes it.
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            committed.commit(batch);
            return Promise.success(Collections.emptyList());
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }

        void commit(List<KVCommand<AetherKey>> batch) {
            process(createBatch(batch));
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
