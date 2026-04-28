// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;


public final class MetricsInterceptorFactory implements ResourceFactory<MetricsMethodInterceptor, MetricsConfig> {
    @Override public Class<MetricsMethodInterceptor> resourceType() {
        return MetricsMethodInterceptor.class;
    }

    @Override public Class<MetricsConfig> configType() {
        return MetricsConfig.class;
    }

    @Override public Promise<MetricsMethodInterceptor> provision(MetricsConfig config) {
        return Promise.success(new MetricsMethodInterceptor(config));
    }
}
