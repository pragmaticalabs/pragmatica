// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.CommunityRetirementIndex;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import static org.assertj.core.api.Assertions.assertThat;

class RetirementIndexRestoreTest {
    @Test
    void restoredSnapshotMayContainFrameworkValuesAlongsidePlacementOperations() {
        var leader = new LeaderValue(new NodeId("core"), 1);
        var oldWorker = new NodeId("old-worker");
        var operation = new AetherValue.CommunityPlacementOperationValue("operation", "community",
            new NodeId("replacement"), "east", Option.none(), "binding", Option.some(oldWorker), "west",
            AetherValue.PlacementOperationPhase.DRAIN_REQUESTED, leader, 0, 0, "");
        Map<?, ?> snapshot = Map.of(LeaderKey.INSTANCE, leader,
            new AetherKey.CommunityPlacementOperationKey("community"), operation);
        var index = CommunityRetirementIndex.communityRetirementIndex();
        AetherNode.restoreRetirementIndex(snapshot, index);
        assertThat(index.including(Set.of())).containsExactly(oldWorker);
    }
}
