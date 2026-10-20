// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy.Candidate;
import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy.Decision;
import org.pragmatica.aether.slice.kvstore.CommunityState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.CommunityState.ACTIVE;
import static org.pragmatica.aether.slice.kvstore.CommunityState.DEGRADED;
import static org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED;
import static org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVING;
import static org.pragmatica.aether.slice.kvstore.CommunityState.FORMING;

/// #241: the growth policy for derived communities is a pure choice. Each rule has a case that only that rule can pass.
class CommunityGrowthPolicyTest {
    private static final String SOURCE = "eu";
    private static final CommunityPolicy POLICY = new CommunityPolicy(10, 3, 3, 300, true);

    private static Candidate community(int index, CommunityState state, int load) {
        return Candidate.candidate(SOURCE + "-w-" + index, state, load);
    }

    private static Decision pick(Candidate... communities) {
        return CommunityGrowthPolicy.pick(SOURCE, List.of(communities), POLICY);
    }

    @Test
    void pick_noCommunity_mintsIndexZero() {
        assertThat(pick()).isEqualTo(Decision.Mint.mint("eu-w-0"));
    }

    @Test
    void pick_activeWithRoom_joinsIt() {
        assertThat(pick(community(0, ACTIVE, 4))).isEqualTo(Decision.Join.join("eu-w-0"));
    }

    @Test
    void pick_allAtCap_mintsTheNext() {
        assertThat(pick(community(0, ACTIVE, 10))).isEqualTo(Decision.Mint.mint("eu-w-1"));
    }

    @Test
    void pick_overCap_isNotRoom() {
        assertThat(pick(community(0, ACTIVE, 11))).isEqualTo(Decision.Mint.mint("eu-w-1"));
    }

    @Test
    void pick_formingBelowCap_beatsAnActivePack() {
        assertThat(pick(community(0, ACTIVE, 9), community(1, FORMING, 1))).isEqualTo(Decision.Join.join("eu-w-1"));
    }

    @Test
    void pick_degradedBelowCap_beatsAnActivePack() {
        assertThat(pick(community(0, ACTIVE, 9), community(1, DEGRADED, 2))).isEqualTo(Decision.Join.join("eu-w-1"));
    }

    @Test
    void pick_undersizedAtCap_isSkipped() {
        assertThat(pick(community(0, ACTIVE, 5), community(1, FORMING, 10))).isEqualTo(Decision.Join.join("eu-w-0"));
    }

    @Test
    void pick_severalUndersized_takesTheLeastLoaded() {
        assertThat(pick(community(0, FORMING, 2), community(1, FORMING, 1), community(2, DEGRADED, 2)))
            .isEqualTo(Decision.Join.join("eu-w-1"));
    }

    @Test
    void pick_severalActive_packsTheFullest() {
        assertThat(pick(community(0, ACTIVE, 3), community(1, ACTIVE, 8), community(2, ACTIVE, 5)))
            .isEqualTo(Decision.Join.join("eu-w-1"));
    }

    @Test
    void pick_boundReached_joinsTheLeastLoadedBelowTheCeiling() {
        assertThat(pick(community(0, ACTIVE, 12), community(1, ACTIVE, 10), community(2, ACTIVE, 40)))
            .as("three communities is the bound, all past the cap: the least loaded takes the worker")
            .isEqualTo(Decision.Join.join("eu-w-1"));
    }

    @Test
    void pick_boundReachedAndCeilingReached_refuses() {
        assertThat(pick(community(0, ACTIVE, CommunityPolicy.CEILING),
                        community(1, ACTIVE, CommunityPolicy.CEILING),
                        community(2, ACTIVE, CommunityPolicy.CEILING))).isEqualTo(Decision.Refusal.BOUNDS_REACHED);
    }

    @Test
    void pick_oneBelowTheCeiling_stillTakesTheWorker() {
        assertThat(pick(community(0, ACTIVE, CommunityPolicy.CEILING),
                        community(1, ACTIVE, CommunityPolicy.CEILING),
                        community(2, ACTIVE, CommunityPolicy.CEILING - 1))).isEqualTo(Decision.Join.join("eu-w-2"));
    }

    @Test
    void pick_dissolvedNumbers_areNeverReused() {
        assertThat(pick(community(0, ACTIVE, 10), community(1, DISSOLVED, 0))).isEqualTo(Decision.Mint.mint("eu-w-2"));
    }

    @Test
    void pick_dissolvedAndDissolving_doNotCountTowardTheBound() {
        assertThat(pick(community(0, ACTIVE, 10), community(1, DISSOLVED, 0), community(2, DISSOLVING, 0)))
            .as("one live community of three allowed: a new one is minted past the dead numbers")
            .isEqualTo(Decision.Mint.mint("eu-w-3"));
    }

    @Test
    void pick_dissolvingCommunity_isNeverChosen() {
        assertThat(pick(community(0, DISSOLVING, 0), community(1, ACTIVE, 10))).isEqualTo(Decision.Mint.mint("eu-w-2"));
    }

    @Test
    void pick_otherSourcesCommunities_doNotAdvanceTheNumbering() {
        assertThat(CommunityGrowthPolicy.pick(SOURCE, List.of(Candidate.candidate("us-w-7", ACTIVE, 10)), POLICY))
            .as("a candidate of another source is not ours: numbering starts at zero")
            .isEqualTo(Decision.Mint.mint("eu-w-0"));
    }

    @Test
    void pick_tiesBreakById_whateverTheInputOrder() {
        var communities = new ArrayList<>(List.of(community(0, ACTIVE, 5), community(1, ACTIVE, 5), community(2, ACTIVE, 5)));
        var first = CommunityGrowthPolicy.pick(SOURCE, communities, POLICY);

        for (int seed = 0; seed < 20; seed++) {
            Collections.shuffle(communities, new Random(seed));
            assertThat(CommunityGrowthPolicy.pick(SOURCE, communities, POLICY)).isEqualTo(first);
        }
        assertThat(first).isEqualTo(Decision.Join.join("eu-w-0"));
    }

    @Test
    void derivedIndex_readsOnlyTheSourcesOwnIds() {
        assertThat(CommunityGrowthPolicy.derivedIndex("eu", "eu-w-12").unwrap()).isEqualTo(12);
        assertThat(CommunityGrowthPolicy.derivedIndex("eu", "us-w-12").isEmpty()).isTrue();
        assertThat(CommunityGrowthPolicy.derivedIndex("eu", "eu-w-x").isEmpty()).isTrue();
        assertThat(CommunityGrowthPolicy.derivedIndex("eu", "eu-w-").isEmpty()).isTrue();
        assertThat(CommunityGrowthPolicy.derivedIndex("eu", "eu-w-1-w-2").isEmpty()).isTrue();
    }
}
