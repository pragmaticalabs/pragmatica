// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.List;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;


public final class MetricsInterceptorFactory implements ResourceFactory<MetricsMethodInterceptor, MetricsConfig> {
    @Override
    public Class<MetricsMethodInterceptor> resourceType() {
        return MetricsMethodInterceptor.class;
    }

    @Override
    public Class<MetricsConfig> configType() {
        return MetricsConfig.class;
    }

    @Override
    public Promise<MetricsMethodInterceptor> provision(MetricsConfig config) {
        return provision(config, ProvisioningContext.provisioningContext());
    }

    @Override
    public Promise<MetricsMethodInterceptor> provision(MetricsConfig config, ProvisioningContext context) {
        return Result.all(context.extension(MeterRegistry.class),
                          parseTags(config.tags()))
                     .map((registry, tags) -> new MetricsMethodInterceptor(config, registry, tags))
                     .async();
    }

    // A malformed "key=value" tag (see MetricsMethodInterceptor#tagOf) fails provisioning here,
    // once, with the offending value named — not the interceptor's first invocation.
    // Keep the explicit lambda: `Tags::of` is ambiguous here (matches both Result#map(Fn1) via the
    // Iterable overload and Result#map(Supplier) via the zero-arg-varargs overload) and fails to
    // compile — do not apply jbct:check's JBCT-STY-05 "simplify to method reference" suggestion.
    private static Result<Tags> parseTags(List<String> rawTags) {
        return Result.allOf(rawTags.stream().map(MetricsMethodInterceptor::tagOf).toList()).map(tags -> Tags.of(tags));
    }
}
