// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// #2100 E-B: a failed connector is evicted (N5a, T15, T18), the two-argument forms are overridden on the instance
/// (T15 tripwire) and a fake that overrides nothing keeps seeing the section (N7).
class DatasourceConnectionProviderEvictionTest {
    private static final String BOOM = "loader boom";

    /// Fails on the first load, then returns a valid configuration (no `SqlConnector` factory is on this classpath, so the
    /// second creation fails at factory selection, a distinct cause that proves it ran anew).
    private static Fn2<Result<?>, String, Class<?>> failThenConfig(AtomicInteger loads, List<String> sections) {
        return (section, _) -> loaded(loads, sections, section);
    }

    private static Result<?> loaded(AtomicInteger loads, List<String> sections, String section) {
        sections.add(section);

        return loads.incrementAndGet() == 1
               ? Causes.cause(BOOM).result()
               : DatabaseConnectorConfig.databaseConnectorConfigBuilder()
                                        .withAsyncUrl("postgresql://h/db")
                                        .build();
    }

    @Test
    void connector_failedCreation_isEvictedSoTheNextCallCreatesAnew() {
        var loads = new AtomicInteger();
        var sections = new CopyOnWriteArrayList<String>();
        var provider = DatasourceConnectionProvider.datasourceConnectionProvider(failThenConfig(loads, sections));

        var first = provider.connector("database.main@entity_orders#1", "database.main").await();
        var second = provider.connector("database.main@entity_orders#1", "database.main").await();

        assertThat(first.isFailure()).isTrue();
        first.onFailure(cause -> assertThat(cause.message()).contains(BOOM));
        assertThat(second.isFailure()).isTrue();
        second.onFailure(cause -> assertThat(cause.message()).as("second call ran anew").contains("No SqlConnector factory"));
        assertThat(loads.get()).as("loader ran for both calls").isEqualTo(2);
        assertThat(sections).as("created from the section, cached by the key").containsExactly("database.main", "database.main");
    }

    @Test
    void connector_alreadyFailedPromise_returnsFailureWithoutRecursiveUpdate() throws Exception {
        Fn2<Result<?>, String, Class<?>> alwaysFails = (_, _) -> Causes.cause(BOOM).result();
        var provider = DatasourceConnectionProvider.datasourceConnectionProvider(alwaysFails);

        var result = provider.connector("k", "s").await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains(BOOM));
        assertThat(cacheOf(provider)).as("failed entry evicted").isEmpty();
    }

    @Test
    void instance_declaresBothTwoArgumentOverrides() throws Exception {
        var connector = DatasourceConnectionProviderInstance.class.getDeclaredMethod("connector", String.class, String.class);
        var release = DatasourceConnectionProviderInstance.class.getDeclaredMethod("release", String.class, String.class);

        assertThat(connector.getDeclaringClass()).isEqualTo(DatasourceConnectionProviderInstance.class);
        assertThat(release.getDeclaringClass()).isEqualTo(DatasourceConnectionProviderInstance.class);
    }

    @Test
    void defaults_serveTheSectionToAFakeThatOverridesNothing() {
        var seen = new CopyOnWriteArrayList<String>();
        DatasourceConnectionProvider fake = new DatasourceConnectionProvider() {
            @Override
            public Promise<SqlConnector> connector(String datasourceName) {
                seen.add("connector:" + datasourceName);
                return Causes.cause("fake").promise();
            }

            @Override
            public Promise<Unit> release(String datasourceName) {
                seen.add("release:" + datasourceName);
                return Promise.success(unit());
            }

            @Override
            public Promise<Unit> releaseAll() {
                return Promise.success(unit());
            }
        };

        fake.connector("database.main@entity_orders#1", "database.main").await();
        fake.release("database.main@entity_orders#1", "database.main").await();

        assertThat(seen).containsExactly("connector:database.main", "release:database.main");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Promise<SqlConnector>> cacheOf(DatasourceConnectionProvider provider) throws Exception {
        Field field = DatasourceConnectionProviderInstance.class.getDeclaredField("connectors");

        field.setAccessible(true);
        return (Map<String, Promise<SqlConnector>>) field.get(provider);
    }
}
