// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
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

import static org.assertj.core.api.Assertions.assertThat;

/// #241: the placement pass follows the committed `[community_policy]` for DERIVED communities only. It re-stamps their target size
/// and, with growth on, dissolves an empty one in two committed steps after the grace. It never touches a declared community.
class CommunityPlacementDerivedTest {
    private static final NodeId CORE = new NodeId("core");
    private static final long GRACE_MS = 60_000L;
    private static final String HEAD = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.eu]
        type = "forge"
        [source.eu.worker]
        count = 4
        """;
    private static final String POLICY = """
        [community_policy]
        max_size = 10
        viability_floor = 3
        max_communities = 5
        dissolve_grace_seconds = 60
        growth = true
        """;
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private final CommunityPlacementReconciler.Actuator actuator = new CommunityPlacementReconciler.Actuator() {
        @Override public org.pragmatica.lang.Result<String> sourceBinding(org.pragmatica.aether.environment.SourceName source) {
            return org.pragmatica.lang.Result.success("binding");
        }
        @Override public Promise<Unit> create(AetherValue.CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Boolean> retirementSafe(AetherValue.CommunityPlacementOperationValue operation) { return Promise.success(true); }
        @Override public Promise<Unit> drain(AetherValue.CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Unit> terminate(AetherValue.CommunityPlacementOperationValue operation) { return Promise.unitPromise(); }
        @Override public Promise<Boolean> previousInstanceExists(AetherValue.CommunityPlacementOperationValue operation) { return Promise.success(false); }
    };

    private CommunityPlacementReconciler reconciler() {
        return reconciler(commands -> Promise.success(store.process(store.createBatch(commands))));
    }

    private CommunityPlacementReconciler reconciler(java.util.function.Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply) {
        return CommunityPlacementReconciler.communityPlacementReconciler(CORE,
                                                                         store,
                                                                         apply,
                                                                         () -> true,
                                                                         _ -> true,
                                                                         () -> Set.of(CORE),
                                                                         () -> 100,
                                                                         actuator,
                                                                         _ -> {},
                                                                         (_, _) -> {},
                                                                         org.pragmatica.lang.io.TimeSpan.timeSpan(60_000).millis(),
                                                                         now::get);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void seed(KVCommand command) { store.process(store.createBatch(List.of(command))); }

    private void start(String toml) {
        seed(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)));
        seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                 new AetherValue.ClusterConfigValue(Option.some(toml), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
    }

    private void community(String id, CommunityState state, int targetSize, int members) {
        seed(new KVCommand.Put<>(new AetherKey.CommunityKey(id), new CommunityValue("eu", "WORKER", targetSize, state, 1L, Option.none())));
        for (int i = 0; i < members; i++) {
            seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(new NodeId(id + "-m" + i)), ActivationDirectiveValue.worker(id, "")));
        }
    }

    private CommunityValue value(String id) {
        return store.getTyped(new AetherKey.CommunityKey(id), CommunityValue.class).unwrap();
    }

    private void pass(CommunityPlacementReconciler reconciler) {
        reconciler.reconcile().await().unwrap();
    }

    @Test
    void reconcile_derivedTargetSizeDiffersFromPolicy_isRestamped() {
        start(HEAD + POLICY);
        community("eu-w-0", CommunityState.ACTIVE, 100, 4);

        pass(reconciler());

        assertThat(value("eu-w-0").targetSize()).isEqualTo(10);
    }

    @Test
    void reconcile_emptyDerivedCommunity_isDissolvedInTwoStepsAfterTheGrace() {
        start(HEAD + POLICY);
        community("eu-w-0", CommunityState.ACTIVE, 10, 4);
        community("eu-w-1", CommunityState.ACTIVE, 10, 0);
        var reconciler = reconciler();

        pass(reconciler);
        assertThat(value("eu-w-1").state()).as("first seen empty: the grace starts").isEqualTo(CommunityState.ACTIVE);
        now.addAndGet(GRACE_MS - 1_000L);
        pass(reconciler);
        assertThat(value("eu-w-1").state()).as("inside the grace").isEqualTo(CommunityState.ACTIVE);
        now.addAndGet(2_000L);
        pass(reconciler);
        assertThat(value("eu-w-1").state()).as("step one").isEqualTo(CommunityState.DISSOLVING);
        pass(reconciler);
        assertThat(value("eu-w-1").state()).as("step two").isEqualTo(CommunityState.DISSOLVED);
        assertThat(value("eu-w-1").targetSize()).isZero();
        assertThat(value("eu-w-1").dissolvedAt().isPresent()).isTrue();
        assertThat(value("eu-w-0").state()).as("the populated community is never touched").isEqualTo(CommunityState.ACTIVE);
    }

    @Test
    void reconcile_aMemberAppearsBeforeTheGraceEnds_restartsTheGrace() {
        start(HEAD + POLICY);
        community("eu-w-0", CommunityState.ACTIVE, 10, 4);
        community("eu-w-1", CommunityState.ACTIVE, 10, 0);
        var reconciler = reconciler();

        pass(reconciler);
        now.addAndGet(GRACE_MS - 1_000L);
        seed(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(new NodeId("late")), ActivationDirectiveValue.worker("eu-w-1", "")));
        pass(reconciler);
        store.process(store.createBatch(List.of(new KVCommand.Remove<AetherKey>(new AetherKey.ActivationDirectiveKey(new NodeId("late"))))));
        now.addAndGet(5_000L);
        pass(reconciler);

        assertThat(value("eu-w-1").state()).as("emptiness was interrupted, so it is timed afresh").isEqualTo(CommunityState.ACTIVE);
    }

    @Test
    void reconcile_dissolvingCommunityThatGainedAMember_isReopened() {
        start(HEAD + POLICY);
        community("eu-w-0", CommunityState.ACTIVE, 10, 4);
        community("eu-w-1", CommunityState.DISSOLVING, 10, 1);

        pass(reconciler());

        assertThat(value("eu-w-1").state()).isEqualTo(CommunityState.FORMING);
    }

    @Test
    void reconcile_lastCommunityOfTheSource_isNeverDissolved() {
        start(HEAD + POLICY);
        community("eu-w-0", CommunityState.ACTIVE, 10, 0);
        var reconciler = reconciler();

        pass(reconciler);
        now.addAndGet(GRACE_MS * 2);
        pass(reconciler);
        pass(reconciler);

        assertThat(value("eu-w-0").state()).isEqualTo(CommunityState.ACTIVE);
    }

    @Test
    void reconcile_newLeaderInstance_restartsTheGrace() {
        start(HEAD + POLICY);
        community("eu-w-0", CommunityState.ACTIVE, 10, 4);
        community("eu-w-1", CommunityState.ACTIVE, 10, 0);

        pass(reconciler());
        now.addAndGet(GRACE_MS * 2);
        pass(reconciler());

        assertThat(value("eu-w-1").state()).as("a fresh leader has not seen it empty for the grace yet").isEqualTo(CommunityState.ACTIVE);
    }

    @Test
    void reconcile_aFailedWrite_doesNotStopTheNextCommunity() {
        start(HEAD + POLICY);
        community("eu-w-0", CommunityState.ACTIVE, 100, 4);
        community("eu-w-1", CommunityState.ACTIVE, 100, 4);
        var failures = new java.util.concurrent.atomic.AtomicInteger(1);

        pass(reconciler(commands -> failures.getAndDecrement() > 0
                                    ? org.pragmatica.lang.utils.Causes.cause("consensus unavailable").<List<Object>> promise()
                                    : Promise.success(store.process(store.createBatch(commands)))));

        assertThat(value("eu-w-0").targetSize()).as("the first write failed and was dropped").isEqualTo(100);
        assertThat(value("eu-w-1").targetSize()).as("the next community was still restamped").isEqualTo(10);
    }

    @Test
    void reconcile_growthOff_restampsButNeverDissolves() {
        start(HEAD + POLICY.replace("growth = true", "growth = false"));
        community("eu-w-0", CommunityState.ACTIVE, 10, 4);
        community("eu-w-1", CommunityState.ACTIVE, 100, 0);
        var reconciler = reconciler();

        pass(reconciler);
        now.addAndGet(GRACE_MS * 2);
        pass(reconciler);
        pass(reconciler);

        assertThat(value("eu-w-1").targetSize()).isEqualTo(10);
        assertThat(value("eu-w-1").state()).isEqualTo(CommunityState.ACTIVE);
    }

    @Test
    void reconcile_noCommittedPolicy_leavesDerivedCommunitiesAlone() {
        start(HEAD);
        community("eu-w-0", CommunityState.ACTIVE, 100, 4);
        community("eu-w-1", CommunityState.ACTIVE, 100, 0);
        var reconciler = reconciler();

        pass(reconciler);
        now.addAndGet(GRACE_MS * 2);
        pass(reconciler);

        assertThat(value("eu-w-0").targetSize()).isEqualTo(100);
        assertThat(value("eu-w-1").state()).isEqualTo(CommunityState.ACTIVE);
    }

    @Test
    void reconcile_declaredCommunitiesPresent_derivedCommunitiesAreLeftAlone() {
        start(HEAD + POLICY + """
            [community.stable]
            target_size = 4
            [community.stable.placement.here]
            source = "eu"
            """);
        community("eu-w-0", CommunityState.ACTIVE, 100, 4);

        pass(reconciler());

        assertThat(value("eu-w-0").targetSize()).as("a cluster with declared communities is not in derived mode").isEqualTo(100);
    }
}
