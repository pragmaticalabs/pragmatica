// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.MembershipDecisionReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
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

/// #241 growth: with `[community_policy] growth = true` a worker joining a source with no declared communities takes the community
/// the growth policy chooses over the source's derived communities, opening a new `<source>-w-N` when they are full. The tests drive
/// the real `Active` state with a recording cluster and read the committed result back from the KV.
class ClusterDeploymentStateCommunityGrowthTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final String SOURCE = "eu-west";
    private static final String FIRST = SOURCE + "-w-0";
    private static final String SECOND = SOURCE + "-w-1";

    private static String config(boolean growth, int maxSize, int maxCommunities) {
        return """
            config_version = "1.0.0"
            [cluster]
            name = "test"
            version = "1.0.0"
            [source.eu-west]
            type = "forge"
            [source.eu-west.worker]
            count = 9
            [community_policy]
            max_size = %d
            viability_floor = 1
            allow_below_rf = true
            max_communities = %d
            growth = %s
            """.formatted(maxSize, maxCommunities, growth);
    }

    @Test
    void join_growthOff_everyWorkerTakesTheFirstCommunity() {
        var fixture = fixture(config(false, 1, 5));

        fixture.joinAll(3);

        assertThat(fixture.communityOf(1)).isEqualTo(FIRST);
        assertThat(fixture.communityOf(2)).isEqualTo(FIRST);
        assertThat(fixture.communityOf(3)).isEqualTo(FIRST);
    }

    @Test
    void join_growthOn_firstWorkerMintsTheFirstCommunity() {
        var fixture = fixture(config(true, 2, 5));

        fixture.joinAll(1);

        assertThat(fixture.communityOf(1)).isEqualTo(FIRST);
        assertThat(fixture.community(FIRST).unwrap().targetSize()).isEqualTo(2);
    }

    @Test
    void join_growthOn_fullCommunityOpensTheNextOne() {
        var fixture = fixture(config(true, 2, 5));

        fixture.joinAll(5);

        assertThat(fixture.communitiesOf(1, 2)).as("the first two fill w-0").containsOnly(FIRST);
        assertThat(fixture.communitiesOf(3, 4)).as("the next two fill the community opened for the third").containsOnly(SECOND);
        assertThat(fixture.communityOf(5)).isEqualTo(SOURCE + "-w-2");
        assertThat(fixture.community(SECOND).unwrap().state()).isEqualTo(CommunityState.FORMING);
    }

    @Test
    void join_growthOn_neverMovesAnExistingMember() {
        var fixture = fixture(config(true, 2, 5));

        fixture.joinAll(3);
        var before = List.of(fixture.communityOf(1), fixture.communityOf(2));
        fixture.joinAll(1);

        assertThat(List.of(fixture.communityOf(1), fixture.communityOf(2))).isEqualTo(before);
    }

    @Test
    void join_growthOn_deadMemberDoesNotHoldASlot() {
        var fixture = fixture(config(true, 2, 5));

        fixture.joinAll(2);
        fixture.passTheJoinGrace();
        fixture.markAbsent(1);
        fixture.joinAll(1);

        assertThat(fixture.communityOf(3)).as("worker 1 is observed absent, so w-0 has room for one more").isEqualTo(FIRST);
    }

    @Test
    void join_growthOn_aFreshWorkerTheLivenessViewHasNotSeenYet_stillHoldsItsSlot() {
        var fixture = fixture(config(true, 1, 5));

        fixture.markEveryoneAbsent();
        fixture.joinAll(2);

        assertThat(fixture.communityOf(2)).as("the first worker's silence is not yet absence: w-0 is full for the second").isEqualTo(SECOND);
    }

    @Test
    void join_growthOn_aJoinerCommittedButNotYetVisible_holdsItsSlot() {
        var fixture = fixture(config(true, 1, 5));

        fixture.holdCommits();
        fixture.joinAll(2);
        fixture.releaseCommits();

        assertThat(fixture.communityOf(2)).as("the first joiner's directive was still in flight: w-0 is full for the second").isEqualTo(SECOND);
    }

    @Test
    void join_growthOn_aPendingJoinerInAVisibleCommunity_isCountedOnce() {
        var fixture = fixture(config(true, 2, 5));

        fixture.seedMembers(FIRST, 1);
        fixture.holdCommits();
        fixture.joinAll(2);
        fixture.releaseCommits();

        assertThat(fixture.communityOf(2)).as("the first joiner took the last slot of w-0, once").isEqualTo(FIRST);
        assertThat(fixture.communityOf(3)).as("w-0 is full, so the second joiner opens w-1").isEqualTo(SECOND);
    }

    @Test
    void join_growthOn_aJoinerWhoseCommitIsRefused_retriesIntoTheCommunityItLostTheRaceFor() {
        var fixture = fixture(config(true, 2, 5));

        fixture.racedBy(() -> fixture.seedCommunity(FIRST));
        fixture.joinAll(1);

        assertThat(fixture.communityOf(1)).as("the leader decided afresh after the refusal, without waiting for the worker to be admitted again").isEqualTo(FIRST);
    }

    @Test
    void join_growthOn_aCommitRefusedEveryTime_endsUnplacedAfterTheBoundedRetries_andIsReportedThenRecovers() {
        var fixture = fixture(config(true, 2, 5));
        var codes = fixture.captureWarnings();

        fixture.seedCommunity(FIRST);
        fixture.alwaysRace(() -> fixture.toggleCommunityState(FIRST));
        fixture.joinAll(1);

        assertThat(fixture.hasDirective(1)).isFalse();
        assertThat(fixture.cluster().applies.get()).as("the first attempt and two retries, no more").isEqualTo(CommunityAdmissionLedger.MAX_ATTEMPTS);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains("community-assignment-unplaced"));
        fixture.alwaysRace(() -> {});
        fixture.rejoin(1);
        assertThat(fixture.communityOf(1)).isEqualTo(FIRST);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains("community-assignment-placed"));
    }

    @Test
    void join_growthOn_aRefusalBecauseAnotherAttemptPlacedTheWorker_closesTheUnplacedReport() {
        var fixture = fixture(config(true, 2, 5));
        var codes = fixture.captureWarnings();

        fixture.seedCommunity(FIRST);
        fixture.alwaysRace(() -> fixture.toggleCommunityState(FIRST));
        fixture.joinAll(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains("community-assignment-unplaced"));
        fixture.alwaysRace(() -> {});
        fixture.racedBy(() -> fixture.seedMembers(FIRST, 1));
        fixture.rejoin(1);

        assertThat(fixture.hasDirective(1)).isTrue();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains("community-assignment-placed"));
    }

    @Test
    void join_growthOn_aWorkerPlacedAtOnce_raisesNoPlacedRecovery() {
        var fixture = fixture(config(true, 2, 5));
        var codes = fixture.captureWarnings();

        fixture.joinAll(1);

        assertThat(fixture.hasDirective(1)).isTrue();
        org.awaitility.Awaitility.await().pollDelay(java.time.Duration.ofMillis(300)).atMost(java.time.Duration.ofSeconds(2)).until(() -> true);
        assertThat(codes).doesNotContain("community-assignment-placed");
    }

    @Test
    void join_growthOn_aPendingMintIsUndersized_andIsFilledBeforeAnActiveCommunityIsPacked() {
        var fixture = fixture(config(true, 3, 5));

        fixture.seedMembers(FIRST, 1);
        fixture.context().communityAdmission().register(new NodeId("in-flight"), SECOND, fixture.context().nowMs());
        fixture.joinAll(1);

        assertThat(fixture.communityOf(2)).as("w-1 is minted by a join in flight and still FORMING: it fills first, though w-0 is as full").isEqualTo(SECOND);
    }

    @Test
    void join_growthOn_aWorkerSilentInsideTheJoinGrace_stillHoldsItsSlot() {
        var fixture = fixture(config(true, 1, 5));

        fixture.joinAll(1);
        fixture.markEveryoneAbsent();
        fixture.passMillis(59_000L);
        fixture.joinAll(1);

        assertThat(fixture.communityOf(2)).as("59 s after its assignment the silent worker is still presumed live, so w-0 is full").isEqualTo(SECOND);
    }

    @Test
    void join_growthOn_aWorkerSilentPastTheJoinGrace_noLongerHoldsItsSlot() {
        var fixture = fixture(config(true, 1, 5));

        fixture.joinAll(1);
        fixture.markEveryoneAbsent();
        fixture.passTheJoinGrace();
        fixture.joinAll(1);

        assertThat(fixture.communityOf(2)).as("61 s after its assignment the silent worker is absent, so w-0 has room").isEqualTo(FIRST);
    }

    @Test
    void join_growthOn_aRetryOfAJoinerStillInFlight_isNotCountedAgainstItself() {
        var fixture = fixture(config(true, 1, 5));

        fixture.holdCommits();
        fixture.joinAll(1);
        fixture.rejoin(1);

        assertThat(fixture.context().communityAdmission().pendingByCommunity(fixture.context().nowMs(), _ -> false))
            .as("the retry chose the same community, not the next one for lack of room it took itself")
            .isEqualTo(Map.of(FIRST, 1));
    }

    @Test
    void join_growthOn_anotherSourcesPendingAssignment_isNotOurCandidate() {
        var fixture = fixture(config(true, 2, 5));

        fixture.context().communityAdmission().register(new NodeId("elsewhere"), "us-east-w-0", fixture.context().nowMs());
        fixture.joinAll(1);

        assertThat(fixture.communityOf(1)).as("a pending join of another source is not ours to take").isEqualTo(FIRST);
    }

    @Test
    void join_growthOn_boundReachedAndCeilingReached_refusesAndWarns() {
        var fixture = fixture(config(true, 2, 1));
        var codes = fixture.captureWarnings();

        fixture.seedMembers(FIRST, CommunityPolicy.CEILING);
        fixture.joinAll(1);

        assertThat(fixture.hasDirective(201)).as("the refused worker has no community").isFalse();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains("community-growth-blocked"));
    }

    @Test
    void join_growthOn_admissionAfterARefusal_raisesTheRecovery() {
        var fixture = fixture(config(true, 2, 1));
        var codes = fixture.captureWarnings();

        fixture.seedMembers(FIRST, CommunityPolicy.CEILING);
        fixture.joinAll(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains("community-growth-blocked"));
        fixture.seedConfig(config(true, 2, 2));
        fixture.joinAll(1);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains("community-growth-resumed"));
        assertThat(fixture.communityOf(202)).as("the bound was raised: a new community takes the worker").isEqualTo(SECOND);
    }

    @Test
    void join_growthOn_noRecoveryForASourceThatWasNeverRefused() {
        var fixture = fixture(config(true, 2, 5));
        var codes = fixture.captureWarnings();

        fixture.joinAll(3);
        fixture.joinAll(1);

        assertThat(codes).doesNotContain("community-growth-resumed");
    }

    private static Fixture fixture(String toml) {
        var router = MessageRouter.mutable();
        var kvStore = new InMemoryKvStore(router);
        var cluster = new RecordingClusterNode(SELF, kvStore);
        var context = new java.util.concurrent.atomic.AtomicReference<ClusterDeploymentContext>();
        var clock = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
            new org.pragmatica.cluster.state.kvstore.LeaderValue(SELF, 1)))));
        kvStore.put(AetherKey.ClusterConfigKey.CURRENT,
                    new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(), 3, 5, "forge", 1, 1));
        Function<NodeId, Option<String>> memberSourceSupplier = nodeId -> Option.some(SOURCE);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> remember(context,
                                new ClusterDeploymentContext(fsm,
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
                                                             timeSpan(300).seconds(),
                                                             clock::get,
                                                             memberSourceSupplier,
                                                             CommunitySizing.DEFAULT)).dormant();
        var harness = FsmTestHarness.harness("community-growth-" + SELF.id(), factory);

        harness.dispatch(new Activate());

        return new Fixture(cluster, kvStore, harness, context.get(), clock);
    }

    private static ClusterDeploymentContext remember(java.util.concurrent.atomic.AtomicReference<ClusterDeploymentContext> holder,
                                                     ClusterDeploymentContext context) {
        holder.set(context);

        return context;
    }

    private static NodeId worker(int number) {
        return new NodeId("worker-" + number);
    }

    private record Fixture(RecordingClusterNode cluster,
                           InMemoryKvStore kvStore,
                           FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness,
                           ClusterDeploymentContext context,
                           java.util.concurrent.atomic.AtomicLong clock) {
        /// Joins the next `count` workers: `worker-1`, `worker-2`, ... in order across calls on this fixture.
        void joinAll(int count) {
            for (int i = 0; i < count; i++) {
                harness.dispatch(new ClusterDeploymentEvents.WorkerJoinReceived(
                    new org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision(worker(nextNumber()), "worker", org.pragmatica.hlc.HlcTimestamp.ZERO)));
            }
        }

        private int nextNumber() {
            return (int) kvStore.joinedCount.incrementAndGet();
        }

        String communityOf(int number) {
            return kvStore.getTyped(ActivationDirectiveKey.activationDirectiveKey(worker(number)), ActivationDirectiveValue.class)
                          .map(ActivationDirectiveValue::communityId)
                          .or("<none>");
        }

        List<String> communitiesOf(int first, int last) {
            return java.util.stream.IntStream.rangeClosed(first, last).mapToObj(this::communityOf).toList();
        }

        boolean hasDirective(int number) {
            return kvStore.get(ActivationDirectiveKey.activationDirectiveKey(worker(number))).isPresent();
        }

        Option<CommunityValue> community(String id) {
            return kvStore.getTyped(CommunityKey.communityKey(id), CommunityValue.class);
        }

        void markEveryoneAbsent() {
            context.setCommunityLiveness(_ -> true);
        }

        /// Time passes for the leader: past the join grace a silent worker is no longer given the benefit of the doubt.
        void passTheJoinGrace() {
            passMillis(61_000L);
        }

        void passMillis(long millis) {
            clock.addAndGet(millis);
        }

        void markAbsent(int number) {
            context.setCommunityLiveness(node -> node.equals(worker(number)));
        }

        /// The next commit lands after `change` ran: a concurrent writer got there first.
        void racedBy(Runnable change) {
            cluster.beforeApply = change;
        }

        /// The next commit fails outright, as when consensus is unreachable.
        void failNext() {
            cluster.failNext = true;
        }

        /// Every commit lands after `change` ran: another writer keeps changing the community.
        void alwaysRace(Runnable change) {
            cluster.beforeEveryApply = change;
        }

        void toggleCommunityState(String id) {
            var current = community(id).unwrap();

            kvStore.put(CommunityKey.communityKey(id),
                        current.withState(current.state() == CommunityState.FORMING ? CommunityState.DEGRADED : CommunityState.FORMING));
        }

        void rejoin(int number) {
            harness.dispatch(new ClusterDeploymentEvents.WorkerJoinReceived(
                new org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision(worker(number), "worker", org.pragmatica.hlc.HlcTimestamp.ZERO)));
        }

        void seedCommunity(String id) {
            kvStore.put(CommunityKey.communityKey(id), new CommunityValue(SOURCE, "WORKER", 2, CommunityState.FORMING, 1L, Option.none()));
        }

        void holdCommits() {
            cluster.hold = true;
        }

        /// The held commits become visible, as consensus would apply them.
        void releaseCommits() {
            cluster.hold = false;
            cluster.held.forEach(batch -> kvStore.process(kvStore.createBatch(batch)));
            cluster.held.clear();
        }

        /// The cluster config is version-fenced: a re-commit carries the next version.
        private long nextConfigVersion() {
            return kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
                          .map(committed -> committed.configVersion() + 1)
                          .or(1L);
        }

        void seedConfig(String toml) {
            kvStore.put(AetherKey.ClusterConfigKey.CURRENT,
                        new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(), 3, 5, "forge", nextConfigVersion(), 1));
        }

        /// `count` committed members of `communityId`, numbered above anything joined so far, in a FORMING community of the source.
        void seedMembers(String communityId, int count) {
            kvStore.put(CommunityKey.communityKey(communityId), new CommunityValue(SOURCE, "WORKER", 2, CommunityState.ACTIVE, 1L, Option.none()));
            for (int i = 1; i <= count; i++) {
                kvStore.put(ActivationDirectiveKey.activationDirectiveKey(worker(i)), ActivationDirectiveValue.worker(communityId, ""));
            }
            kvStore.joinedCount.set(count);
        }

        List<String> captureWarnings() {
            var codes = new java.util.concurrent.CopyOnWriteArrayList<String>();

            context.setOperatorWarningSink(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(warning -> codes.add(warning.code().code())));

            return codes;
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
        final List<List<KVCommand<AetherKey>>> held = Collections.synchronizedList(new ArrayList<>());
        volatile boolean hold;
        volatile Runnable beforeEveryApply = () -> {};
        final java.util.concurrent.atomic.AtomicInteger applies = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean failNext;
        volatile Runnable beforeApply = () -> {};
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
            if (failNext) {
                failNext = false;

                return org.pragmatica.lang.utils.Causes.cause("consensus unavailable").promise();
            }

            applies.incrementAndGet();
            beforeEveryApply.run();
            var change = beforeApply;

            beforeApply = () -> {};
            change.run();
            if (hold) {
                held.add(batch);

                return Promise.success(List.of());
            }
            commands.addAll(batch);
            for (var command : batch) {
                if (command instanceof KVCommand.LeaderTransaction<?, ?> transaction) {
                    transaction.mutations().forEach(mutation -> mutation.replacement().onPresent(value ->
                        commands.add(new KVCommand.Put<>((AetherKey) mutation.key(), value))));
                }
            }
            return Promise.success(committed.process(committed.createBatch(batch)));
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        final java.util.concurrent.atomic.AtomicInteger joinedCount = new java.util.concurrent.atomic.AtomicInteger();

        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            if (value instanceof org.pragmatica.cluster.state.kvstore.LeaderAuthorized) {
                var authority = getTyped(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE, org.pragmatica.cluster.state.kvstore.LeaderValue.class).unwrap();
                process(createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key, java.util.UUID.randomUUID().toString(), authority, List.of(),
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
