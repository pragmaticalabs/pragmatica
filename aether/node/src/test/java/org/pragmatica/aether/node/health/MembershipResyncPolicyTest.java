// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipResyncPolicyTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final long INTERVAL = MembershipResyncPolicy.MIN_INTERVAL_NANOS;

    private final MembershipResyncPolicy policy = MembershipResyncPolicy.membershipResyncPolicy();

    @Test
    void permits_nothingMissing_neverAsks() {
        assertThat(policy.permits(Set.of(), 0L)).isFalse();
    }

    @Test
    void permits_firstSightOfAMissingVoter_asksAtOnce() {
        assertThat(policy.permits(Set.of(A), 0L)).isTrue();
    }

    @Test
    void permits_insideTheMinimumInterval_isRefused() {
        policy.permits(Set.of(A), 0L);

        assertThat(policy.permits(Set.of(A), INTERVAL - 1)).isFalse();
        assertThat(policy.permits(Set.of(A), INTERVAL)).isTrue();
    }

    @Test
    void permits_aChangedMissingSet_startsOverWithoutWaiting() {
        policy.permits(Set.of(A), 0L);

        assertThat(policy.permits(Set.of(A, B), 1L)).as("a new missing voter is asked about immediately").isTrue();
    }

    @Test
    void permits_aVoterThatNeverAppears_isBoundedPerView() {
        var asked = 0;

        for (var round = 0; round < MembershipResyncPolicy.MAX_REQUESTS_PER_VIEW * 3; round++) {
            asked += policy.permits(Set.of(A), round * INTERVAL) ? 1 : 0;
        }

        assertThat(asked).as("a dead voter costs a bounded burst, not a permanent drip")
                         .isEqualTo(MembershipResyncPolicy.MAX_REQUESTS_PER_VIEW);
    }

    @Test
    void permits_afterTheViewCompletes_aLaterGapIsAskedAgain() {
        for (var round = 0; round < MembershipResyncPolicy.MAX_REQUESTS_PER_VIEW; round++) {
            policy.permits(Set.of(A), round * INTERVAL);
        }
        policy.permits(Set.of(), 0L);

        assertThat(policy.permits(Set.of(A), 0L)).as("the same voter missing again after a complete view").isTrue();
    }
}
