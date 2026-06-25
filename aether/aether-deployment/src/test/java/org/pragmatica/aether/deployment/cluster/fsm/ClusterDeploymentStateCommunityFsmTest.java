// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
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
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #241 per-community FSM (worker-membership-spec §3.3): the leader, inside `reconcile()`, evaluates
/// each committed community's OBSERVED live membership (= its governor announcement's member count)
/// and drives `CommunityValue.state` on edges against the viability floor (= DHT RF, 3):
/// FORMING → ACTIVE (>=3), ACTIVE → DEGRADED (<3), DEGRADED → ACTIVE (>=3). The evaluation is
/// edge-driven — an unchanged state emits NO `Put` — and leaves the terminal DISSOLVING/DISSOLVED
/// states untouched (Phase C).
///
/// The harness drives the real `Active` state (`Activate` then a direct `reconcile()`) and inspects
/// the commands recorded by the stub cluster, which closes the consensus → KV loop so a re-read
/// observes the committed transition — mirrors `ClusterDeploymentStateCommunityMintTest`.
class ClusterDeploymentStateCommunityFsmTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId GOVERNOR = new NodeId("node-governor");
    private static final int VIABLE = 3;
    private static final int NOT_VIABLE = 2;

    private RecordingClusterNode cluster;
    private InMemoryKvStore kvStore;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF, kvStore);
        Function<NodeId, Option<String>> noSource = nodeId -> Option.none();

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
                                                    noSource).dormant();
        harness = FsmTestHarness.harness("community-fsm-" + SELF.id(), factory);
        harness.dispatch(new Activate());
    }

    private void seedCommunity(String communityId, CommunityState state) {
        kvStore.put(CommunityKey.communityKey(communityId),
                    new CommunityValue("src", "WORKER", 100, state, 1L, Option.none()));
    }

    private void seedAnnouncement(String communityId, int memberCount) {
        kvStore.put(GovernorAnnouncementKey.forCommunity(communityId),
                    GovernorAnnouncementValue.governorAnnouncementValue(GOVERNOR, memberCount));
    }

    private void reconcile() {
        ((ClusterDeploymentState.Active) harness.state()).reconcile();
    }

    private List<CommunityValue> communityPutsFor(String communityId) {
        var key = CommunityKey.communityKey(communityId);

        return cluster.commands.stream()
                               .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                  && put.key().equals(key)
                                                  && put.value() instanceof CommunityValue)
                               .map(command -> (CommunityValue) ((KVCommand.Put<AetherKey, AetherValue>) command).value())
                               .toList();
    }

    @Nested
    class Promotion {
        @Test
        void reconcile_formingCommunityAtFloor_transitionsToActive() {
            seedCommunity("orders-w-0", CommunityState.FORMING);
            seedAnnouncement("orders-w-0", VIABLE);

            reconcile();

            var puts = communityPutsFor("orders-w-0");

            assertThat(puts).as("a FORMING community at the viability floor is promoted to ACTIVE").hasSize(1);
            assertThat(puts.getFirst().state()).isEqualTo(CommunityState.ACTIVE);
        }

        @Test
        void reconcile_promotionPreservesEveryOtherField() {
            seedCommunity("orders-w-0", CommunityState.FORMING);
            seedAnnouncement("orders-w-0", VIABLE);

            reconcile();

            var put = communityPutsFor("orders-w-0").getFirst();

            assertThat(put.sourceName()).as("the transition re-stamps only state").isEqualTo("src");
            assertThat(put.role()).isEqualTo("WORKER");
            assertThat(put.targetSize()).isEqualTo(100);
            assertThat(put.createdAt()).isEqualTo(1L);
        }

        @Test
        void reconcile_degradedCommunityAtFloor_transitionsToActive() {
            seedCommunity("orders-w-0", CommunityState.DEGRADED);
            seedAnnouncement("orders-w-0", VIABLE);

            reconcile();

            var puts = communityPutsFor("orders-w-0");

            assertThat(puts).as("a DEGRADED community that regains the floor is promoted to ACTIVE").hasSize(1);
            assertThat(puts.getFirst().state()).isEqualTo(CommunityState.ACTIVE);
        }
    }

    @Nested
    class Demotion {
        @Test
        void reconcile_activeCommunityBelowFloor_transitionsToDegraded() {
            seedCommunity("orders-w-0", CommunityState.ACTIVE);
            seedAnnouncement("orders-w-0", NOT_VIABLE);

            reconcile();

            var puts = communityPutsFor("orders-w-0");

            assertThat(puts).as("an ACTIVE community below the floor is demoted to DEGRADED").hasSize(1);
            assertThat(puts.getFirst().state()).isEqualTo(CommunityState.DEGRADED);
        }

        @Test
        void reconcile_activeCommunityWithNoAnnouncement_transitionsToDegraded() {
            seedCommunity("orders-w-0", CommunityState.ACTIVE);
            // no governor announcement → observed live members = 0 < floor

            reconcile();

            var puts = communityPutsFor("orders-w-0");

            assertThat(puts).as("an ACTIVE community with no governor (0 live members) is demoted to DEGRADED").hasSize(1);
            assertThat(puts.getFirst().state()).isEqualTo(CommunityState.DEGRADED);
        }
    }

    @Nested
    class EdgeDriven {
        @Test
        void reconcile_activeCommunityAtFloor_emitsNoPut() {
            seedCommunity("orders-w-0", CommunityState.ACTIVE);
            seedAnnouncement("orders-w-0", VIABLE);

            reconcile();

            assertThat(communityPutsFor("orders-w-0"))
                    .as("an already-ACTIVE community at the floor is unchanged — no Put may be emitted")
                    .isEmpty();
        }

        @Test
        void reconcile_formingCommunityBelowFloor_emitsNoPut() {
            seedCommunity("orders-w-0", CommunityState.FORMING);
            seedAnnouncement("orders-w-0", NOT_VIABLE);

            reconcile();

            assertThat(communityPutsFor("orders-w-0"))
                    .as("a FORMING community still below the floor stays FORMING — no Put may be emitted")
                    .isEmpty();
        }
    }

    @Nested
    class TerminalStatesUntouched {
        @Test
        void reconcile_dissolvingCommunity_isLeftUntouched() {
            seedCommunity("orders-w-0", CommunityState.DISSOLVING);
            seedAnnouncement("orders-w-0", VIABLE);

            reconcile();

            assertThat(communityPutsFor("orders-w-0"))
                    .as("a DISSOLVING community is a Phase-C teardown concern and must not be touched")
                    .isEmpty();
        }

        @Test
        void reconcile_dissolvedCommunity_isLeftUntouched() {
            seedCommunity("orders-w-0", CommunityState.DISSOLVED);
            seedAnnouncement("orders-w-0", VIABLE);

            reconcile();

            assertThat(communityPutsFor("orders-w-0"))
                    .as("a DISSOLVED community is terminal and must not be touched")
                    .isEmpty();
        }
    }

    // --- test fixtures (mirrors ClusterDeploymentStateCommunityMintTest) ---

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
        // into the shared KV store, so a subsequent community-state read observes it.
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
