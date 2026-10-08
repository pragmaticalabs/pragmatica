// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import java.time.Instant;
import java.util.Collections;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// The connector's three parameter-binding sites (autocommit, transactional single statements, transactional batches)
/// against a LIVE PostgreSQL 16 through the real pgjdbc driver. pgjdbc refuses `setObject(Instant)`; the replacement
/// binding must store the UTC instant in a `timestamptz` AND a `timestamp` column whatever the JVM zone, so the zone is
/// forced to Asia/Tokyo for the class. H2 (`JdbcInstantParameterTest`) accepts `Instant` natively and cannot see this.
/// Self-skips when no Docker daemon is reachable.
class JdbcSqlConnectorPostgresInstantTest {
    private static final Instant INSTANT = Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final String UTC_WALL = "2026-01-02 03:04:05.123456";
    private static TimeZone previousZone;
    private static PostgreSQLContainer<?> postgres;
    private static JdbcSqlConnector connector;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        var dataSource = new PGSimpleDataSource();

        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var config = DatabaseConnectorConfig.databaseConnectorConfigBuilder()
                                            .withName("pg-instant")
                                            .withJdbcUrl(postgres.getJdbcUrl())
                                            .build()
                                            .unwrap();

        connector = JdbcSqlConnector.jdbcSqlConnector(config, dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (connector != null) {
            connector.stop().await();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (previousZone != null) {
            TimeZone.setDefault(previousZone);
        }
    }

    @BeforeEach
    void freshTable() {
        connector.update("DROP TABLE IF EXISTS instants").await().unwrap();
        connector.update("CREATE TABLE instants(tz TIMESTAMPTZ, ts TIMESTAMP)").await().unwrap();
    }

    @Test
    void update_instant_storesUtcInstantInBothColumnTypesUnderTokyoJvm() {
        assertThat(TimeZone.getDefault().getID()).as("control: the JVM zone under test").isEqualTo("Asia/Tokyo");

        connector.update("INSERT INTO instants VALUES (?, ?)", INSTANT, INSTANT).await().unwrap();

        assertStoredAsUtc();
    }

    @Test
    void transactional_updateQueryAndBatch_shareTheUtcInstantBinding() {
        var matches = connector.transactional(tx -> tx.update("INSERT INTO instants VALUES (?, ?)", INSTANT, INSTANT)
                                                      .flatMap(_ -> tx.batch("INSERT INTO instants VALUES (?, ?)",
                                                                             Collections.singletonList(new Object[]{INSTANT, INSTANT})))
                                                      .flatMap(_ -> tx.queryOne("SELECT COUNT(*) AS n FROM instants WHERE tz = ? AND ts = ?",
                                                                                row -> row.getLong("n"),
                                                                                INSTANT,
                                                                                INSTANT)))
                               .await()
                               .unwrap();

        assertThat(matches).as("both rows (single and batched) match the bound instant in both column types").isEqualTo(2L);
        assertStoredAsUtc(2);
    }

    private static void assertStoredAsUtc() {
        assertStoredAsUtc(1);
    }

    private static void assertStoredAsUtc(int rows) {
        var stored = connector.queryOne("SELECT COUNT(*) FILTER (WHERE (tz AT TIME ZONE 'UTC')::text = '" + UTC_WALL
                                        + "' AND ts::text = '" + UTC_WALL + "') AS n FROM instants",
                                        row -> row.getLong("n"))
                              .await()
                              .unwrap();

        assertThat(stored).as("rows whose timestamptz (as UTC wall) and timestamp both equal the UTC instant")
                          .isEqualTo((long) rows);
    }
}
