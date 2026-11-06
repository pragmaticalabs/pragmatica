// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceRequirements.Requirement;
import org.pragmatica.lang.Option;

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
}
