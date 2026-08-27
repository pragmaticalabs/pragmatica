// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;

import io.micrometer.core.instrument.MeterRegistry;


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
        return context.extension(MeterRegistry.class)
                      .map(registry -> new MetricsMethodInterceptor(config, registry))
                      .async();
    }
}
