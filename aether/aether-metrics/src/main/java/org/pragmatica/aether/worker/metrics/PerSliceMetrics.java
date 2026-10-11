// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.serialization.Codec;


@Codec
public record PerSliceMetrics(Artifact artifact,
                              long activeInvocations,
                              double p95LatencyMs,
                              double errorRate,
                              long totalCalls,
                              List<PerMethodMetrics> methods) {
    public PerSliceMetrics {
        methods = methods == null
                  ? List.of()
                  : List.copyOf(methods);
    }

    public static PerSliceMetrics perSliceMetrics(Artifact artifact,
                                                  long activeInvocations,
                                                  double p95LatencyMs,
                                                  double errorRate,
                                                  long totalCalls,
                                                  List<PerMethodMetrics> methods) {
        return new PerSliceMetrics(artifact, activeInvocations, p95LatencyMs, errorRate, totalCalls, methods);
    }

    public static PerSliceMetrics perSliceMetrics(Artifact artifact,
                                                  long activeInvocations,
                                                  double p95LatencyMs,
                                                  double errorRate,
                                                  long totalCalls) {
        return new PerSliceMetrics(artifact, activeInvocations, p95LatencyMs, errorRate, totalCalls, List.of());
    }
}
