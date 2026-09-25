// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.WorkerJoinReceived;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #241 leader-stamp (worker-membership-spec §4.1 / §3.3 / A10): when the leader assigns a WORKER
/// role on the worker join channel it must, in ONE atomic batch, mint a FORMING [`CommunityKey`] for the
/// joining node's source (only the FIRST worker of a source mints it; subsequent workers REUSE the
/// same community id with no second Put — A10 no-renumber) and write a community-assigned WORKER
/// [`ActivationDirectiveKey`] carrying that community id. The CORE path is unchanged: no community
/// Put, a bare CORE directive. An absent/blank source falls back to `"default"` (D2).
///
/// The harness drives the real FSM (`Activate` → `WorkerJoinReceived`) and
/// inspects the commands recorded by the stub cluster — mirrors `ClusterDeploymentStateActiveTest`.
class ClusterDeploymentStateCommunityMintTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId WORKER_1 = new NodeId("node-worker-1");
    private static final NodeId WORKER_2 = new NodeId("node-worker-2");
    private static final NodeId CORE_CANDIDATE = new NodeId("node-core-candidate");
    private static final String SOURCE_EU = "eu-west";
    private static final String EXPECTED_COMMUNITY = SOURCE_EU + "-w-0";

    private RecordingClusterNode cluster;
    private InMemoryKvStore kvStore;
    private final Map<NodeId, String> sources = new ConcurrentHashMap<>();
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF, kvStore);
        Function<NodeId, Option<String>> memberSourceSupplier = nodeId -> Option.option(sources.get(nodeId));

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
                                                    5,
                                                    timeSpan(300).seconds(),
                                                    System::currentTimeMillis,
                                                    memberSourceSupplier).dormant();
        harness = FsmTestHarness.harness("community-mint-" + SELF.id(), factory);
        harness.dispatch(new Activate());
    }

    private void joinWorker(NodeId nodeId, String source) {
        sources.put(nodeId, source);
        dispatchJoin(nodeId);
    }

    private void dispatchJoin(NodeId nodeId) {
        harness.dispatch(new WorkerJoinReceived(WorkerJoinDecision.workerJoinDecision(nodeId, "worker", HlcTimestamp.ZERO)));
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

    private List<ActivationDirectiveValue> directivesFor(NodeId nodeId) {
        var key = ActivationDirectiveKey.activationDirectiveKey(nodeId);

        return cluster.commands.stream()
                               .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                  && put.key().equals(key)
                                                  && put.value() instanceof ActivationDirectiveValue)
                               .map(command -> (ActivationDirectiveValue) ((KVCommand.Put<AetherKey, AetherValue>) command).value())
                               .toList();
    }

    @Test
    void failedAssignment_isRetriedByNextFreshAdmissionWithoutRoleChange() {
        cluster.rejectNext = true;
        joinWorker(WORKER_1, SOURCE_EU);
        assertThat(kvStore.get(ActivationDirectiveKey.activationDirectiveKey(WORKER_1)).isEmpty()).isTrue();
        dispatchJoin(WORKER_1);
        assertThat(kvStore.getTyped(ActivationDirectiveKey.activationDirectiveKey(WORKER_1), ActivationDirectiveValue.class)
            .unwrap().communityId()).isEqualTo(EXPECTED_COMMUNITY);
    }

    @Test
    void concurrentCommunityChange_refusesWholeAssignmentAndRetryPreservesNewState() {
        cluster.beforeCommit = () -> kvStore.put(CommunityKey.communityKey(EXPECTED_COMMUNITY),
            CommunityValue.communityValue(SOURCE_EU, "WORKER", 100).withState(CommunityState.DEGRADED));
        joinWorker(WORKER_1, SOURCE_EU);
        assertThat(kvStore.get(ActivationDirectiveKey.activationDirectiveKey(WORKER_1)).isEmpty()).isTrue();
        dispatchJoin(WORKER_1);
        assertThat(kvStore.get(ActivationDirectiveKey.activationDirectiveKey(WORKER_1)).isPresent()).isTrue();
        assertThat(kvStore.getTyped(CommunityKey.communityKey(EXPECTED_COMMUNITY), CommunityValue.class).unwrap().state()).isEqualTo(CommunityState.DEGRADED);
    }

    @Test
    void explicitCommunity_spansSourcesAndWaitsForObservedZone() {
        var toml = """
            config_version = "1.0.0"
            [cluster]
            name = "test"
            version = "1.0.0"
            [source.east]
            type = "forge"
            zones = ["a"]
            [source.east.worker]
            count = 10
            [source.west]
            type = "forge"
            zones = ["b"]
            [source.west.worker]
            count = 10
            [community.stable]
            target_size = 10
            [community.stable.placement.east]
            source = "east"
            zone = "a"
            [community.stable.placement.west]
            source = "west"
            zone = "b"
            """;
        kvStore.put(AetherKey.ClusterConfigKey.CURRENT,
                    new AetherValue.ClusterConfigValue(toml, "test", "1.0.0", List.of(), 3, 5, "forge", 1, 1));
        joinWorker(WORKER_1, "east");
        assertThat(directivesFor(WORKER_1)).isEmpty();
        kvStore.put(new AetherKey.NodePlacementKey(WORKER_1), new AetherValue.NodePlacementValue("east", Option.some("a"), "instance-1"));
        dispatchJoin(WORKER_1);
        kvStore.put(new AetherKey.NodePlacementKey(WORKER_2), new AetherValue.NodePlacementValue("west", Option.some("b"), "instance-2"));
        joinWorker(WORKER_2, "west");
        assertThat(directivesFor(WORKER_1).getFirst().communityId()).isEqualTo("stable");
        assertThat(directivesFor(WORKER_2).getFirst().communityId()).isEqualTo("stable");
        assertThat(communityPutsFor("stable")).hasSize(1);
    }

    @Test
    void retiredPolicyRejectsLateReservedWorker() {
        var toml = """
            config_version = "1.0.0"
            [cluster]
            name = "test"
            version = "1.0.0"
            [source.east]
            type = "forge"
            [source.east.worker]
            count = 0
            [community.stable]
            target_size = 0
            [community.stable.placement.east]
            source = "east"
            """;
        kvStore.put(AetherKey.ClusterConfigKey.CURRENT,
            new AetherValue.ClusterConfigValue(toml, "test", "1.0.0", List.of(), 3, 5, "forge", 1, 1));
        kvStore.put(new AetherKey.CommunityPlacementOperationKey("stable"),
            new AetherValue.CommunityPlacementOperationValue("late-create", "stable", WORKER_1, "east", Option.none(),
                "binding", Option.none(), "", AetherValue.PlacementOperationPhase.AWAITING_READY,
                new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1), 1, 1, ""));
        kvStore.put(new AetherKey.NodePlacementKey(WORKER_1), new AetherValue.NodePlacementValue("east", Option.none(), "instance"));
        joinWorker(WORKER_1, "east");
        assertThat(directivesFor(WORKER_1)).isEmpty();
    }

    @Nested
    class WorkerCommunityMint {
        @Test
        void workerJoin_withCoreDeficit_mintsCommunityWithoutPromotingWorker() {
            joinWorker(WORKER_1, SOURCE_EU);

            var puts = communityPutsFor(EXPECTED_COMMUNITY);

            assertThat(puts)
                    .as("the first WORKER of a source must mint exactly one FORMING community")
                    .hasSize(1);
            assertThat(puts.getFirst().state())
                    .as("the minted community must be FORMING")
                    .isEqualTo(CommunityState.FORMING);
            assertThat(puts.getFirst().sourceName())
                    .as("the minted community carries the resolved source")
                    .isEqualTo(SOURCE_EU);
            assertThat(puts.getFirst().role()).isEqualTo(ActivationDirectiveValue.WORKER);

            var directives = directivesFor(WORKER_1);

            assertThat(directives).as("a worker directive must be written").hasSize(1);
            assertThat(directives.getFirst().role()).isEqualTo(ActivationDirectiveValue.WORKER);
            assertThat(directives.getFirst().communityId())
                    .as("the WORKER directive carries the minted community id")
                    .isEqualTo(EXPECTED_COMMUNITY);
            assertThat(directives.getFirst().governorHint())
                    .as("a FORMING community has no governor yet")
                    .isEmpty();
        }

        @Test
        void assignNodeRole_mintBatch_isAtomicWithCommunityBeforeDirective() {
            joinWorker(WORKER_1, SOURCE_EU);

            var batch = cluster.lastBatchContaining(ActivationDirectiveKey.activationDirectiveKey(WORKER_1));

            assertThat(batch).hasSize(1);
            assertThat(batch.getFirst()).isInstanceOf(KVCommand.LeaderTransaction.class);
            var transaction = (KVCommand.LeaderTransaction<?, ?>) batch.getFirst();
            assertThat(transaction.mutations()).hasSize(2);
            assertThat(transaction.mutations().getFirst().key()).isEqualTo(CommunityKey.communityKey(EXPECTED_COMMUNITY));
            assertThat(transaction.mutations().getLast().expected().isEmpty()).isTrue();

        }

        @Test
        void assignNodeRole_secondWorkerOfSameSource_reusesCommunityWithoutSecondPut() {
            joinWorker(WORKER_1, SOURCE_EU);
            joinWorker(WORKER_2, SOURCE_EU);

            assertThat(communityPutsFor(EXPECTED_COMMUNITY))
                    .as("A10 no-renumber: the second worker of a source must NOT mint a second community")
                    .hasSize(1);

            var directives = directivesFor(WORKER_2);

            assertThat(directives).as("second worker directive must be written").hasSize(1);
            assertThat(directives.getFirst().communityId())
                    .as("the second worker reuses the same community id")
                    .isEqualTo(EXPECTED_COMMUNITY);
        }

        @Test
        void assignNodeRole_secondWorker_batchIsDirectiveOnly() {
            joinWorker(WORKER_1, SOURCE_EU);
            joinWorker(WORKER_2, SOURCE_EU);

            var batch = cluster.lastBatchContaining(ActivationDirectiveKey.activationDirectiveKey(WORKER_2));

            assertThat(batch)
                    .as("reuse path commits only the directive (community already exists)")
                    .hasSize(1);
        }
    }

    @Nested
    class CoreAssignment {
        @Test
        void coreJoin_atCapacity_preservesCoreRoleAndDoesNotMintCommunity() {
            // A CORE membership event preserves its role even when configured capacity is full.
            var router = MessageRouter.mutable();
            var localKv = new InMemoryKvStore(router);
            var localCluster = new RecordingClusterNode(SELF, localKv);
            Function<NodeId, Option<String>> noSource = nodeId -> Option.none();
            Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                    fsm -> new ClusterDeploymentContext(fsm,
                                                        SELF,
                                                        localCluster,
                                                        localKv,
                                                        router,
                                                        stubTopologyManager(SELF),
                                                        stubSchemaOrchestrator(),
                                                        () -> Set.of(SELF),
                                                        () -> Set.of(SELF),
                                                        Set::of,
                                                        Set.of(SELF),
                                                        DeploymentAtomicity.ALL_OR_NOTHING,
                                                        1,
                                                        timeSpan(300).seconds(),
                                                        System::currentTimeMillis,
                                                        noSource).dormant();
            var localHarness = FsmTestHarness.harness("core-assign-" + SELF.id(), factory);
            localHarness.dispatch(new Activate());

            localHarness.dispatch(new MembershipDecisionReceived(MembershipDecision.nodeJoined(CORE_CANDIDATE,
                                                                                              List.of(SELF, CORE_CANDIDATE))));

            var communityPuts = localCluster.commands.stream()
                                                     .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                                        && put.value() instanceof CommunityValue)
                                                     .toList();
            assertThat(communityPuts)
                    .as("the CORE path must never mint a community")
                    .isEmpty();

            var directiveKey = ActivationDirectiveKey.activationDirectiveKey(CORE_CANDIDATE);
            var coreDirective = localCluster.commands.stream()
                                                     .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                                        && put.key().equals(directiveKey)
                                                                        && put.value() instanceof ActivationDirectiveValue)
                                                     .map(command -> (ActivationDirectiveValue) ((KVCommand.Put<AetherKey, AetherValue>) command).value())
                                                     .toList();
            assertThat(coreDirective).hasSize(1);
            assertThat(coreDirective.getFirst().role()).isEqualTo(ActivationDirectiveValue.CORE);
            assertThat(coreDirective.getFirst().communityId())
                    .as("a CORE directive carries no community id")
                    .isEmpty();
        }
    }

    @Nested
    class SourceFallback {
        @Test
        void assignNodeRole_workerWithAbsentSource_usesDefaultCommunity() {
            dispatchJoin(WORKER_1); // no source registered → none()

            assertThat(communityPutsFor("default-w-0"))
                    .as("an absent source falls back to the 'default' community (D2)")
                    .hasSize(1);

            var directives = directivesFor(WORKER_1);

            assertThat(directives).as("a worker directive must be written").hasSize(1);
            assertThat(directives.getFirst().communityId()).isEqualTo("default-w-0");
        }

        @Test
        void assignNodeRole_workerWithBlankSource_usesDefaultCommunity() {
            joinWorker(WORKER_1, "   ");

            assertThat(communityPutsFor("default-w-0"))
                    .as("a blank source falls back to the 'default' community (D2)")
                    .hasSize(1);
        }
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
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());
        final List<List<KVCommand<AetherKey>>> batches = Collections.synchronizedList(new ArrayList<>());
        private final InMemoryKvStore committed;
        private boolean rejectNext;
        private Runnable beforeCommit = () -> {};

        @SuppressWarnings({"unchecked", "rawtypes"})
        RecordingClusterNode(NodeId self, InMemoryKvStore committed) {
            this.self = self;
            this.committed = committed;
            committed.process(committed.createBatch((List) List.of(new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
                new org.pragmatica.cluster.state.kvstore.LeaderValue(self, 1)))));
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        // Mirror the real consensus → KV replication loop: an applied batch is recorded AND committed
        // into the shared KV store, so a subsequent community-existence read observes it (the reuse /
        // A10 no-renumber path depends on this closed loop).
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            batch.forEach(command -> {
                if (command instanceof KVCommand.LeaderTransaction<?, ?> transaction) {
                    transaction.mutations().forEach(mutation -> mutation.replacement().onPresent(value ->
                        commands.add(new KVCommand.Put<>((AetherKey) mutation.key(), (AetherValue) value))));
                } else { commands.add(command); }
            });
            batches.add(List.copyOf(batch));
            if (rejectNext) { rejectNext = false; return org.pragmatica.lang.utils.Causes.cause("temporary submission failure").promise(); }
            var action = beforeCommit; beforeCommit = () -> {}; action.run();
            committed.commit(batch);
            return Promise.success(Collections.emptyList());
        }

        List<KVCommand<AetherKey>> lastBatchContaining(AetherKey key) {
            return batches.stream()
                          .filter(batch -> batch.stream().anyMatch(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                                              && put.key().equals(key)
                              || command instanceof KVCommand.LeaderTransaction<?, ?> transaction && transaction.mutations().stream().anyMatch(mutation -> mutation.key().equals(key))))
                          .reduce((first, second) -> second)
                          .orElse(List.of());
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
