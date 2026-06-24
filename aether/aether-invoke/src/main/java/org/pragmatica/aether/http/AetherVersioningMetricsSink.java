// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.pragmatica.aether.metrics.observability.AetherMetrics;
import org.pragmatica.http.routing.VersioningMetricsSink;
import org.pragmatica.lang.Contract;


/// [AetherMetrics]-backed implementation of the #198 §11.1 versioning metrics sink.
///
/// Bridges the dependency-free `http-routing` sink seam to the Micrometer-backed [AetherMetrics]
/// counters: `http.requests.versioned`, `api.versioning.deprecated.requests`, and
/// `api.versioning.missing.header`. Each emit increments the corresponding tagged counter.
public final class AetherVersioningMetricsSink implements VersioningMetricsSink {
    private final AetherMetrics metrics;

    private AetherVersioningMetricsSink(AetherMetrics metrics) {
        this.metrics = metrics;
    }

    /// Factory for the AetherMetrics-backed versioning sink.
    ///
    /// @param metrics the aether metrics facade backing the version counters
    /// @return the sink
    public static AetherVersioningMetricsSink aetherVersioningMetricsSink(AetherMetrics metrics) {
        return new AetherVersioningMetricsSink(metrics);
    }

    @Override
    @Contract
    public void versionedRequest(String slice, int version, String method, int status) {
        metrics.versionedRequestCounter(slice, Integer.toString(version), method, Integer.toString(status))
               .increment();
    }

    @Override
    @Contract
    public void deprecatedRequest(String slice, int version) {
        metrics.deprecatedVersionRequestCounter(slice, Integer.toString(version)).increment();
    }

    @Override
    @Contract
    public void missingVersionHeader(String slice) {
        metrics.missingVersionHeaderCounter(slice).increment();
    }
}
