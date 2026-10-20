// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #241: the `[community_policy]` table is committed with the cluster config; every rule refuses at apply time.
class CommunityPolicyParserTest {
    private static final String BASE = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "forge"
        zones = ["a"]
        [source.east.core]
        count = 3
        [source.east.worker]
        count = 20
        """;
    private static final String POLICY = """
        [community_policy]
        max_size = 50
        viability_floor = 3
        max_communities = 4
        dissolve_grace_seconds = 60
        growth = true
        """;
    private static final String DECLARED = """
        [community.stable]
        target_size = 20
        [community.stable.placement.first]
        source = "east"
        zone = "a"
        """;

    @Test
    void parse_absentTable_hasNoPolicy() {
        assertThat(ClusterBootstrapConfigParser.parse(BASE).unwrap().communityPolicy().isEmpty()).isTrue();
    }

    @Test
    void parse_fullTable_carriesEveryKnob() {
        var policy = ClusterBootstrapConfigParser.parse(BASE + POLICY).unwrap().communityPolicy().unwrap();

        assertThat(policy).isEqualTo(new CommunityPolicy(50, 3, 4, 60, true));
    }

    @Test
    void parse_emptyTable_takesDefaultsAndGrowthStaysOff() {
        var policy = ClusterBootstrapConfigParser.parse(BASE + "[community_policy]\n").unwrap().communityPolicy().unwrap();

        assertThat(policy.maxSize()).isEqualTo(100);
        assertThat(policy.viabilityFloor()).isEqualTo(3);
        assertThat(policy.maxCommunities()).isEqualTo(CommunityPolicy.DEFAULT_MAX_COMMUNITIES);
        assertThat(policy.growth()).as("growth is off unless the operator turns it on").isFalse();
    }

    @Test
    void parse_floorBelowReplicationFactor_needsExplicitAllowance() {
        assertRefused(POLICY.replace("viability_floor = 3", "viability_floor = 2"), "viability_floor");
        assertThat(ClusterBootstrapConfigParser.parse(BASE + POLICY.replace("viability_floor = 3", "viability_floor = 2\nallow_below_rf = true"))
                                               .isSuccess()).isTrue();
    }

    @Test
    void parse_maxSizeOutsideFloorAndCeiling_isRefused() {
        assertRefused(POLICY.replace("max_size = 50", "max_size = 2"), "max_size");
        assertRefused(POLICY.replace("max_size = 50", "max_size = 201"), "max_size");
    }

    @Test
    void parse_communityBoundOutsideRange_isRefused() {
        assertRefused(POLICY.replace("max_communities = 4", "max_communities = 0"), "max_communities");
        assertRefused(POLICY.replace("max_communities = 4", "max_communities = 101"), "max_communities");
    }

    @Test
    void parse_negativeGrace_isRefused() {
        assertRefused(POLICY.replace("dissolve_grace_seconds = 60", "dissolve_grace_seconds = -1"), "dissolve_grace_seconds");
    }

    @Test
    void parse_unknownKey_isRefusedNamingIt() {
        assertRefused(POLICY + "max_sise = 3\n", "max_sise");
    }

    @Test
    void parse_mistypedValue_isRefusedNamingKeyAndValue() {
        assertRefused(POLICY.replace("growth = true", "growth = \"yes\""), "growth");
    }

    @Test
    void parse_declaredCommunityWithDerivedId_isRefused() {
        assertThat(ClusterBootstrapConfigParser.parse(BASE + DECLARED.replace("stable", "east-w-3")).isFailure()).isTrue();
        assertThat(ClusterBootstrapConfigParser.parse(BASE + DECLARED).isSuccess()).isTrue();
    }

    @Test
    void parse_declaredCommunityAboveCeiling_isRefused() {
        assertThat(ClusterBootstrapConfigParser.parse(BASE + DECLARED.replace("target_size = 20", "target_size = 201")).isFailure()).isTrue();
    }

    @Test
    void diff_policyChangeIsVisibleToApply() {
        var before = ClusterBootstrapConfigParser.parse(BASE + POLICY).unwrap();
        var after = ClusterBootstrapConfigParser.parse(BASE + POLICY.replace("growth = true", "growth = false")).unwrap();

        assertThat(ClusterBootstrapConfigDiff.diff(before, after).modifications()).contains(new DiffAction.CommunityPlacementChange());
        assertThat(ClusterBootstrapConfigDiff.diff(before, before).isEmpty()).isTrue();
    }

    private static void assertRefused(String policy, String mention) {
        ClusterBootstrapConfigParser.parse(BASE + policy)
                                    .onSuccess(config -> org.junit.jupiter.api.Assertions.fail("must refuse, parsed " + config))
                                    .onFailure(cause -> assertThat(cause.message()).contains(mention));
    }
}
