// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceRequirements.Requirement;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVCommand.ReadWitness;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// rc6 §3.1: the combiner's pure evaluation. `target = clamp(max(minInstances, every unexpired requirement), 3, maxInstances,
/// placeableCapacity)`, with the evaluation time and the capacity as inputs (INV-09) and the lower bound of 3 winning (INV-12).
class SliceRequirementsTest {
    private static final long NOW = 1_000_000L;
    private static final int ROOMY = 100;
    private static final Version V1 = Version.version("1.0.0").unwrap();

    private static SliceTargetValue target(int instances, int minInstances, Option<Integer> maxInstances) {
        return SliceTargetValue.sliceTargetValue(V1,
                                                 instances,
                                                 minInstances,
                                                 Option.none(),
                                                 maxInstances,
                                                 Option.none(),
                                                 Option.none());
    }

    private static Requirement requirement(RequirementKind kind, int instances) {
        return new Requirement(kind, SliceRequirementValue.sliceRequirementValue(instances, "test", Option.none(), 0L));
    }

    private static Requirement expiring(RequirementKind kind, int instances, long expiresAt) {
        return new Requirement(kind,
                               SliceRequirementValue.sliceRequirementValue(instances, "test", Option.some(expiresAt), 0L));
    }

    @Test
    void combine_severalRequirements_takesTheMaxNeverTheSum() {
        var combined = SliceRequirements.combine(target(4, 3, Option.none()),
                                                 List.of(requirement(RequirementKind.REACTIVE, 6),
                                                         requirement(RequirementKind.OPERATOR, 5),
                                                         requirement(RequirementKind.SCHEDULED, 4)),
                                                 NOW,
                                                 ROOMY);

        assertThat(combined).isEqualTo(Option.some(6));
    }

    @Test
    void combine_requirementBelowMinInstances_isRaisedToTheFloor() {
        var combined = SliceRequirements.combine(target(7, 7, Option.none()),
                                                 List.of(requirement(RequirementKind.REACTIVE, 4)),
                                                 NOW,
                                                 ROOMY);

        assertThat(combined).isEqualTo(Option.some(7));
    }

    @Test
    void combine_requirementAboveMaxInstances_isClampedToMax() {
        var combined = SliceRequirements.combine(target(5, 3, Option.some(8)),
                                                 List.of(requirement(RequirementKind.OPERATOR, 12)),
                                                 NOW,
                                                 ROOMY);

        assertThat(combined).isEqualTo(Option.some(8));
    }

    @Test
    void combine_requirementAbovePlaceableCapacity_isClampedToCapacity() {
        var combined = SliceRequirements.combine(target(5, 3, Option.some(20)),
                                                 List.of(requirement(RequirementKind.REACTIVE, 12)),
                                                 NOW,
                                                 6);

        assertThat(combined).isEqualTo(Option.some(6));
    }

    @Test
    void combine_upperBoundsBelowThree_floorOfThreeWins() {
        var combined = SliceRequirements.combine(target(3, 1, Option.some(2)),
                                                 List.of(requirement(RequirementKind.REACTIVE, 5)),
                                                 NOW,
                                                 1);

        assertThat(combined).isEqualTo(Option.some(3));
    }

    /// The pure combination excludes an expired claim. It is NOT the committed outcome: the combiner's transaction removes the expired
    /// record and re-bases REACTIVE to the committed count in the same write, so the target holds (v2713 item 2, pinned by
    /// `RequirementCombinerOrderingTest.scheduledRequirementExpires_targetHoldsAtTheExpiry`).
    @Test
    void combine_expiredRequirement_isExcludedFromThePureCombination() {
        var combined = SliceRequirements.combine(target(9, 3, Option.none()),
                                                 List.of(expiring(RequirementKind.SCHEDULED, 9, NOW),
                                                         requirement(RequirementKind.REACTIVE, 4)),
                                                 NOW,
                                                 ROOMY);

        assertThat(combined).isEqualTo(Option.some(4));
    }

    @Test
    void combine_requirementExpiringLater_stillClaims() {
        var combined = SliceRequirements.combine(target(9, 3, Option.none()),
                                                 List.of(expiring(RequirementKind.SCHEDULED, 9, NOW + 1),
                                                         requirement(RequirementKind.REACTIVE, 4)),
                                                 NOW,
                                                 ROOMY);

        assertThat(combined).isEqualTo(Option.some(9));
    }

    @Test
    void combine_noUnexpiredRequirement_writesNothingSoTheCommittedCountStands() {
        var onlyExpired = SliceRequirements.combine(target(9, 3, Option.none()),
                                                    List.of(expiring(RequirementKind.CAMPAIGN, 12, NOW - 1)),
                                                    NOW,
                                                    ROOMY);
        var none = SliceRequirements.combine(target(9, 3, Option.none()), List.of(), NOW, ROOMY);

        assertThat(onlyExpired.isEmpty()).isTrue();
        assertThat(none.isEmpty()).isTrue();
    }

