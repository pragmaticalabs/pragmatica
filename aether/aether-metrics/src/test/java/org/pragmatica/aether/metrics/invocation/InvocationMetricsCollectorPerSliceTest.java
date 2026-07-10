// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.invocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.worker.metrics.PerSliceMetrics;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationMetricsCollectorPerSliceTest {

    private static final Artifact ARTIFACT_A = Artifact.artifact("org.test:slice-a:1.0.0").unwrap();
    private static final Artifact ARTIFACT_B = Artifact.artifact("org.test:slice-b:1.0.0").unwrap();
    private static final MethodName FAST = MethodName.methodName("fast").unwrap();
    private static final MethodName SLOW = MethodName.methodName("slow").unwrap();

    private InvocationMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = InvocationMetricsCollector.invocationMetricsCollector();
    }

    @Test
    void collectPerSliceMetrics_groupsByArtifact_oneEntryPerArtifact() {
        collector.recordSuccess(ARTIFACT_A, FAST, 500_000L, 10, 10);
        collector.recordSuccess(ARTIFACT_B, FAST, 500_000L, 10, 10);

        var byArtifact = index(collector.collectPerSliceMetrics());

        assertThat(byArtifact).containsOnlyKeys(ARTIFACT_A, ARTIFACT_B);
    }

    @Test
    void collectPerSliceMetrics_sumsCallsAcrossMethods() {
        collector.recordSuccess(ARTIFACT_A, FAST, 500_000L, 10, 10);
        collector.recordSuccess(ARTIFACT_A, FAST, 500_000L, 10, 10);
        collector.recordSuccess(ARTIFACT_A, SLOW, 500_000_000L, 10, 10);

        var metrics = index(collector.collectPerSliceMetrics()).get(ARTIFACT_A);

        assertThat(metrics.totalCalls()).isEqualTo(3);
        assertThat(metrics.methods()).hasSize(2);
    }

    @Test
    void collectPerSliceMetrics_reflectsActiveInvocationsGauge() {
        collector.recordStart(ARTIFACT_A, FAST);
        collector.recordStart(ARTIFACT_A, FAST);
        collector.recordComplete(ARTIFACT_A, FAST);

        var metrics = index(collector.collectPerSliceMetrics()).get(ARTIFACT_A);

        assertThat(metrics.activeInvocations()).isEqualTo(1);
    }

    @Test
    void collectPerSliceMetrics_worstMethodDrivesArtifactP95() {
        collector.recordSuccess(ARTIFACT_A, FAST, 500_000L, 10, 10);
        collector.recordSuccess(ARTIFACT_A, SLOW, 500_000_000L, 10, 10);

        var metrics = index(collector.collectPerSliceMetrics()).get(ARTIFACT_A);
        var worstMethodP95 = metrics.methods().stream().mapToDouble(m -> m.p95LatencyMs()).max().orElse(0.0);

        assertThat(metrics.p95LatencyMs()).isEqualTo(worstMethodP95);
        assertThat(metrics.p95LatencyMs()).isGreaterThan(0.0);
    }

    @Test
    void collectPerSliceMetrics_worstMethodDrivesArtifactErrorRate() {
        collector.recordSuccess(ARTIFACT_A, FAST, 500_000L, 10, 10);
        collector.recordFailure(ARTIFACT_A, SLOW, 500_000L, 10, "boom");

        var metrics = index(collector.collectPerSliceMetrics()).get(ARTIFACT_A);

        assertThat(metrics.errorRate()).isEqualTo(1.0);
    }

    @Test
    void collectPerSliceMetrics_carriesPerMethodBreakdown() {
        collector.recordSuccess(ARTIFACT_A, FAST, 500_000L, 10, 10);
        collector.recordSuccess(ARTIFACT_A, SLOW, 500_000_000L, 10, 10);

        var metrics = index(collector.collectPerSliceMetrics()).get(ARTIFACT_A);
        var methodNames = metrics.methods().stream().map(m -> m.method()).collect(Collectors.toSet());

        assertThat(methodNames).containsExactlyInAnyOrder("fast", "slow");
    }

    private static Map<Artifact, PerSliceMetrics> index(List<PerSliceMetrics> metrics) {
        return metrics.stream().collect(Collectors.toMap(PerSliceMetrics::artifact, Function.identity()));
    }
}
