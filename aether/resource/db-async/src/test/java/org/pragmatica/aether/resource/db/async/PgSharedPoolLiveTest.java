// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.PoolScope;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testing.DockerRequirement;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// T-PG (#2973): node-shared pools and the demand guard against a REAL PostgreSQL, through the real provider. The only
/// non-fake test of the feature.
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PgSharedPoolLiveTest {
    private static final TimeSpan TIMEOUT = timeSpan(30).seconds();
    private static final String COUNT_NAMED = "SELECT count(*)::int AS n FROM pg_stat_activity WHERE application_name = ?";

    private final List<PostgreSQLContainer<?>> containers = new CopyOnWriteArrayList<>();
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopContainers() {
        containers.forEach(PostgreSQLContainer::stop);
    }

    private PostgreSQLContainer<?> postgres(int maxConnections) {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live shared-pool check");

        var container = new PostgreSQLContainer<>("postgres:16").withCommand("postgres",
                                                                              "-c",
                                                                              "max_connections=" + maxConnections);

        container.start();
        containers.add(container);

        return container;
    }

    private static DatabaseConnectorConfig config(PostgreSQLContainer<?> postgres, int min, int max, Option<PoolScope> scope) {
        var pool = PoolConfig.poolConfigBuilder()
                             .withMinConnections(min)
                             .withMaxConnections(max)
                             .withConnectionTimeout(org.pragmatica.lang.parse.TimeSpan.timeSpan("5s").unwrap())
                             .build()
                             .unwrap();
        var builder = databaseConnectorConfigBuilder().withAsyncUrl("postgresql://" + postgres.getHost() + ":"
                                                                    + postgres.getMappedPort(5432) + "/"
                                                                    + postgres.getDatabaseName())
                                                      .withUsername(postgres.getUsername())
                                                      .withPassword(postgres.getPassword())
                                                      .withApplicationName("t-pg-shared")
                                                      .withPoolConfig(pool);

        return scope.map(builder::withPoolScope).or(builder).build().unwrap();
    }

    private SpiResourceProvider provider(Map<String, DatabaseConnectorConfig> sections) {
        var provider = SpiResourceProvider.spiResourceProvider(List.of(new PgSqlConnectorFactory()),
                                                               (section, _) -> Option.option(sections.get(section))
                                                                                     .toResult(org.pragmatica.lang.utils.Causes.cause("no " + section)));

        provider.registerExtension(OperatorWarningSink.class, OperatorWarningSink.handingOffTo(raised::add));

        return provider;
    }

    private boolean raisedWithin(OperatorWarningCode code, long millis) throws InterruptedException {
        var deadline = System.currentTimeMillis() + millis;

        while (System.currentTimeMillis() < deadline) {
            if (raised.stream().anyMatch(warning -> warning.code() == code)) {
                return true;
            }

            Thread.sleep(200);
        }

        return false;
    }

    private static PgSqlConnector acquire(SpiResourceProvider provider, String section, String slice) {
        return provider.provide(PgSqlConnector.class,
                                section,
                                ProvisioningContext.provisioningContext().withExtension(String.class, slice))
                       .await(TIMEOUT)
                       .unwrap();
    }

    @Test
    void threeSlicesOnOneConfig_openOnePoolsSessions() {
        var postgres = postgres(100);
        var provider = provider(Map.of("database", config(postgres, 3, 5, Option.none())));
        var a = acquire(provider, "database", "slice-a");

        acquire(provider, "database", "slice-b");
        acquire(provider, "database", "slice-c");

        Result<Integer> sessions = a.queryOne(COUNT_NAMED, row -> row.getInt("n"), "t-pg-shared").await(TIMEOUT);

        assertThat(sessions.unwrap()).as("one pool's warm sessions, not three pools'").isEqualTo(3);

        provider.closeShared().await(TIMEOUT);
    }

    @Test
    void aFullServer_refusingTheProbe_raisesServerRefused() throws InterruptedException {
        var postgres = postgres(6);
        var provider = provider(Map.of("database.full", config(postgres, 6, 6, Option.some(PoolScope.SLICE)),
                                       "database.late", config(postgres, 1, 2, Option.some(PoolScope.SLICE))));

        acquire(provider, "database.full", "slice-a");
        acquire(provider, "database.late", "slice-b");

        assertThat(raisedWithin(OperatorWarningCode.DATASOURCE_SERVER_REFUSED_CONNECTION, 30_000)).as("raised: %s", raised)
                                                                                         .isTrue();

        provider.releaseAll("slice-a").await(TIMEOUT);
        provider.releaseAll("slice-b").await(TIMEOUT);
    }
}