    @Test
    void combine_shuffledInputOrder_givesTheSameResult() {
        var requirements = new ArrayList<>(List.of(requirement(RequirementKind.REACTIVE, 6),
                                                   requirement(RequirementKind.OPERATOR, 11),
                                                   expiring(RequirementKind.SCHEDULED, 14, NOW - 5),
                                                   expiring(RequirementKind.CAMPAIGN, 9, NOW + 50),
                                                   requirement(RequirementKind.PREDICTIVE, 7),
                                                   requirement(RequirementKind.RECOVERY, 3)));
        var target = target(5, 4, Option.some(13));
        var expected = SliceRequirements.combine(target, requirements, NOW, 12);
        var random = new Random(2026_11_05L);

        for (var round = 0; round < 200; round++) {
            Collections.shuffle(requirements, random);

            assertThat(SliceRequirements.combine(target, requirements, NOW, 12)).isEqualTo(expected);
        }

        assertThat(expected).isEqualTo(Option.some(11));
    }

    @Test
    void earliestExpiry_returnsTheSoonestFutureExpiryOnly() {
        var requirements = List.of(expiring(RequirementKind.SCHEDULED, 5, NOW - 10),
                                   expiring(RequirementKind.CAMPAIGN, 5, NOW + 300),
                                   expiring(RequirementKind.PREDICTIVE, 5, NOW + 200),
                                   requirement(RequirementKind.OPERATOR, 5));

        assertThat(SliceRequirements.earliestExpiry(requirements, NOW)).isEqualTo(Option.some(NOW + 200));
        assertThat(SliceRequirements.earliestExpiry(List.of(requirement(RequirementKind.OPERATOR, 5)), NOW)
                                    .isEmpty()).isTrue();
    }

