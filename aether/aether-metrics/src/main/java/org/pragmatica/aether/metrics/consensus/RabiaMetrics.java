// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
                           long totalDecisionLatencyNs,
                           long oldestPendingBatchAgeMs,
                           long applyTimeoutCount) {
    public static final RabiaMetrics EMPTY = new RabiaMetrics("FOLLOWER",
                                                              Option.empty(),
                                                              0,
                                                              0,
                                                              0,
                                                              0,
                                                              0,
                                                              0,
                                                              0,
                                                              0,
                                                              0,
                                                              0,
                                                              0);

    /// The record as it was before #1117 added the pending-age and apply-timeout signals; both read zero.
    public RabiaMetrics(String role,
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
        this(role,
             leaderId,
             pendingBatches,
             decisionsCount,
             proposalsCount,
             voteRound1Count,
             voteRound2Count,
             fastPathCount,
             syncSuccessCount,
             syncFailureCount,
             totalDecisionLatencyNs,
             0L,
             0L);
    }

    /// The wire-facing counter view (#674): key names double as the Prometheus gauge names, so the
    /// HTTP block, the Prometheus surface and any differencing instrument read the SAME vocabulary.
    /// Monotonic totals except `consensus_pending_batches` and `consensus_oldest_pending_batch_age_ms` (levels) — consumers difference the
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
                                pendingBatches,
                                "consensus_oldest_pending_batch_age_ms",
                                oldestPendingBatchAgeMs,
                                "consensus_apply_timeouts_total",
                                applyTimeoutCount);
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
