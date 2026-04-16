// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;


/// Factory that provisions a {@link LoggingMethodInterceptor} adding entry/exit logging.
///
/// Uses SLF4J for logging method invocations. Configurable log level,
/// argument logging, result logging, and duration logging.
public final class LoggingInterceptorFactory implements ResourceFactory<LoggingMethodInterceptor, LogConfig> {
    @Override public Class<LoggingMethodInterceptor> resourceType() {
        return LoggingMethodInterceptor.class;
    }

    @Override public Class<LogConfig> configType() {
        return LogConfig.class;
    }

    @Override public Promise<LoggingMethodInterceptor> provision(LogConfig config) {
        return Promise.success(new LoggingMethodInterceptor(config));
    }
}
