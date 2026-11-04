// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.consensus;

import java.util.Map;

import org.pragmatica.lang.Option;


public record RabiaMetrics(String role,
                           Option<String> leaderId,
                           int pendingBatches,
                           long decisionsCount,
                           long proposalsCount,
                           long voteRound1Count,
                           long voteRound2Count,
                           long syncSuccessCount,
                           long syncFailureCount,
                           long totalDecisionLatencyNs,
                           long oldestPendingBatchAgeMs,
                           long applyTimeoutCount,
                           long consecutiveEmptySlotsWithPending,
                           Option<String> firstChoiceBatchId) {
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
                                                              0,
                                                              Option.empty());

    /// The record as it was before #1117 added the pending-age and apply-timeout signals; both read zero.
    public RabiaMetrics(String role,
                        Option<String> leaderId,
                        int pendingBatches,
                        long decisionsCount,
                        long proposalsCount,
                        long voteRound1Count,
                        long voteRound2Count,
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
             syncSuccessCount,
             syncFailureCount,
             totalDecisionLatencyNs,
             0L,
             0L,
             0L,
             Option.empty());
    }

    /// The wire-facing counter view (#674): key names double as the Prometheus gauge names, so the
    /// HTTP block, the Prometheus surface and any differencing instrument read the SAME vocabulary.
    /// Monotonic totals except `consensus_pending_batches`, `consensus_oldest_pending_batch_age_ms` and
    /// `consensus_consecutive_empty_slots_with_pending` (levels) — consumers difference the totals over their own window,
    /// exactly as with the transport counter map. `firstChoiceBatchId` is text, so it is served by the HTTP block only.
    public Map<String, Number> counterMap() {
        return Map.ofEntries(Map.entry("consensus_decisions_total", decisionsCount),
                             Map.entry("consensus_proposals_total", proposalsCount),
                             Map.entry("consensus_vote_round1_total", voteRound1Count),
                             Map.entry("consensus_vote_round2_total", voteRound2Count),
                             Map.entry("consensus_sync_success_total", syncSuccessCount),
                             Map.entry("consensus_sync_failure_total", syncFailureCount),
                             Map.entry("consensus_pending_batches", pendingBatches),
                             Map.entry("consensus_oldest_pending_batch_age_ms", oldestPendingBatchAgeMs),
                             Map.entry("consensus_apply_timeouts_total", applyTimeoutCount),
                             Map.entry("consensus_consecutive_empty_slots_with_pending",
                                       consecutiveEmptySlotsWithPending));
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
