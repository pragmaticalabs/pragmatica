// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;


// #278: no MeterRegistry field. A registry is a runtime singleton, not TOML data — a record field
// for it was unbindable from config and forced every call site to fabricate its own disconnected
// registry (never wired to the Management API's real one). The interceptor factory now resolves
// the node's actual MeterRegistry from ProvisioningContext (mirrors CacheInterceptorFactory's
// DHTClient/Serializer/Deserializer extensions) and hands it to MetricsMethodInterceptor directly.
public record MetricsConfig(String name, boolean recordTiming, boolean recordCounts, List<String> tags) {
    public static Result<MetricsConfig> metricsConfig(String name) {
        return ensure(name, Verify.Is::notBlank).map(n -> new MetricsConfig(n, true, true, List.of()));
    }

    public static Result<MetricsConfig> metricsConfig(String name, boolean recordTiming, boolean recordCounts) {
        return ensure(name, Verify.Is::notBlank).map(n -> new MetricsConfig(n, recordTiming, recordCounts, List.of()));
    }

    // Exact record-component-shaped factory: the reflective TOML binder (ProviderBasedConfigService)
    // only invokes a factory method whose parameter types match the record components verbatim,
    // so this is what actually runs when [section] carries record_timing/record_counts/tags.
    public static Result<MetricsConfig> metricsConfig(String name,
                                                      boolean recordTiming,
                                                      boolean recordCounts,
                                                      List<String> tags) {
        return ensure(name, Verify.Is::notBlank).map(n -> new MetricsConfig(n, recordTiming, recordCounts, tags));
    }

    public MetricsConfig withTags(String... tags) {
        return new MetricsConfig(name, recordTiming, recordCounts, List.of(tags));
    }
}
