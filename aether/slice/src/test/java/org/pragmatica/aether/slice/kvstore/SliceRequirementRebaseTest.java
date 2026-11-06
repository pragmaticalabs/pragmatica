// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// v2713 round 3 (R3d), wide_slice survivors: the REACTIVE re-base that an expiry or a release writes with it (spec §3.1), and the
/// canary scan that holds a slice. Each test names the mutants it kills.
class SliceRequirementRebaseTest {
    private static final long NOW = 1_000_000L;
    private static final int ROOMY = 100;
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();
    private static final ArtifactBase OTHER = ArtifactBase.artifactBase("org.example:billing-slice").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final SliceRequirementKey REACTIVE_KEY = SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.REACTIVE);
    private static final SliceRequirementKey OPERATOR_KEY = SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.OPERATOR);
    private static final SliceRequirementKey SCHEDULED_KEY = SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.SCHEDULED);

    private static KVStore<AetherKey, AetherValue> storeWithTarget(int committed) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) {return null;}
        });

        put(store, LeaderKey.INSTANCE, new LeaderValue(NodeId.nodeId("leader").unwrap(), 1));
        put(store, TARGET_KEY, SliceTargetValue.sliceTargetValue(V1, committed, Option.<BlueprintId>none()));

        return store;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(KVStore<AetherKey, AetherValue> store, StructuredKey key, Object value) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put(key, value))));
    }

    private static void putRequirement(KVStore<AetherKey, AetherValue> store,
                                       SliceRequirementKey key,
                                       int instances,
                                       Option<Long> expiresAt) {
        put(store, key, SliceRequirementValue.sliceRequirementValue(instances, "test", expiresAt, 0L));
    }

    private static void putTest(KVStore<AetherKey, AetherValue> store, String id, ArtifactBase base, String state) {
        put(store, new AbTestKey(id), AbTestValue.abTestValue(id, base, V1, "{}", state, "{}", 50, 50, "", 0L, 0L));
    }

    private static List<StructuredKey> keys(List<Mutation<AetherKey, AetherValue>> mutations) {
        return mutations.stream().<StructuredKey> map(Mutation::key).toList();
    }

    private static Mutation<AetherKey, AetherValue> mutationAt(List<Mutation<AetherKey, AetherValue>> mutations, AetherKey key) {
        return mutations.stream().filter(mutation -> mutation.key().equals(key)).findFirst().orElseThrow();
    }

    private static int count(Mutation<AetherKey, AetherValue> mutation) {
        return mutation.replacement().map(value -> ((SliceRequirementValue) value).instances()).or(-1);
    }

    /// s15: a REACTIVE exactly at the committed count needs no re-base; one below does, and so does an absent one.
    @Test
    void reactiveRebase_boundaryAtTheCommittedCount() {
        var store = storeWithTarget(5);
        var held = SliceTargetValue.sliceTargetValue(V1, 5, Option.<BlueprintId>none());

        assertThat(SliceRequirements.reactiveRebase(store, BASE, held).isEmpty()).as("absent REACTIVE re-bases").isFalse();

        putRequirement(store, REACTIVE_KEY, 4, Option.none());
        assertThat(SliceRequirements.reactiveRebase(store, BASE, held).isEmpty()).as("REACTIVE one below re-bases").isFalse();

        putRequirement(store, REACTIVE_KEY, 5, Option.none());
        assertThat(SliceRequirements.reactiveRebase(store, BASE, held).isEmpty()).as("REACTIVE at the count: nothing to hold").isTrue();

        putRequirement(store, REACTIVE_KEY, 6, Option.none());
        assertThat(SliceRequirements.reactiveRebase(store, BASE, held).isEmpty()).as("REACTIVE above the count").isTrue();
    }

    /// s17, s19: the re-based value holds the committed count, carries the reason text and the store's committed revision.
    @Test
    void reactiveRebase_valueCarriesCountReasonAndCommittedRevision() {
        var store = storeWithTarget(5);
        var held = SliceTargetValue.sliceTargetValue(V1, 5, Option.<BlueprintId>none());
        var rebase = SliceRequirements.reactiveRebase(store, BASE, held).unwrap();

        assertThat(rebase.instances()).isEqualTo(5);
        assertThat(rebase.reason()).isEqualTo("held at 5: a claim was released or expired");
        assertThat(rebase.expiresAt().isEmpty()).isTrue();
        assertThat(store.committedRevision()).as("control: the store has committed batches").isPositive();
        assertThat(rebase.baseTargetRevision()).isEqualTo(store.committedRevision());
    }

    /// s06b: while the re-base is present, an expired REACTIVE is replaced by the re-base put, not also removed (one mutation on the
    /// key); every other expired kind is removed.
    @Test
    void combinerTransaction_expiredReactiveWithRebase_isReplacedNotRemoved() {
        var store = storeWithTarget(5);

        putRequirement(store, REACTIVE_KEY, 3, Option.some(NOW - 1));
        putRequirement(store, OPERATOR_KEY, 4, Option.some(NOW - 1));

        var mutations = SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY).unwrap().mutations();

        assertThat(keys(mutations)).containsExactlyInAnyOrder(REACTIVE_KEY, OPERATOR_KEY);
        assertThat(count(mutationAt(mutations, REACTIVE_KEY))).as("REACTIVE re-based to the committed count").isEqualTo(5);
        assertThat(mutationAt(mutations, OPERATOR_KEY).replacement().isEmpty()).as("expired OPERATOR is removed").isTrue();
    }

    /// s06b control: with no re-base (REACTIVE already holds the count) the expired REACTIVE is removed like any other kind.
    @Test
    void combinerTransaction_expiredReactiveWithoutRebase_isRemoved() {
        var store = storeWithTarget(5);

        putRequirement(store, REACTIVE_KEY, 5, Option.some(NOW - 1));

        var mutations = SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY).unwrap().mutations();

        assertThat(keys(mutations)).containsExactly(REACTIVE_KEY);
        assertThat(mutations.getFirst().replacement().isEmpty()).as("removed, not re-based").isTrue();
    }

    /// s12: when the combined count moves the target, the expiry removals and the re-base ride in the same transaction.
    @Test
    void combinerTransaction_countMoves_carriesTheExpiryMutationsWithTheTargetWrite() {
        var store = storeWithTarget(5);

        putRequirement(store, OPERATOR_KEY, 8, Option.none());
        putRequirement(store, SCHEDULED_KEY, 4, Option.some(NOW - 1));

        var mutations = SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY).unwrap().mutations();

        assertThat(keys(mutations)).containsExactlyInAnyOrder(TARGET_KEY, SCHEDULED_KEY, REACTIVE_KEY);
        assertThat(mutationAt(mutations, SCHEDULED_KEY).replacement().isEmpty()).isTrue();
        assertThat(count(mutationAt(mutations, REACTIVE_KEY))).isEqualTo(5);
    }

    /// s14: the re-based combination keeps the unexpired non-REACTIVE kinds: a live OPERATOR 8 still raises the target to 8 although
    /// the REACTIVE it replaces expired.
    @Test
    void combinerTransaction_rebaseKeepsLiveNonReactiveRequirementsInTheCombination() {
        var store = storeWithTarget(5);

        putRequirement(store, REACTIVE_KEY, 3, Option.some(NOW - 1));
        putRequirement(store, OPERATOR_KEY, 8, Option.none());

        var mutations = SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY).unwrap().mutations();
        var targetWrite = mutationAt(mutations, TARGET_KEY).replacement().map(value -> ((SliceTargetValue) value).targetInstances());

        assertThat(targetWrite).isEqualTo(Option.some(8));
    }

    /// s26: a replacement equal to the committed claim releases nothing and adds no re-base; a lower one or a removal does.
    @Test
    void requirementMutations_releaseBoundaryAtTheCommittedClaim() {
        var store = storeWithTarget(5);
        var observed = SliceTargetValue.sliceTargetValue(V1, 5, Option.<BlueprintId>none());

        putRequirement(store, OPERATOR_KEY, 5, Option.none());

        var equal = SliceRequirements.requirementMutations(store, observed, OPERATOR_KEY, Option.some(claim(5)));
        var lower = SliceRequirements.requirementMutations(store, observed, OPERATOR_KEY, Option.some(claim(4)));
        var removal = SliceRequirements.requirementMutations(store, observed, OPERATOR_KEY, Option.none());
        var higher = SliceRequirements.requirementMutations(store, observed, OPERATOR_KEY, Option.some(claim(6)));

        assertThat(keys(equal)).as("equal: the put alone").containsExactly(OPERATOR_KEY);
        assertThat(keys(lower)).as("lower releases").containsExactly(OPERATOR_KEY, REACTIVE_KEY);
        assertThat(keys(removal)).as("removal releases").containsExactly(OPERATOR_KEY, REACTIVE_KEY);
        assertThat(keys(higher)).as("higher: the put alone").containsExactly(OPERATOR_KEY);
    }

    private static SliceRequirementValue claim(int instances) {
        return SliceRequirementValue.sliceRequirementValue(instances, "test", Option.none(), 0L);
    }

    /// s22: one canary among non-canary tests of the same base holds the slice wherever the scan meets it (`|=`, not `=`). Eight stores
    /// put the canary under eight ids so the hash-ordered scan meets it in different positions.
    @Test
    void canaryRunning_oneCanaryAmongOtherTests_isFound() {
        for (var state : List.of("DEPLOYING_VARIANTS", "ACTIVE")) {
            for (var i = 0; i < 8; i++) {
                var store = storeWithTarget(5);

                putTest(store, "quiet-a", BASE, "DRAFT");
                putTest(store, "quiet-b", BASE, "DRAFT");
                putTest(store, "quiet-c", BASE, "DRAFT");
                putTest(store, "canary-" + i, BASE, state);
                putTest(store, "quiet-d", BASE, "DRAFT");
                putTest(store, "quiet-e", BASE, "DRAFT");

                assertThat(SliceRequirements.canaryRunning(store, BASE)).as(state + " canary-" + i).isTrue();
            }
        }
    }

    /// s22 controls: tests that are not canaries, or run on another slice, hold nothing; and a held slice refuses the combiner.
    @Test
    void canaryRunning_noCanaryOnTheBase_isFalseAndCombinerIsNotHeld() {
        var store = storeWithTarget(5);

        putTest(store, "quiet", BASE, "DRAFT");
        putTest(store, "elsewhere", OTHER, "ACTIVE");
        putRequirement(store, OPERATOR_KEY, 8, Option.none());

        assertThat(SliceRequirements.canaryRunning(store, BASE)).isFalse();
        assertThat(SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY).isSuccess()).isTrue();

        putTest(store, "canary", BASE, "ACTIVE");

        assertThat(SliceRequirements.canaryRunning(store, BASE)).isTrue();
        SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY)
                         .onSuccessRun(() -> org.junit.jupiter.api.Assertions.fail("held slice must not build a transaction"))
                         .onFailure(cause -> assertThat(cause).isInstanceOf(SliceRequirements.CombinerOutcome.CanaryRunning.class));
    }
}
