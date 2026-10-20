// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler.ParkedClearRefusal;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #241: a placement operation parked in DRAIN_UNCERTAIN or BLOCKED raises `community-placement-parked`; leaving the parked phase by itself raises
/// `community-placement-resumed`; the operator's clear closes it with `community-placement-cleared` and touches neither node.
class CommunityPlacementParkedTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId OLD = new NodeId("old-worker");
    private static final NodeId NEW = new NodeId("placement-op-1");
    private static final String COMMUNITY = "stable";
    private static final String CONFIG = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.pool]
        type = "forge"
        zones = ["old", "new"]
        [source.pool.core]
        count = 3
        [source.pool.worker]
        count = 1
        [community.stable]
        target_size = 1
        [community.stable.placement.destination]
        source = "pool"
        zone = "new"
        """;
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AtomicBoolean leading = new AtomicBoolean(true);
    private final CopyOnWriteArrayList<String> codes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
    private final List<CommunityPlacementOperationValue> escalations = new CopyOnWriteArrayList<>();
    private Runnable beforeCommit = () -> {};
    private Runnable beforeEveryCommit = () -> {};

    private volatile String binding = "binding";
    private final CommunityPlacementReconciler.Actuator actuator = new CommunityPlacementReconciler.Actuator() {
        @Override public org.pragmatica.lang.Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source) {
            return org.pragmatica.lang.Result.success(binding);
        }
        @Override public Promise<Unit> create(CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Boolean> retirementSafe(CommunityPlacementOperationValue operation) { return Promise.success(true); }
        @Override public Promise<Unit> drain(CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Unit> terminate(CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Boolean> previousInstanceExists(CommunityPlacementOperationValue operation) { return Promise.success(true); }
    };

    private final CommunityPlacementReconciler reconciler = reconciler();

    private CommunityPlacementReconciler reconciler() {
        var created = CommunityPlacementReconciler.communityPlacementReconciler(CORE,
                                                                                store,
                                                                                commands -> {
                                                                                    var hook = beforeCommit;

                                                                                    beforeCommit = () -> {};
                                                                                    beforeEveryCommit.run();
                                                                                    hook.run();

                                                                                    return Promise.success(store.process(store.createBatch(commands)));
                                                                                },
                                                                                leading::get,
                                                                                _ -> true,
                                                                                () -> Set.of(CORE, OLD),
                                                                                () -> 100,
                                                                                actuator,
                                                                                escalations::add,
                                                                                (_, _) -> {},
                                                                                org.pragmatica.lang.io.TimeSpan.timeSpan(60_000).millis(),
                                                                                now::get);

        created.operatorWarnings(OperatorWarningSink.handingOffTo(warning -> {
            codes.add(warning.code().code());
            messages.add(warning.message());
        }));

        return created;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private LeaderValue leader() {
        return new LeaderValue(CORE, 1);
    }

    private void start() {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, leader()));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 new AetherValue.ClusterConfigValue(Option.some(CONFIG), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(OLD), ActivationDirectiveValue.worker(COMMUNITY, "")));
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(OLD), new AetherValue.NodePlacementValue("pool", Option.some("old"), "old-instance")));
    }

    private CommunityPlacementOperationValue operation(PlacementOperationPhase phase, long phaseChangedAt) {
        return new CommunityPlacementOperationValue("op-1",
                                                    COMMUNITY,
                                                    NEW,
                                                    "pool",
                                                    Option.some("new"),
                                                    "binding",
                                                    Option.some(OLD),
                                                    "pool",
                                                    phase,
                                                    leader(),
                                                    1L,
                                                    phaseChangedAt,
                                                    "");
    }

    /// The operation record is leader-authorized: it is written as the leader writes it, replacing whatever is there.
    private void seedOperation(PlacementOperationPhase phase, long phaseChangedAt) {
        var key = new AetherKey.CommunityPlacementOperationKey(COMMUNITY);
        var value = operation(phase, phaseChangedAt);

        seed(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                      java.util.UUID.randomUUID().toString(),
                                                                      leader(),
                                                                      List.of(),
                                                                      List.of(new KVCommand.Mutation<>(key, store.get(key), Option.some(value)))));
    }

    /// Another writer moves the operation on just before a commit that would park it: only a commit made while the drain is
    /// still requested is raced, so the park attempt itself is the one refused.
    private void moveOnWhileDraining() {
        if (current().phase() == PlacementOperationPhase.DRAIN_REQUESTED) {
            seedOperation(PlacementOperationPhase.DRAINED, now.get());
        }
    }

    private CommunityPlacementOperationValue current() {
        return store.getTyped(new AetherKey.CommunityPlacementOperationKey(COMMUNITY), CommunityPlacementOperationValue.class).unwrap();
    }

    /// A negative assertion about an asynchronous sink needs the sink to have had its chance.
    private static void settle() {
        await().pollDelay(java.time.Duration.ofMillis(300)).atMost(java.time.Duration.ofSeconds(2)).until(() -> true);
    }

    private void awaitCode(String code) {
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> codes.contains(code));
    }

    @Test
    void reconcile_aDrainThatWasNeverAcknowledged_parksTheOperationAndRaisesTheWarning() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_REQUESTED, now.get() - 120_000L);

        reconciler.reconcile().await().unwrap();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_UNCERTAIN);
        awaitCode("community-placement-parked");
        assertThat(messages.getFirst()).contains("stable").contains("DRAIN_UNCERTAIN").contains("old-worker").contains("aether cluster clear-placement stable");
        assertThat(escalations).as("the existing escalation event still fires").hasSize(1);
    }

    @Test
    void reconcile_aParkRefusedBecauseTheOperationChangedFirst_raisesNeitherTheWarningNorTheEscalation() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_REQUESTED, now.get() - 120_000L);
        beforeEveryCommit = this::moveOnWhileDraining;

        reconciler.reconcile().await().unwrap();
        settle();

        assertThat(current().phase()).as("the park was refused: the operation the other writer moved on was not overwritten").isNotEqualTo(PlacementOperationPhase.DRAIN_UNCERTAIN);
        assertThat(codes).as("a park that did not happen is not reported").doesNotContain("community-placement-parked");
        assertThat(escalations).as("nor escalated").isEmpty();
    }

    @Test
    void reconcile_aConflictBlocksACreate_parksTheOperationWithTheAdviceForABlockedOne() {
        start();
        seedOperation(PlacementOperationPhase.RESERVED, now.get());
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(NEW), new AetherValue.NodePlacementValue("pool", Option.some("old"), "elsewhere")));

        reconciler.reconcile().await().unwrap();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.BLOCKED);
        awaitCode("community-placement-parked");
        assertThat(messages.getFirst()).contains("BLOCKED").contains("resumes by itself when its target node is observed").contains("clear-placement stable");
    }

    @Test
    void reconcile_aParkedOperationThatStaysParkedWithANewReason_raisesNoRecovery() {
        start();
        seedOperation(PlacementOperationPhase.BLOCKED, now.get());
        binding = "another-binding";

        reconciler.reconcile().await().unwrap();
        awaitCode("community-placement-parked");
        settle();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.BLOCKED);
        assertThat(current().detail()).contains("Source identity changed");
        assertThat(codes).as("BLOCKED to BLOCKED is not a recovery").doesNotContain("community-placement-resumed");
    }

    @Test
    void reconcile_aLateDrainAcknowledgement_resumesTheOperationAndRaisesTheRecovery() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_UNCERTAIN, now.get());

        assertThat(reconciler.onDrainCompleted(OLD, "op-1").await().unwrap()).isTrue();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAINED);
        awaitCode("community-placement-resumed");
    }

    @Test
    void reconcile_aTransitionTheStoreRefuses_raisesNoRecovery() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_UNCERTAIN, now.get());
        beforeCommit = () -> seedOperation(PlacementOperationPhase.DRAIN_UNCERTAIN, now.get() + 1);

        assertThat(reconciler.onDrainCompleted(OLD, "op-1").await().unwrap()).as("the write lost the race").isFalse();
        settle();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_UNCERTAIN);
        assertThat(codes).as("the operation did not leave the parked phase").doesNotContain("community-placement-resumed");
    }

    @Test
    void reconcile_anOperationThatLeavesAnOrdinaryPhase_raisesNoRecovery() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_REQUESTED, now.get());

        assertThat(reconciler.onDrainCompleted(OLD, "op-1").await().unwrap()).isTrue();
        reconciler.reconcile().await().unwrap();
        settle();

        assertThat(codes).doesNotContain("community-placement-resumed");
    }

    @Test
    void clear_aDrainUncertainOperation_closesItAndLeavesBothNodesAlone() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_UNCERTAIN, now.get());

        var cleared = reconciler.clearParked(COMMUNITY, "alice", "old node is drained by hand").await().unwrap();

        assertThat(cleared.phase()).isEqualTo(PlacementOperationPhase.DRAIN_UNCERTAIN);
        assertThat(cleared.previousNode()).isEqualTo(Option.some(OLD));
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
        assertThat(current().detail()).contains("alice").contains("old node is drained by hand");
        assertThat(store.get(new AetherKey.ActivationDirectiveKey(OLD)).isPresent()).as("the previous worker's directive stays").isTrue();
        assertThat(store.get(new AetherKey.NodePlacementKey(OLD)).isPresent()).as("and its placement").isTrue();
        awaitCode("community-placement-cleared");
        assertThat(messages.getLast()).contains("alice").contains("old node is drained by hand").contains("old-worker");
    }

    @Test
    void clear_aBlockedOperationWhoseTargetWasNeverObserved_closesIt() {
        start();
        seedOperation(PlacementOperationPhase.BLOCKED, now.get());

        reconciler.clearParked(COMMUNITY, "alice", "target never came").await().unwrap();

        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.COMPLETE);
    }

    @Test
    void clear_aBlockedOperationWhoseTargetIsObserved_isRefusedBecauseItResumesByItself() {
        start();
        seedOperation(PlacementOperationPhase.BLOCKED, now.get());
        seed(new KVCommand.Put<>(new AetherKey.NodePlacementKey(NEW), new AetherValue.NodePlacementValue("pool", Option.some("new"), "new-instance")));

        var refused = reconciler.clearParked(COMMUNITY, "alice", "checked").await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(ParkedClearRefusal.NotParked.class));
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.BLOCKED);
    }

    @Test
    void clear_anOperationInAnOrdinaryPhase_isRefused() {
        start();
        seedOperation(PlacementOperationPhase.AWAITING_READY, now.get());

        var refused = reconciler.clearParked(COMMUNITY, "alice", "checked").await();

        refused.onFailure(cause -> assertThat(cause).isEqualTo(new ParkedClearRefusal.NotParked(COMMUNITY, PlacementOperationPhase.AWAITING_READY)));
        assertThat(refused.isFailure()).isTrue();
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.AWAITING_READY);
    }

    @Test
    void clear_aCommunityWithoutAnOperation_isRefused() {
        start();

        var refused = reconciler.clearParked("nobody", "alice", "checked").await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(new ParkedClearRefusal.NoOperation("nobody")));
    }

    @Test
    void clear_onANodeThatIsNotTheActiveLeader_isRefusedAndChangesNothing() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_UNCERTAIN, now.get());
        leading.set(false);

        var refused = reconciler.clearParked(COMMUNITY, "alice", "checked").await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(ParkedClearRefusal.NotActive.class));
        assertThat(current().phase()).isEqualTo(PlacementOperationPhase.DRAIN_UNCERTAIN);
    }

    @Test
    void clear_whenTheOperationChangesUnderIt_isRefusedAsChanged() {
        start();
        seedOperation(PlacementOperationPhase.DRAIN_UNCERTAIN, now.get());
        beforeCommit = () -> seedOperation(PlacementOperationPhase.DRAINED, now.get());

        var refused = reconciler.clearParked(COMMUNITY, "alice", "checked").await();

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(new ParkedClearRefusal.Changed(COMMUNITY)));
        assertThat(current().phase()).as("the operation that moved on is not overwritten").isEqualTo(PlacementOperationPhase.DRAINED);
        settle();
        assertThat(codes).doesNotContain("community-placement-cleared");
    }
}
