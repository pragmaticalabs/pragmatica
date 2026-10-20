// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.deployment.cluster.DerivedCommunityPlan.Action;
import org.pragmatica.aether.deployment.cluster.DerivedCommunityPlan.View;
import org.pragmatica.aether.slice.kvstore.CommunityState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.CommunityState.ACTIVE;
import static org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED;
import static org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVING;
import static org.pragmatica.aether.slice.kvstore.CommunityState.FORMING;

/// #241: what the placement pass does to derived communities. The clock is a plain number, so every case is exact.
class DerivedCommunityPlanTest {
    private static final CommunityPolicy GROWTH = new CommunityPolicy(10, 3, 5, 60, true);
    private static final CommunityPolicy NO_GROWTH = new CommunityPolicy(10, 3, 5, 60, false);
    private static final long NOW = 1_000_000L;
    private static final long PAST_GRACE = NOW - 61_000L;
    private static final long WITHIN_GRACE = NOW - 59_000L;

    private static View view(int index, CommunityState state, int targetSize, int members) {
        return View.view("eu-w-" + index, "eu", state, targetSize, members);
    }

    private static List<Action> plan(CommunityPolicy policy, Map<String, Long> emptySince, View... views) {
        return DerivedCommunityPlan.plan(List.of(views), policy, emptySince, NOW);
    }

    @Test
    void plan_targetSizeDiffersFromPolicy_restamps() {
        assertThat(plan(GROWTH, Map.of(), view(0, ACTIVE, 100, 4))).containsExactly(Action.Restamp.restamp("eu-w-0", 10));
    }

    @Test
    void plan_targetSizeAlreadyThePolicys_doesNothing() {
        assertThat(plan(GROWTH, Map.of(), view(0, ACTIVE, 10, 4))).isEmpty();
    }

    @Test
    void plan_restampComesBeforeDissolve() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-1", PAST_GRACE),
                        view(0, ACTIVE, 10, 4),
                        view(1, ACTIVE, 100, 0))).containsExactly(Action.Restamp.restamp("eu-w-1", 10));
    }

    @Test
    void plan_emptyPastGraceWithASibling_beginsDissolve() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-1", PAST_GRACE),
                        view(0, ACTIVE, 10, 4),
                        view(1, ACTIVE, 10, 0))).containsExactly(Action.BeginDissolve.beginDissolve("eu-w-1"));
    }

    @Test
    void plan_emptyWithinGrace_waits() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-1", WITHIN_GRACE),
                        view(0, ACTIVE, 10, 4),
                        view(1, ACTIVE, 10, 0))).isEmpty();
    }

    @Test
    void plan_emptyExactlyAtTheGrace_beginsDissolve() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-1", NOW - 60_000L),
                        view(0, ACTIVE, 10, 4),
                        view(1, ACTIVE, 10, 0))).containsExactly(Action.BeginDissolve.beginDissolve("eu-w-1"));
    }

    @Test
    void plan_theIdleCommunityIsDissolved_whenALaterOneHasMembers() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-0", PAST_GRACE),
                        view(0, ACTIVE, 10, 0),
                        view(1, ACTIVE, 10, 3))).containsExactly(Action.BeginDissolve.beginDissolve("eu-w-0"));
    }

    @Test
    void plan_emptyButNotTimed_waits() {
        assertThat(plan(GROWTH, Map.of(), view(0, ACTIVE, 10, 4), view(1, ACTIVE, 10, 0))).isEmpty();
    }

    @Test
    void plan_withAMember_isNeverDissolved() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-1", PAST_GRACE),
                        view(0, ACTIVE, 10, 4),
                        view(1, ACTIVE, 10, 1))).isEmpty();
    }

    @Test
    void plan_growthOff_neverBeginsDissolve() {
        assertThat(plan(NO_GROWTH,
                        Map.of("eu-w-1", PAST_GRACE),
                        view(0, ACTIVE, 10, 4),
                        view(1, ACTIVE, 10, 0))).isEmpty();
    }

    @Test
    void plan_lastCommunityOfASource_isNeverDissolved() {
        assertThat(plan(GROWTH, Map.of("eu-w-0", PAST_GRACE), view(0, ACTIVE, 10, 0))).isEmpty();
    }

    @Test
    void plan_aSiblingOfAnotherSource_isNotASibling() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-0", PAST_GRACE),
                        view(0, ACTIVE, 10, 0),
                        View.view("us-w-0", "us", ACTIVE, 10, 4))).isEmpty();
    }

    @Test
    void plan_aSiblingBeingDissolved_isNotASibling() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-1", PAST_GRACE),
                        view(0, DISSOLVING, 10, 0),
                        view(1, ACTIVE, 10, 0))).containsExactly(Action.CompleteDissolve.completeDissolve("eu-w-0"));
    }

    @Test
    void plan_twoEmptyCommunities_dissolvesTheNewestOnly() {
        assertThat(plan(GROWTH,
                        Map.of("eu-w-0", PAST_GRACE, "eu-w-1", PAST_GRACE, "eu-w-2", PAST_GRACE),
                        view(0, ACTIVE, 10, 0),
                        view(1, ACTIVE, 10, 0),
                        view(2, FORMING, 10, 0))).containsExactly(Action.BeginDissolve.beginDissolve("eu-w-2"));
    }

    @Test
    void plan_dissolvingAndStillEmpty_completes() {
        assertThat(plan(GROWTH, Map.of(), view(1, DISSOLVING, 10, 0))).containsExactly(Action.CompleteDissolve.completeDissolve("eu-w-1"));
    }

    @Test
    void plan_dissolvingWithAMemberThatRacedTheFirstStep_reopens() {
        assertThat(plan(GROWTH, Map.of(), view(1, DISSOLVING, 10, 1))).containsExactly(Action.Reopen.reopen("eu-w-1"));
    }

    @Test
    void plan_dissolvingIsSettledEvenWithGrowthOff() {
        assertThat(plan(NO_GROWTH, Map.of(), view(1, DISSOLVING, 10, 0))).containsExactly(Action.CompleteDissolve.completeDissolve("eu-w-1"));
    }

    @Test
    void plan_dissolvedAndUnknown_areLeftAlone() {
        assertThat(plan(GROWTH, Map.of(), view(0, DISSOLVED, 0, 0), view(1, CommunityState.UNKNOWN, 3, 0))).isEmpty();
    }

    @Test
    void emptyOpen_listsOnlyOpenCommunitiesWithoutMembers() {
        assertThat(DerivedCommunityPlan.emptyOpen(List.of(view(0, ACTIVE, 10, 0),
                                                          view(1, ACTIVE, 10, 2),
                                                          view(2, DISSOLVING, 10, 0),
                                                          view(3, FORMING, 10, 0)))).containsExactly("eu-w-0", "eu-w-3");
    }
}
