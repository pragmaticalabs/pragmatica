// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;


public record MetricsConfig(String name,
                            MeterRegistry registry,
                            boolean recordTiming,
                            boolean recordCounts,
                            List<String> tags) {
    public static Result<MetricsConfig> metricsConfig(String name, MeterRegistry registry) {
        var validName = ensure(name, Verify.Is::notBlank);
        var validRegistry = ensure(registry, Verify.Is::notNull);
        return all(validName, validRegistry).map((n, r) -> new MetricsConfig(n, r, true, true, List.of()));
    }

    public static Result<MetricsConfig> metricsConfig(String name,
                                                      MeterRegistry registry,
                                                      boolean recordTiming,
                                                      boolean recordCounts) {
        var validName = ensure(name, Verify.Is::notBlank);
        var validRegistry = ensure(registry, Verify.Is::notNull);
        return all(validName, validRegistry).map((n, r) -> new MetricsConfig(n, r, recordTiming, recordCounts, List.of()));
    }

    public MetricsConfig withTags(String... tags) {
        return new MetricsConfig(name, registry, recordTiming, recordCounts, List.of(tags));
    }
}
