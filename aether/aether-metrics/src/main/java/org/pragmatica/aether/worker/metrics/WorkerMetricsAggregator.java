// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.concurrent.CancellableTask;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Per-node per-slice metrics broadcaster (#423). Each node periodically publishes a
/// [CommunityMetricsSnapshot] carrying the real per-artifact invocation metrics read from its own
/// [InvocationMetricsCollector]. The leader ingests these typed snapshots into per-artifact scaling
/// windows — the community-snapshot path is THE feed for autoscaling, replacing the retired
/// cluster-CPU trigger and raw `method.*` gossip-string parsing. This is also the
/// multi-community-ready shape (#367).
public interface WorkerMetricsAggregator {
    Logger LOG = LoggerFactory.getLogger(WorkerMetricsAggregator.class);

    @Contract
    void start();

    @Contract
    void stop();

    List<PerSliceMetrics> collectOwnMetrics();
    CommunityMetricsSnapshot buildSnapshot();

    static WorkerMetricsAggregator workerMetricsAggregator(NodeId self,
                                                           Supplier<String> communityIdSupplier,
                                                           InvocationMetricsCollector invocationMetrics,
                                                           Consumer<CommunityMetricsSnapshot> broadcaster,
                                                           long aggregationIntervalMs) {
        record workerMetricsAggregator(NodeId self,
                                       Supplier<String> communityIdSupplier,
                                       InvocationMetricsCollector invocationMetrics,
                                       Consumer<CommunityMetricsSnapshot> broadcaster,
                                       long aggregationIntervalMs,
                                       CancellableTask task) implements WorkerMetricsAggregator {
            @Override
            @Contract
            public void start() {
                stop();
                task.set(SharedScheduler.scheduleAtFixedRate(this::runCycle,
                                                             TimeSpan.timeSpan(aggregationIntervalMs).millis()));
                LOG.debug("Started per-slice metrics broadcaster for node {}", self.id());
            }

            @Override
            @Contract
            public void stop() {
                task.cancel();
                LOG.debug("Stopped per-slice metrics broadcaster for node {}", self.id());
            }

            @Override
            public List<PerSliceMetrics> collectOwnMetrics() {
                return invocationMetrics.collectPerSliceMetrics();
            }

            @Override
            public CommunityMetricsSnapshot buildSnapshot() {
                return CommunityMetricsSnapshot.communityMetricsSnapshot(communityIdSupplier.get(),
                                                                         self,
                                                                         0L,
                                                                         1,
                                                                         collectOwnMetrics(),
                                                                         List.of());
            }

            @Contract
            private void runCycle() {
                broadcaster.accept(buildSnapshot());
            }
        }

        return new workerMetricsAggregator(self,
                                           communityIdSupplier,
                                           invocationMetrics,
                                           broadcaster,
                                           aggregationIntervalMs,
                                           CancellableTask.cancellableTask());
    }
}
