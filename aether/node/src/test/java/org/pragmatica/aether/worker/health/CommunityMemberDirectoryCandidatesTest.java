// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Governor candidacy is an explicit committed ROLE plus community (H13, #1840): the directory's candidate
/// index follows committed directive puts, removals and restores, and never admits a core or a spot node.
class CommunityMemberDirectoryCandidatesTest {
    private static final NodeId W1 = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId W2 = NodeId.nodeId("worker-2").unwrap();
    private static final NodeId CORE = NodeId.nodeId("core-1").unwrap();
    private static final NodeId SPOT = NodeId.nodeId("spot-1").unwrap();
    private final CommunityMemberDirectory directory = CommunityMemberDirectory.communityMemberDirectory();

    @Test
    void candidates_areWorkerRoleOnly_coreAndSpotNamingTheCommunityAreExcluded() {
        directory.put(W1, ActivationDirectiveValue.worker("c", ""));
        directory.put(CORE, new ActivationDirectiveValue(ActivationDirectiveValue.CORE, "c", ""));
        directory.put(SPOT, new ActivationDirectiveValue("SPOT", "c", ""));

        assertThat(directory.governorCandidates("c")).containsExactly(W1);
        assertThat(directory.members("c")).as("membership still includes the spot node").contains(W1, SPOT);
    }

    @Test
    void candidates_workerWithoutCommunity_isExcluded() {
        directory.put(W1, ActivationDirectiveValue.worker());

        assertThat(directory.governorCandidates("")).isEmpty();
    }

    @Test
    void candidates_removedDirective_leavesTheIndexAndEmptyCommunityDisappears() {
        directory.put(W1, ActivationDirectiveValue.worker("c", ""));
        directory.put(W2, ActivationDirectiveValue.worker("c", ""));
        directory.remove(W1);

        assertThat(directory.governorCandidates("c")).containsExactly(W2);

        directory.remove(W2);

        assertThat(directory.governorCandidates("c")).isEmpty();
    }

    @Test
    void candidates_roleChangedToCore_leavesTheIndex() {
        directory.put(W1, ActivationDirectiveValue.worker("c", ""));
        directory.put(W1, new ActivationDirectiveValue(ActivationDirectiveValue.CORE, "c", ""));

        assertThat(directory.governorCandidates("c")).isEmpty();
    }

    @Test
    void candidates_restore_replacesTheIndexFromTheSnapshot() {
        directory.put(W1, ActivationDirectiveValue.worker("stale", ""));

        directory.restore(Map.of(new AetherKey.ActivationDirectiveKey(W2), ActivationDirectiveValue.worker("c", ""),
                                 new AetherKey.ActivationDirectiveKey(CORE), ActivationDirectiveValue.core()));

        assertThat(directory.governorCandidates("c")).containsExactly(W2);
        assertThat(directory.governorCandidates("stale")).isEmpty();
    }

    @Test
    void candidates_returnedSetIsASnapshot_laterChangesDoNotMutateIt() {
        directory.put(W1, ActivationDirectiveValue.worker("c", ""));
        var before = directory.governorCandidates("c");

        directory.put(W2, ActivationDirectiveValue.worker("c", ""));

        assertThat(before).containsExactly(W1);
    }
}
