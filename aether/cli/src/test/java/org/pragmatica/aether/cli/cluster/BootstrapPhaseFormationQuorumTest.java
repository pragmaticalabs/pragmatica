// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/// #295 — split-brain fence at formation. A multi-node cluster must not declare quorum off a single
/// node's self-view. `healthMeetsFloor` requires the leader-reported `/api/health` to show
/// `quorum:true` AND a member count at or above the strict-majority floor; a lone node
/// (`nodeCount:1`) against a 3-core expectation (floor 2) must fail.
class BootstrapPhaseFormationQuorumTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private static JsonNode health(int nodeCount, boolean quorum) {
        var json = "{\"status\":\"healthy\",\"ready\":true,\"quorum\":" + quorum
                 + ",\"nodeCount\":" + nodeCount + ",\"connectedPeers\":" + (nodeCount - 1) + "}";

        return MAPPER.readTree(json).unwrap();
    }

    @Test
    void healthMeetsFloor_singleNodeAgainstThreeCoreFloor_isRejected() {
        // 3-core cluster -> floor 2. A lone node (nodeCount 1) must NOT satisfy quorum, even if it
        // optimistically reports quorum:true for itself — this is the split-brain case.
        assertThat(BootstrapPhaseFormation.healthMeetsFloor(health(1, true), 2)).isFalse();
    }

    @Test
    void healthMeetsFloor_majorityPresentAndQuorate_isAccepted() {
        assertThat(BootstrapPhaseFormation.healthMeetsFloor(health(2, true), 2)).isTrue();
        assertThat(BootstrapPhaseFormation.healthMeetsFloor(health(3, true), 2)).isTrue();
    }

    @Test
    void healthMeetsFloor_majorityPresentButNotQuorate_isRejected() {
        // Member count meets the floor but the leader has not declared quorum yet — not ready.
        assertThat(BootstrapPhaseFormation.healthMeetsFloor(health(2, false), 2)).isFalse();
    }

    @Test
    void healthMeetsFloor_belowFloor_isRejected() {
        // 5-core cluster -> floor 3. Two nodes present is below the majority.
        assertThat(BootstrapPhaseFormation.healthMeetsFloor(health(2, true), 3)).isFalse();
    }
}
