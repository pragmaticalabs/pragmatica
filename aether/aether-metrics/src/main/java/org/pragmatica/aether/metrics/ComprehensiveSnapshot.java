// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.Map;

import org.pragmatica.aether.metrics.consensus.RabiaMetrics;
import org.pragmatica.aether.metrics.eventloop.EventLoopMetrics;
import org.pragmatica.aether.metrics.gc.GCMetrics;


public record ComprehensiveSnapshot(long timestamp,
                                    double cpuUsage,
                                    long heapUsed,
                                    long heapMax,
                                    GCMetrics gc,
                                    EventLoopMetrics eventLoop,
                                    RabiaMetrics consensus,
                                    long totalInvocations,
                                    long successfulInvocations,
                                    long failedInvocations,
                                    double avgLatencyMs,
                                    Map<String, Double> custom) {
    public static final ComprehensiveSnapshot EMPTY = new ComprehensiveSnapshot(0,
                                                                                0.0,
                                                                                0,
                                                                                0,
                                                                                GCMetrics.EMPTY,
                                                                                EventLoopMetrics.EMPTY,
                                                                                RabiaMetrics.EMPTY,
                                                                                0,
                                                                                0,
                                                                                0,
                                                                                0.0,
                                                                                Map.of());

    public double heapUsage() {
        if (heapMax <= 0) {
            return 0.0;
        }

        return (double) heapUsed / heapMax;
    }

    public double successRate() {
        if (totalInvocations <= 0) {
            return 1.0;
        }

        return (double) successfulInvocations / totalInvocations;
    }

    public double errorRate() {
        if (totalInvocations <= 0) {
            return 0.0;
        }

        return (double) failedInvocations / totalInvocations;
    }

    public boolean eventLoopHealthy() {
        return eventLoop.healthy();
    }

    public boolean consensusHealthy() {
        return consensus.hasLeader() && consensus.avgDecisionLatencyMs() < 100.0;
    }

    public boolean healthy() {
        return eventLoopHealthy()
               && consensusHealthy()
               && heapUsage() < 0.9
               && errorRate() < 0.1;
    }
}
