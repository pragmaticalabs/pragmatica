// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerMetricsAggregatorTest {

    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.test:slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("handle").unwrap();

    private InvocationMetricsCollector collector;
    private List<CommunityMetricsSnapshot> broadcasts;
    private WorkerMetricsAggregator aggregator;

    @BeforeEach
    void setUp() {
        collector = InvocationMetricsCollector.invocationMetricsCollector();
        broadcasts = new ArrayList<>();
        aggregator = WorkerMetricsAggregator.workerMetricsAggregator(SELF,
                                                                     () -> "community-a",
                                                                     collector,
                                                                     broadcasts::add,
                                                                     5_000L);
    }

    @Test
    void collectOwnMetrics_readsRealPerSliceMetrics() {
        collector.recordSuccess(ARTIFACT, METHOD, 500_000L, 10, 10);

        var metrics = aggregator.collectOwnMetrics();

        assertThat(metrics).hasSize(1);
        assertThat(metrics.getFirst().artifact()).isEqualTo(ARTIFACT);
        assertThat(metrics.getFirst().totalCalls()).isEqualTo(1);
    }

    @Test
    void buildSnapshot_carriesOwnMetricsAsGovernorSelf() {
        collector.recordSuccess(ARTIFACT, METHOD, 500_000L, 10, 10);

        var snapshot = aggregator.buildSnapshot();

        assertThat(snapshot.governorId()).isEqualTo(SELF);
        assertThat(snapshot.communityId()).isEqualTo("community-a");
        assertThat(snapshot.memberCount()).isEqualTo(1);
        assertThat(snapshot.sliceMetrics()).hasSize(1);
        assertThat(snapshot.sliceMetrics().getFirst().artifact()).isEqualTo(ARTIFACT);
    }

    @Test
    void buildSnapshot_withNoInvocations_carriesEmptySliceMetrics() {
        var snapshot = aggregator.buildSnapshot();

        assertThat(snapshot.sliceMetrics()).isEmpty();
    }
}
