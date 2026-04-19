// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


/// Response from follower to governor with local metrics.
/// ~100 bytes per pong. Contains scaling-relevant metrics only.
///
/// Extended in Commit 3 with `observedCommunityEpoch` — the sender's last-
/// accepted community epoch. Governor aggregates to derive community
/// quiescence (`min(observedCommunityEpoch) ≥ target`).
///
/// @param sender                 follower node ID
/// @param cpuUsage               CPU usage ratio (0.0-1.0)
/// @param heapUsage              heap usage ratio (0.0-1.0)
/// @param activeInvocations      number of in-flight slice invocations
/// @param p95LatencyMs           estimated P95 latency in milliseconds
/// @param errorRate              error rate ratio (0.0-1.0)
/// @param timestampMs            when the pong was created
/// @param observedCommunityEpoch sender's last-accepted community epoch
@Codec public record WorkerMetricsPong(NodeId sender,
                                       double cpuUsage,
                                       double heapUsage,
                                       long activeInvocations,
                                       double p95LatencyMs,
                                       double errorRate,
                                       long timestampMs,
                                       Epoch observedCommunityEpoch) implements Message.Wired {
    public WorkerMetricsPong {
        if (observedCommunityEpoch == null) {observedCommunityEpoch = Epoch.ZERO;}
    }

    public static WorkerMetricsPong workerMetricsPong(NodeId sender,
                                                      double cpuUsage,
                                                      double heapUsage,
                                                      long activeInvocations,
                                                      double p95LatencyMs,
                                                      double errorRate,
                                                      long timestampMs,
                                                      Epoch observedCommunityEpoch) {
        return new WorkerMetricsPong(sender,
                                     cpuUsage,
                                     heapUsage,
                                     activeInvocations,
                                     p95LatencyMs,
                                     errorRate,
                                     timestampMs,
                                     observedCommunityEpoch);
    }

    public static WorkerMetricsPong workerMetricsPong(NodeId sender,
                                                      double cpuUsage,
                                                      double heapUsage,
                                                      long activeInvocations,
                                                      double p95LatencyMs,
                                                      double errorRate,
                                                      long timestampMs) {
        return new WorkerMetricsPong(sender,
                                     cpuUsage,
                                     heapUsage,
                                     activeInvocations,
                                     p95LatencyMs,
                                     errorRate,
                                     timestampMs,
                                     Epoch.ZERO);
    }

    public static WorkerMetricsPong workerMetricsPong(NodeId sender,
                                                      double cpuUsage,
                                                      double heapUsage,
                                                      long activeInvocations,
                                                      double p95LatencyMs,
                                                      double errorRate) {
        return new WorkerMetricsPong(sender,
                                     cpuUsage,
                                     heapUsage,
                                     activeInvocations,
                                     p95LatencyMs,
                                     errorRate,
                                     System.currentTimeMillis(),
                                     Epoch.ZERO);
    }
}
