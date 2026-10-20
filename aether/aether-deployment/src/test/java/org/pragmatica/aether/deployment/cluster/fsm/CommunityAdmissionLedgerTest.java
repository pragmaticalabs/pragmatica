// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityAdmissionLedgerTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");

    @Test
    void pendingByCommunity_countsInFlightAssignmentsPerCommunity() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        ledger.register(A, "eu-w-0", 1_000L);
        ledger.register(B, "eu-w-0", 1_000L);
        ledger.register(C, "eu-w-1", 1_000L);

        assertThat(ledger.pendingByCommunity(1_001L, _ -> false)).isEqualTo(Map.of("eu-w-0", 2, "eu-w-1", 1));
    }

    @Test
    void pendingByCommunity_dropsAssignmentsWhoseDirectiveIsVisible() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        ledger.register(A, "eu-w-0", 1_000L);
        ledger.register(B, "eu-w-0", 1_000L);

        assertThat(ledger.pendingByCommunity(1_001L, A::equals)).isEqualTo(Map.of("eu-w-0", 1));
        assertThat(ledger.pendingByCommunity(1_002L, _ -> false)).as("the visible one stays dropped").isEqualTo(Map.of("eu-w-0", 1));
    }

    @Test
    void pendingByCommunity_expiresAssignmentsAfterTheTtl() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        ledger.register(A, "eu-w-0", 1_000L);

        assertThat(ledger.pendingByCommunity(1_000L + CommunityAdmissionLedger.PENDING_TTL_MS, _ -> false)).as("at the TTL it still counts").hasSize(1);
        assertThat(ledger.pendingByCommunity(1_001L + CommunityAdmissionLedger.PENDING_TTL_MS, _ -> false)).as("past the TTL it is gone").isEmpty();
    }

    @Test
    void blockedSources_reportOnlyTheTransitions() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        assertThat(ledger.clearBlocked("eu")).as("never refused").isFalse();
        assertThat(ledger.markBlocked("eu")).isTrue();
        assertThat(ledger.markBlocked("eu")).as("already reported").isFalse();
        assertThat(ledger.clearBlocked("eu")).isTrue();
        assertThat(ledger.clearBlocked("eu")).as("cleared once").isFalse();
    }
}
