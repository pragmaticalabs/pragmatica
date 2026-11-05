// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.Origin;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector.Settlement;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.worker.metrics.ArrivalCounts;
import org.pragmatica.aether.worker.metrics.PerSliceMetrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Rc6 f3a: arrival counters per (artifact, method), external and internal kept apart, carried into the
/// per-slice and per-method metrics.
class InvocationMetricsCollectorArrivalTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.test:slice-a:1.0.0").unwrap();
    private static final MethodName FAST = MethodName.methodName("fast").unwrap();

    private final InvocationMetricsCollector collector = InvocationMetricsCollector.invocationMetricsCollector();

    @Test
    void settlements_areBucketedAndAcceptedIsCompletedPlusFailed() {
        offer(Origin.EXTERNAL, Settlement.COMPLETED);
        offer(Origin.EXTERNAL, Settlement.COMPLETED);
        offer(Origin.EXTERNAL, Settlement.FAILED);
        offer(Origin.EXTERNAL, Settlement.REJECTED);
        assertThat(collector.arrivalCounts(ARTIFACT, "fast", Origin.EXTERNAL)).isEqualTo(new ArrivalCounts(4, 3, 1, 2, 1));
    }

    @Test
    void origins_areCountedSeparately() {
        offer(Origin.EXTERNAL, Settlement.COMPLETED);
        collector.recordArrival(ARTIFACT, "fast", Origin.INTERNAL);
        assertThat(collector.arrivalCounts(ARTIFACT, "fast", Origin.EXTERNAL)).isEqualTo(new ArrivalCounts(1, 1, 0, 1, 0));
        assertThat(collector.arrivalCounts(ARTIFACT, "fast", Origin.INTERNAL)).isEqualTo(new ArrivalCounts(1, 0, 0, 0, 0));
    }

    @Test
    void unseenMethod_isZero() {
        assertThat(collector.arrivalCounts(ARTIFACT, "none", Origin.EXTERNAL)).isEqualTo(ArrivalCounts.ZERO);
    }

    @Test
    void perSliceMetrics_carryCountsOfMethodThatHasGaugesAndOneThatOnlyHasArrivals() {
        collector.recordSuccess(ARTIFACT, FAST, 500_000L, 10, 10);
        offer(Origin.EXTERNAL, Settlement.COMPLETED);
        collector.recordArrival(ARTIFACT, "refused", Origin.EXTERNAL);
        collector.recordSettled(ARTIFACT, "refused", Origin.EXTERNAL, Settlement.REJECTED);
        collector.recordArrival(ARTIFACT, "refused", Origin.INTERNAL);
        var slice = collector.collectPerSliceMetrics().getFirst();

        assertThat(slice.external()).isEqualTo(new ArrivalCounts(2, 1, 1, 1, 0));
        assertThat(slice.internal()).isEqualTo(new ArrivalCounts(1, 0, 0, 0, 0));
        assertThat(slice.methods()).hasSize(2);
        assertThat(slice.methods().stream().filter(m -> m.method()
                                                         .equals("refused")).toList().getFirst().external()).isEqualTo(new ArrivalCounts(1,
                                                                                                                                         0,
                                                                                                                                         1,
                                                                                                                                         0,
                                                                                                                                         0));
    }

    @Test
    void artifactWithArrivalsOnlyAndNoGauges_appearsInPerSliceMetrics() {
        var ghost = Artifact.artifact("org.test:ghost:1.0.0").unwrap();

        collector.recordArrival(ghost, "gone", Origin.INTERNAL);
        collector.recordSettled(ghost, "gone", Origin.INTERNAL, Settlement.REJECTED);
        var slices = collector.collectPerSliceMetrics();

        assertThat(slices).hasSize(1);
        assertThat(slices.getFirst().artifact()).isEqualTo(ghost);
        assertThat(slices.getFirst().internal()).isEqualTo(new ArrivalCounts(1, 0, 1, 0, 0));
    }

    @Test
    void perSliceMetrics_sumAllFiveCountsAcrossMethods() {
        collector.recordSuccess(ARTIFACT, FAST, 500_000L, 10, 10);
        offer(Origin.EXTERNAL, Settlement.COMPLETED);
        offer(Origin.EXTERNAL, Settlement.FAILED);
        offer(Origin.EXTERNAL, Settlement.FAILED);
        collector.recordArrival(ARTIFACT, "other", Origin.EXTERNAL);
        collector.recordSettled(ARTIFACT, "other", Origin.EXTERNAL, Settlement.FAILED);
        collector.recordArrival(ARTIFACT, "other", Origin.EXTERNAL);
        collector.recordSettled(ARTIFACT, "other", Origin.EXTERNAL, Settlement.REJECTED);
        collector.recordArrival(ARTIFACT, "other", Origin.EXTERNAL);
        var slice = collector.collectPerSliceMetrics().getFirst();

        assertThat(slice.external()).isEqualTo(new ArrivalCounts(6, 4, 1, 1, 3));
    }

    @Test
    void perSliceMetrics_nullArrivalCounts_normaliseToZero() {
        var slice = new PerSliceMetrics(ARTIFACT, 0, 0.0, 0.0, 0, null, null, null);

        assertThat(slice.external()).isEqualTo(ArrivalCounts.ZERO);
        assertThat(slice.internal()).isEqualTo(ArrivalCounts.ZERO);
        assertThat(slice.methods()).isEmpty();
    }

    @Test
    void countsSurviveTheSnapshotResetOfTheGauges() {
        collector.recordSuccess(ARTIFACT, FAST, 500_000L, 10, 10);
        offer(Origin.EXTERNAL, Settlement.COMPLETED);
        collector.snapshotAndReset();
        assertThat(collector.arrivalCounts(ARTIFACT, "fast", Origin.EXTERNAL)).isEqualTo(new ArrivalCounts(1, 1, 0, 1, 0));
    }

    private void offer(Origin origin, Settlement settlement) {
        collector.recordArrival(ARTIFACT, "fast", origin);
        collector.recordSettled(ARTIFACT, "fast", origin, settlement);
    }
}
