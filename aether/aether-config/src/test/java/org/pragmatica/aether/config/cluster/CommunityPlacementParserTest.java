// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityPlacementParserTest {
    private static final String BASE = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "forge"
        zones = ["a", "b"]
        [source.east.core]
        count = 3
        [source.east.worker]
        count = 20
        [source.west]
        type = "forge"
        zones = ["c"]
        [source.west.worker]
        count = 20
        """;
    private static final String POLICY = """
        [community.stable]
        target_size = 20
        [community.stable.placement.first]
        source = "east"
        zone = "a"
        minimum = 3
        weight = 1
        [community.stable.placement.second]
        source = "west"
        zone = "c"
        minimum = 3
        weight = 2
        """;

    @Test
    void parse_multiSourceCommunityPreservesStableIdentityAndLocations() {
        var config = ClusterBootstrapConfigParser.parse(BASE + POLICY).unwrap();
        var policy = config.communities().get("stable");
        assertThat(policy.targetSize()).isEqualTo(20);
        assertThat(policy.desiredCounts().values()).containsExactlyInAnyOrder(8, 12);
        assertThat(policy.desiredCounts().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(policy.locations()).extracting(CommunityPlacement.Location::source).containsExactly("east", "west");
        assertThat(config.withClusterName("renamed").unwrap().communities()).isEqualTo(config.communities());
    }

    @Test
    void parse_unknownSourceOrZoneFails() {
        assertThat(ClusterBootstrapConfigParser.parse(BASE + POLICY.replace("source = \"west\"", "source = \"missing\"")).isFailure()).isTrue();
        assertThat(ClusterBootstrapConfigParser.parse(BASE + POLICY.replace("zone = \"c\"", "zone = \"missing\"")).isFailure()).isTrue();
    }

    @Test
    void parse_impossibleMinimumFails() {
        assertThat(ClusterBootstrapConfigParser.parse(BASE + POLICY.replace("minimum = 3", "minimum = 11")).isFailure()).isTrue();
    }

    @Test
    void diff_policyChangeIsVisibleToApply() {
        var before = ClusterBootstrapConfigParser.parse(BASE + POLICY).unwrap();
        var after = ClusterBootstrapConfigParser.parse(BASE + POLICY.replace("weight = 2", "weight = 3")).unwrap();
        assertThat(ClusterBootstrapConfigDiff.diff(before, after).modifications()).contains(new DiffAction.CommunityPlacementChange());
    }
}
