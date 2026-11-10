// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.r2dbc.probe;

import java.util.concurrent.atomic.AtomicReference;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.ConnectionFactoryProvider;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/// A test-only R2DBC driver that records the context class loader of the thread asked to create its connection factory.
/// It lives outside the factory's package, so the factory's own-package loader never redefines it, and registered
/// through `META-INF/services` so `ConnectionFactories.get` finds it by its driver name.
public final class TcclProbeProvider implements ConnectionFactoryProvider {
    public static final String DRIVER = "tccl-probe";
    public static final AtomicReference<ClassLoader> SEEN = new AtomicReference<>();

    @Override
    public ConnectionFactory create(ConnectionFactoryOptions options) {
        SEEN.set(Thread.currentThread().getContextClassLoader());

        return new ConnectionFactory() {
            @Override
            public Publisher<? extends Connection> create() {
                return Mono.error(new IllegalStateException("tccl probe has no connections"));
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return () -> DRIVER;
            }
        };
    }

    @Override
    public boolean supports(ConnectionFactoryOptions options) {
        return DRIVER.equals(options.getValue(ConnectionFactoryOptions.DRIVER));
    }

    @Override
    public String getDriver() {
        return DRIVER;
    }
}
