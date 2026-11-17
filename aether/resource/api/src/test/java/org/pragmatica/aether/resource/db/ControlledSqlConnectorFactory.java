// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

/// Test `SqlConnector` factory that claims only `controlled://<name>` async URLs and provisions whatever the test
/// registered under `<name>`, so a test decides when (and whether asynchronously) a creation fails or succeeds.
/// Registered through `META-INF/services`; every other test's configuration is unclaimed (#2100 E-B L0 eviction tests).
public final class ControlledSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig> {
    static final String SCHEME = "controlled://";

    private static final Map<String, Supplier<Promise<SqlConnector>>> BEHAVIOURS = new ConcurrentHashMap<>();

    static void behave(String name, Supplier<Promise<SqlConnector>> behaviour) {
        BEHAVIOURS.put(name, behaviour);
    }

    /// A provider whose configuration loader maps a section `x` to the async URL `controlled://x`.
    static DatasourceConnectionProvider provider() {
        return DatasourceConnectionProvider.datasourceConnectionProvider(ControlledSqlConnectorFactory::controlledConfig);
    }

    private static Result<?> controlledConfig(String section, Class<?> configClass) {
        return DatabaseConnectorConfig.databaseConnectorConfigBuilder()
                                      .withAsyncUrl(SCHEME + section)
                                      .build();
    }

    static SqlConnector stubConnector() {
        return stubConnector(new AtomicInteger());
    }

    /// A connector whose `stop` succeeds and is counted.
    static SqlConnector stubConnector(AtomicInteger stops) {
        return (SqlConnector) Proxy.newProxyInstance(ControlledSqlConnectorFactory.class.getClassLoader(),
                                                     new Class<?>[]{SqlConnector.class},
                                                     (_, method, _) -> method.getName().equals("stop")
                                                                       ? stopped(stops)
                                                                       : null);
    }

    private static Promise<Unit> stopped(AtomicInteger stops) {
        stops.incrementAndGet();
        return Promise.success(Unit.unit());
    }

    /// The provider's cache, read reflectively (the entry's presence is the observable under test).
    @SuppressWarnings("unchecked")
    static Map<String, Promise<SqlConnector>> cacheOf(DatasourceConnectionProvider provider) throws Exception {
        Field field = DatasourceConnectionProviderInstance.class.getDeclaredField("connectors");

        field.setAccessible(true);
        return (Map<String, Promise<SqlConnector>>) field.get(provider);
    }

    @Override
    public Class<SqlConnector> resourceType() {
        return SqlConnector.class;
    }

    @Override
    public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public boolean supports(DatabaseConnectorConfig config) {
        return config.asyncUrl().map(url -> url.startsWith(SCHEME)).or(false);
    }

    @Override
    public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        return config.asyncUrl()
                     .map(url -> BEHAVIOURS.get(url.substring(SCHEME.length())).get())
                     .or(() -> Causes.cause("unclaimed").promise());
    }
}
