// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec
public record WorkerMetricsPong(NodeId sender,
                                double cpuUsage,
                                double heapUsage,
                                long activeInvocations,
                                double p95LatencyMs,
                                double errorRate,
                                long timestampMs,
                                Epoch observedCommunityEpoch) implements Message.Wired {
    @Override
    public StreamType streamType() {
        return StreamType.METRICS;
    }

    public WorkerMetricsPong {
        if (observedCommunityEpoch == null) {
            observedCommunityEpoch = Epoch.ZERO;
        }
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
