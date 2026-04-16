// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.consensus;

import org.pragmatica.lang.Option;


/// Snapshot of Rabia consensus metrics for observability.
///
/// @param role              Current node role ("LEADER" or "FOLLOWER")
/// @param leaderId          Current leader node ID
/// @param pendingBatches    Number of batches awaiting consensus
/// @param decisionsCount    Total decisions made since start
/// @param proposalsCount    Total proposals received since start
/// @param syncSuccessCount  Successful sync attempts
/// @param syncFailureCount  Failed sync attempts
/// @param totalDecisionLatencyNs Cumulative decision latency in nanoseconds
public record RabiaMetrics(String role,
                           Option<String> leaderId,
                           int pendingBatches,
                           long decisionsCount,
                           long proposalsCount,
                           long syncSuccessCount,
                           long syncFailureCount,
                           long totalDecisionLatencyNs) {
    public static final RabiaMetrics EMPTY = new RabiaMetrics("FOLLOWER", Option.empty(), 0, 0, 0, 0, 0, 0);

    public double avgDecisionLatencyMs() {
        if (decisionsCount == 0) {return 0.0;}
        return (totalDecisionLatencyNs / (double) decisionsCount) / 1_000_000.0;
    }

    public double syncSuccessRate() {
        long total = syncSuccessCount + syncFailureCount;
        if (total == 0) {return 1.0;}
        return syncSuccessCount / (double) total;
    }

    public boolean isLeader() {
        return "LEADER".equals(role);
    }

    public boolean hasLeader() {
        return leaderId.isPresent();
    }
}
