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
    void pendingByCommunity_leavesOutAssignmentsWhoseDirectiveIsVisible() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        ledger.register(A, "eu-w-0", 1_000L);
        ledger.register(B, "eu-w-0", 1_000L);

        assertThat(ledger.pendingByCommunity(1_001L, A::equals)).isEqualTo(Map.of("eu-w-0", 1));
        assertThat(ledger.isRecent(A, 1_001L)).as("a visible assignment is still recent: the liveness view has not seen the worker yet").isTrue();
    }

    @Test
    void pendingByCommunity_stopsCountingAfterTheTtl_butTheAssignmentStaysRecent() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        ledger.register(A, "eu-w-0", 1_000L);

        assertThat(ledger.pendingByCommunity(1_000L + CommunityAdmissionLedger.PENDING_TTL_MS, _ -> false)).as("at the TTL it still counts").hasSize(1);
        assertThat(ledger.pendingByCommunity(1_001L + CommunityAdmissionLedger.PENDING_TTL_MS, _ -> false)).as("past the TTL it is no longer in flight").isEmpty();
        assertThat(ledger.isRecent(A, 1_001L + CommunityAdmissionLedger.PENDING_TTL_MS)).isTrue();
    }

    @Test
    void isRecent_endsWithTheJoinGrace_andForgottenOrUnknownNodesAreNeverRecent() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        ledger.register(A, "eu-w-0", 1_000L);
        ledger.register(B, "eu-w-0", 1_000L);
        ledger.forget(B);

        assertThat(ledger.isRecent(A, 1_000L + CommunityAdmissionLedger.JOIN_GRACE_MS)).as("at the grace it still counts").isTrue();
        assertThat(ledger.isRecent(A, 1_001L + CommunityAdmissionLedger.JOIN_GRACE_MS)).as("past the grace the liveness view decides").isFalse();
        assertThat(ledger.isRecent(B, 1_001L)).as("a forgotten assignment is not recent").isFalse();
        assertThat(ledger.isRecent(C, 1_001L)).as("a node never assigned here is not recent").isFalse();
    }

    @Test
    void pendingByCommunity_dropsAssignmentsOlderThanTheJoinGrace() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        ledger.register(A, "eu-w-0", 1_000L);
        ledger.pendingByCommunity(1_001L + CommunityAdmissionLedger.JOIN_GRACE_MS, _ -> false);

        assertThat(ledger.pending()).as("the ledger does not grow without bound").isEmpty();
    }

    @Test
    void theJoinGraceAndThePendingTtl_arePinnedByLiteral() {
        assertThat(CommunityAdmissionLedger.JOIN_GRACE_MS).isEqualTo(60_000L);
        assertThat(CommunityAdmissionLedger.PENDING_TTL_MS).isEqualTo(30_000L);
        assertThat(CommunityAdmissionLedger.MAX_ATTEMPTS).isEqualTo(3);
    }

    @Test
    void refusals_countInARow_andRestartWhenGivenUpOrPlaced() {
        var ledger = CommunityAdmissionLedger.communityAdmissionLedger();

        assertThat(ledger.refused(A)).isEqualTo(1);
        assertThat(ledger.refused(A)).isEqualTo(2);
        assertThat(ledger.refused(B)).as("per worker").isEqualTo(1);
        ledger.giveUp(A);
        assertThat(ledger.refused(A)).as("a gave-up worker starts counting afresh").isEqualTo(1);
        assertThat(ledger.placed(A)).as("it had been given up on").isTrue();
        assertThat(ledger.refused(A)).as("placing restarts the count").isEqualTo(1);
        assertThat(ledger.placed(A)).as("not reported unplaced any more").isFalse();
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
