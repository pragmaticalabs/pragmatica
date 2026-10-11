// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;


public final class LoggingInterceptorFactory implements ResourceFactory<LoggingMethodInterceptor, LogConfig> {
    @Override
    public Class<LoggingMethodInterceptor> resourceType() {
        return LoggingMethodInterceptor.class;
    }

    @Override
    public Class<LogConfig> configType() {
        return LogConfig.class;
    }

    @Override
    public Promise<LoggingMethodInterceptor> provision(LogConfig config) {
        return Promise.success(new LoggingMethodInterceptor(config));
    }
}
