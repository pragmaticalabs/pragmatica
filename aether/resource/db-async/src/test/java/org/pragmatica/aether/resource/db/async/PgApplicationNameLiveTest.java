// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnector;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.testing.DockerRequirement;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// #2948 against a live PostgreSQL: the sessions the pool opens carry the configured `application_name` in
/// `pg_stat_activity`, and `min_connections` sessions are open once provisioning returns.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PgApplicationNameLiveTest {
    private static final String COUNT_NAMED = "SELECT count(*) AS n FROM pg_stat_activity WHERE application_name = ?";
    private static PostgreSQLContainer<?> postgres;
    private final List<DatabaseConnector> opened = new ArrayList<>();

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL application_name check");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterEach
    void stopConnectors() {
        opened.forEach(connector -> connector.stop().await());
        opened.clear();
    }

    private DatabaseConnectorConfig config(String name, String applicationName, int min) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(min).withMaxConnections(5).build().unwrap();

        return databaseConnectorConfigBuilder().withName(name)
                                               .withAsyncUrl("postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName())
                                               .withUsername(postgres.getUsername())
                                               .withPassword(postgres.getPassword())
                                               .withPoolConfig(pool)
                                               .withApplicationName(applicationName)
                                               .build()
                                               .unwrap();
    }

    private <C extends SqlConnector> C provision(ResourceFactory<C, DatabaseConnectorConfig> factory, DatabaseConnectorConfig config) {
        var connector = factory.provision(config).await().unwrap();

        opened.add(connector);

        return connector;
    }

    private static int sessionsNamed(SqlConnector connector, String applicationName) {
        return connector.queryOne(COUNT_NAMED, row -> row.getInt("n"), applicationName).await().unwrap();
    }

    @Test
    void pgSqlConnector_sessionsCarryConfiguredApplicationName_andPoolIsWarm() {
        var connector = provision(new PgSqlConnectorFactory(), config("live-pg", "bench-arm-pg", 3));

        assertThat(sessionsNamed(connector, "bench-arm-pg")).isEqualTo(3);
    }

    @Test
    void asyncSqlConnector_sessionsCarryConfiguredApplicationName_andPoolIsWarm() {
        var connector = provision(new AsyncSqlConnectorFactory(), config("live-async", "bench-arm-async", 2));

        assertThat(sessionsNamed(connector, "bench-arm-async")).isEqualTo(2);
    }

    @Test
    void pgSqlConnector_withMinZero_opensSessionsLazily() {
        var connector = provision(new PgSqlConnectorFactory(), config("live-lazy", "bench-arm-lazy", 0));

        assertThat(sessionsNamed(connector, "bench-arm-lazy")).isEqualTo(1);
    }

    @Test
    void pgSqlConnector_withoutConfiguredName_usesDerivedAetherName() {
        var connector = provision(new PgSqlConnectorFactory(), config("live-derived", null, 1));

        assertThat(sessionsNamed(connector, PgPoolSettings.applicationName(config("live-derived", null, 1)))).isEqualTo(1);
    }
}
