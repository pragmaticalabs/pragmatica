// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2973 D2.7 and D2.8 for JdbcSqlConnectorFactory: the view it hands a holder is not the pool, its stop() releases that holder and
/// never stops the pool; and the pool is created under the factory's class loader, never the caller's (a node-shared pool
/// may outlive the slice whose thread opened it, and its threads inherit the creating thread's context loader).
class JdbcSqlConnectorFactorySharedPoolTest {
    private static final TimeSpan TIMEOUT = timeSpan(10).seconds();

    private final JdbcSqlConnectorFactory factory = new JdbcSqlConnectorFactory();

    private static DatabaseConnectorConfig config() {
        return databaseConnectorConfigBuilder().withName("db")
                                               .withJdbcUrl("jdbc:h2:mem:tccl_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
                                               .withType(DatabaseType.H2)
                                               .withUsername("sa")
                                               .withPassword("")
                                               .withPoolConfig(PoolConfig.DEFAULT)
                                               .build()
                                               .unwrap();
    }

    @Test
    void view_isNotThePool_stopReleases_andNeverStopsThePool() {
        var poolStops = new AtomicInteger();
        var releases = new AtomicInteger();
        var pool = (SqlConnector) Proxy.newProxyInstance(SqlConnector.class.getClassLoader(),
                                                   new Class<?>[]{SqlConnector.class},
                                                   (_, method, _) -> answer(method.getName(), poolStops));
        var view = factory.view(pool, () -> counted(releases));

        assertThat(view).isNotSameAs(pool);

        view.stop().await(TIMEOUT);
        view.stop().await(TIMEOUT);

        assertThat(releases.get()).as("the first stop() released this holder, once").isEqualTo(1);
        assertThat(poolStops.get()).as("the pool's own stop() is never called by a view").isZero();
    }

    private static Object answer(String method, AtomicInteger poolStops) {
        if (Set.of("stop", "close").contains(method)) {
            poolStops.incrementAndGet();
        }

        return Promise.unitPromise();
    }

    private static Promise<Unit> counted(AtomicInteger releases) {
        releases.incrementAndGet();

        return Promise.unitPromise();
    }

    @Test
    void provision_createsThePoolUnderTheFactorysLoader_notTheCallers() {
        var caller = new ClassLoader("caller-slice", null) {};
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        var before = Set.copyOf(Thread.getAllStackTraces().keySet());
        Promise<SqlConnector> provisioning;

        thread.setContextClassLoader(caller);
        try {
            provisioning = factory.provision(config());
        } finally {
            thread.setContextClassLoader(previous);
        }

        var pool = provisioning.await(TIMEOUT).unwrap();
        var created = Thread.getAllStackTraces().keySet().stream().filter(t -> !before.contains(t)).toList();

        try {
            assertThat(created).as("control: provisioning started threads this check can see").isNotEmpty();
            assertThat(created).as("a thread created by provisioning keeps the caller's loader")
                               .noneMatch(t -> t.getContextClassLoader() == caller);
            assertThat(created).as("the factory's loader is the one in force")
                               .anyMatch(t -> t.getContextClassLoader() == factory.getClass().getClassLoader());
        } finally {
            pool.stop().await(TIMEOUT);
        }
    }
}