    @Test
    void requirementKey_roundTripsThroughItsStringForm() {
        var base = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();

        for (var kind : RequirementKind.sources()) {
            var key = SliceRequirementKey.sliceRequirementKey(base, kind);

            assertThat(SliceRequirementKey.sliceRequirementKey(key.asString()).unwrap()).isEqualTo(key);
        }

        assertThat(RequirementKind.sources()).doesNotContain(RequirementKind.UNKNOWN);
        assertThat(SliceRequirementKey.sliceRequirementKey("slice-requirement/UNKNOWN/org.example:orders-slice")
                                      .isFailure()).isTrue();
        assertThat(SliceRequirementKey.sliceRequirementKey("slice-requirement/BOGUS/org.example:orders-slice")
                                      .isFailure()).isTrue();
        assertThat(SliceRequirementKey.sliceRequirementKey("slice-target/org.example:orders-slice")
                                      .isFailure()).isTrue();
    }

    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);

    /// A store holding a committed leader, and optionally the target of `BASE` at the given count.
    private static KVStore<AetherKey, AetherValue> storeWithTarget(Option<Integer> targetInstances) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) {return null;}
        });

        put(store, LeaderKey.INSTANCE, new LeaderValue(NodeId.nodeId("leader").unwrap(), 1));
        targetInstances.onPresent(count -> put(store, TARGET_KEY, target(count, 3, Option.none())));

        return store;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(KVStore<AetherKey, AetherValue> store, StructuredKey key, Object value) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put(key, value))));
    }

    private static void putRequirement(KVStore<AetherKey, AetherValue> store, RequirementKind kind, int instances) {
        put(store, SliceRequirementKey.sliceRequirementKey(BASE, kind), requirement(kind, instances).value());
    }

    /// s05: with no `maxInstances` the upper bound is the placeable capacity, not unbounded.
    @Test
    void combine_noMaxInstances_demandAboveCapacityIsClampedToCapacity() {
        assertThat(SliceRequirements.combine(target(5, 3, Option.none()), List.of(requirement(RequirementKind.OPERATOR, 12)), NOW, 6))
            .isEqualTo(Option.some(6));
        assertThat(SliceRequirements.clamp(12, Option.none(), 6)).isEqualTo(6);
    }

    /// s11: the committed requirements come back in kind order, which is the order of the witnesses built from them.
    @Test
    void committedRequirements_areInKindOrder() {
        var store = storeWithTarget(Option.some(5));

        putRequirement(store, RequirementKind.SCHEDULED, 4);
        putRequirement(store, RequirementKind.REACTIVE, 6);
        putRequirement(store, RequirementKind.OPERATOR, 5);

        assertThat(SliceRequirements.committedRequirements(store, BASE).stream().map(Requirement::kind).toList())
            .containsExactly(RequirementKind.REACTIVE, RequirementKind.OPERATOR, RequirementKind.SCHEDULED);
    }

    /// s12: no unexpired requirement leaves the committed count, not zero.
    @Test
    void committedCombination_noRequirements_isTheCommittedCount() {
        assertThat(SliceRequirements.committedCombination(storeWithTarget(Option.some(5)), BASE, NOW, ROOMY).unwrap()).isEqualTo(5);
    }

    /// s13: a combination equal to the committed count writes nothing.
    @Test
    void combinerTransaction_combinationEqualsTheTarget_isSettledNotWritten() {
        var store = storeWithTarget(Option.some(5));

        putRequirement(store, RequirementKind.OPERATOR, 5);

        var built = SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY);

        assertThat(built.isFailure()).as("no transaction for an unchanged count").isTrue();
        built.onFailure(cause -> assertThat(cause).isInstanceOf(SliceRequirements.CombinerOutcome.Settled.class));
    }

    /// Positive control for s13: a combination that moves the count is written, at the target key, to the combined count.
    @Test
    void combinerTransaction_combinationMovesTheTarget_writesTheTargetKey() {
        var store = storeWithTarget(Option.some(5));

        putRequirement(store, RequirementKind.OPERATOR, 8);

        var transaction = SliceRequirements.combinerTransaction(store, BASE, NOW, ROOMY).unwrap();

        assertThat(transaction.mutations().getFirst().key()).isEqualTo(TARGET_KEY);
        assertThat(transaction.mutations().getFirst().replacement()
                              .map(value -> ((SliceTargetValue) value).targetInstances())).isEqualTo(Option.some(8));
    }

    /// s21: a target that is committed is not an orphan, requirements or not.
    @Test
    void orphanRemoval_targetPresentWithRequirements_isNoOrphan() {
        var store = storeWithTarget(Option.some(5));

        putRequirement(store, RequirementKind.OPERATOR, 8);

        assertOrphanless(SliceRequirements.orphanRemovalTransaction(store, BASE));
    }

    /// s21, s22: an absent target with no requirement left has nothing to remove (and builds no empty transaction).
    @Test
    void orphanRemoval_targetAbsentWithoutRequirements_isNoOrphan() {
        assertOrphanless(SliceRequirements.orphanRemovalTransaction(storeWithTarget(Option.none()), BASE));
    }

    /// s23, s25: an absent target with requirements is removed under a witness that the target is still absent.
    @Test
    void orphanRemoval_targetAbsentWithRequirements_removesThemWitnessingTheAbsence() {
        var store = storeWithTarget(Option.none());

        putRequirement(store, RequirementKind.OPERATOR, 8);
        putRequirement(store, RequirementKind.REACTIVE, 4);

        var transaction = SliceRequirements.orphanRemovalTransaction(store, BASE).unwrap();

        assertThat(transaction.guards()).containsExactly(new ReadWitness<AetherKey>(TARGET_KEY, Option.none()));
        assertThat(transaction.mutations().stream().map(KVCommand.Mutation::key).toList())
            .containsExactly(SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.REACTIVE),
                             SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.OPERATOR));
        assertThat(transaction.mutations().stream().allMatch(mutation -> mutation.replacement().isEmpty())).isTrue();
    }

    /// s25: the guarded transaction carries exactly the witnesses and mutations it was given.
    @Test
    void guardedTransaction_carriesTheGuardsItWasGiven() {
        var store = storeWithTarget(Option.some(5));
        var witness = new ReadWitness<AetherKey>(TARGET_KEY, store.get(TARGET_KEY).map(Object.class::cast));
        var removal = new KVCommand.Mutation<AetherKey, AetherValue>(SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.OPERATOR),
                                                                    Option.none(),
                                                                    Option.none());

        LeaderTransaction<AetherKey, AetherValue> transaction = SliceTargetFence.guardedTransaction(store, List.of(witness), List.of(removal)).unwrap();

        assertThat(transaction.guards()).containsExactly(witness);
        assertThat(transaction.mutations()).containsExactly(removal);
    }

    /// s28: only the exact prefix names a requirement key; a same-length prefix with the right shape after it does not.
    @Test
    void requirementKey_wrongPrefixOfTheSameLength_isRejected() {
        assertThat(SliceRequirementKey.sliceRequirementKey("slice-requirenent/REACTIVE/org.example:orders-slice").isFailure()).isTrue();
        assertThat(SliceRequirementKey.sliceRequirementKey("slice-requirement/REACTIVE/org.example:orders-slice").isSuccess()).isTrue();
    }

    /// s31: a requirement claims capacity before its expiry and not at or after it.
    @Test
    void unexpiredAt_claimsUntilTheExpiryInstant() {
        var claim = SliceRequirementValue.sliceRequirementValue(5, "test", Option.some(NOW + 1), 0L);

        assertThat(claim.unexpiredAt(NOW)).as("expires in the future").isTrue();
        assertThat(claim.unexpiredAt(NOW + 1)).as("at the expiry instant").isFalse();
        assertThat(claim.unexpiredAt(NOW + 2)).as("expired in the past").isFalse();
        assertThat(SliceRequirementValue.sliceRequirementValue(5, "test", Option.none(), 0L).unexpiredAt(NOW + 1_000_000)).isTrue();
    }

    private static void assertOrphanless(Result<LeaderTransaction<AetherKey, AetherValue>> built) {
        assertThat(built.isFailure()).isTrue();
        built.onFailure(cause -> assertThat(cause).isInstanceOf(SliceRequirements.CombinerOutcome.NoOrphans.class));
    }
}
