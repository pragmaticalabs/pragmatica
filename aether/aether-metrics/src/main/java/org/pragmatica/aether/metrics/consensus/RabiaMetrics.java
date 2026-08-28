// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.consensus;

import org.pragmatica.lang.Option;


public record RabiaMetrics(String role,
                           Option<String> leaderId,
                           int pendingBatches,
                           long decisionsCount,
                           long proposalsCount,
                           long voteRound1Count,
                           long voteRound2Count,
                           long fastPathCount,
                           long syncSuccessCount,
                           long syncFailureCount,
                           long totalDecisionLatencyNs) {
    public static final RabiaMetrics EMPTY = new RabiaMetrics("FOLLOWER", Option.empty(), 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /// The wire-facing counter view (#674): key names double as the Prometheus gauge names, so the
    /// HTTP block, the Prometheus surface and any differencing instrument read the SAME vocabulary.
    /// Monotonic totals except `consensus_pending_batches` (a level) — consumers difference the
    /// totals over their own window, exactly as with the transport counter map.
    public java.util.Map<String, Number> counterMap() {
        return java.util.Map.of("consensus_decisions_total",
                                decisionsCount,
                                "consensus_proposals_total",
                                proposalsCount,
                                "consensus_vote_round1_total",
                                voteRound1Count,
                                "consensus_vote_round2_total",
                                voteRound2Count,
                                "consensus_fast_path_total",
                                fastPathCount,
                                "consensus_sync_success_total",
                                syncSuccessCount,
                                "consensus_sync_failure_total",
                                syncFailureCount,
                                "consensus_pending_batches",
                                pendingBatches);
    }

    public double avgDecisionLatencyMs() {
        if (decisionsCount == 0) {
            return 0.0;
        }

        return (totalDecisionLatencyNs / (double) decisionsCount) / 1_000_000.0;
    }

    public double syncSuccessRate() {
        long total = syncSuccessCount + syncFailureCount;

        if (total == 0) {
            return 1.0;
        }

        return syncSuccessCount / (double) total;
    }

    public boolean isLeader() {
        return "LEADER".equals(role);
    }

    public boolean hasLeader() {
        return leaderId.isPresent();
    }
}
