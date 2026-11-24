// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3635, ReleaseChain invariant at the assembly sites: `builder.pool()` holds a lease from the moment it returns, so a
/// failure of everything built after it (here the dedicated `plain()` Connectible) must close that pool before the failure
/// is reported. One test per assemble() site.
class AssemblyReleasesPoolOnFailureTest {
    private static final TimeSpan TIMEOUT = timeSpan(10).seconds();

    private static DatabaseConnectorConfig config() {
        return databaseConnectorConfigBuilder().withName("db")
                                               .withAsyncUrl("postgresql://127.0.0.1:1/db")
                                               .withUsername("u")
                                               .withPassword("p")
                                               .build()
                                               .unwrap();
    }

    @Test
    void asyncSqlConnectorFactory_assemble_closesThePool_whenPlainFails() {
        var builder = new PlainFailsBuilder();

        var outcome = AsyncSqlConnectorFactory.assemble(config(), builder).await(TIMEOUT);

        assertThat(outcome.isFailure()).as("assembly failed with the injected failure").isTrue();
        assertThat(builder.poolCloses.get()).as("the pool built before the failure was closed once").isEqualTo(1);
    }

    @Test
    void pgSqlConnectorFactory_assemble_closesThePool_whenPlainFails() {
        var builder = new PlainFailsBuilder();

        var outcome = PgSqlConnectorFactory.assemble(config(), builder).await(TIMEOUT);

        assertThat(outcome.isFailure()).as("assembly failed with the injected failure").isTrue();
        assertThat(builder.poolCloses.get()).as("the pool built before the failure was closed once").isEqualTo(1);
    }

    /// Builds the real pool (so its lease is real) but counts its close, and cannot build the dedicated Connectible.
    private static final class PlainFailsBuilder extends NettyConnectibleBuilder {
        final AtomicInteger poolCloses = new AtomicInteger();

        @Override
        public Connectible pool() {
            return closeCounting(super.pool(), poolCloses);
        }

        @Override
        public Connectible plain() {
            throw new IllegalStateException("plain() failed (injected by the test)");
        }
    }

    private static Connectible closeCounting(Connectible delegate, AtomicInteger closes) {
        return (Connectible) Proxy.newProxyInstance(Connectible.class.getClassLoader(),
                                                    new Class<?>[]{Connectible.class},
                                                    (_, method, args) -> invoke(delegate, closes, method, args));
    }

    private static Object invoke(Connectible delegate,
                                 AtomicInteger closes,
                                 Method method,
                                 Object[] args) throws Throwable {
        if (method.getName().equals("close")) {
            closes.incrementAndGet();
        }

        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
