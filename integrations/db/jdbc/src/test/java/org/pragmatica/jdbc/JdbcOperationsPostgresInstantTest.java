/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.jdbc;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `Instant` parameters against a LIVE PostgreSQL 16 through the real pgjdbc driver (the H2 tests accept `Instant`
/// natively and cannot see the defect). pgjdbc refuses `setObject(Instant)` outright ("Can't infer the SQL type"); the
/// binding that replaces it must also not depend on the JVM time zone, for a `timestamptz` column AND a `timestamp`
/// (without time zone) column, so the JVM default zone is forced to Asia/Tokyo (UTC+9, no DST) for the whole class.
/// Self-skips when no Docker daemon is reachable.
class JdbcOperationsPostgresInstantTest {
    private static final Instant INSTANT = Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final String UTC_WALL = "2026-01-02 03:04:05.123456";
    private static TimeZone previousZone;
    private static PostgreSQLContainer<?> postgres;
    private static JdbcOperations jdbc;

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
        jdbc = JdbcOperations.jdbcOperations(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
        if (previousZone != null) {
            TimeZone.setDefault(previousZone);
        }
    }

    @BeforeEach
    void freshTable() {
        jdbc.update("DROP TABLE IF EXISTS instants").await().unwrap();
        jdbc.update("CREATE TABLE instants(tz TIMESTAMPTZ, ts TIMESTAMP)").await().unwrap();
    }

    @Test
    void update_instantIntoTimestamptzAndTimestamp_storesUtcInstantUnderTokyoJvm() {
        assertThat(TimeZone.getDefault().getID()).as("control: the JVM zone under test").isEqualTo("Asia/Tokyo");

        jdbc.update("INSERT INTO instants VALUES (?, ?)", INSTANT, INSTANT).await().unwrap();

        assertStoredAsUtc();
    }

    @Test
    void batch_instantIntoTimestamptzAndTimestamp_storesUtcInstantUnderTokyoJvm() {
        jdbc.batch("INSERT INTO instants VALUES (?, ?)", Collections.singletonList(new Object[]{INSTANT, INSTANT}))
            .await()
            .unwrap();

        assertStoredAsUtc();
    }

    @Test
    void query_instantParameterMatchesTheStoredInstantInBothColumnTypes() {
        jdbc.update("INSERT INTO instants VALUES (?, ?)", INSTANT, INSTANT).await().unwrap();

        var matches = jdbc.queryOne("SELECT COUNT(*) FROM instants WHERE tz = ? AND ts = ?",
                                    rows -> rows.getLong(1),
                                    INSTANT,
                                    INSTANT)
                          .await()
                          .unwrap();

        assertThat(matches).isEqualTo(1L);
    }

    private static void assertStoredAsUtc() {
        var stored = jdbc.queryOne("SELECT (tz AT TIME ZONE 'UTC')::text || '|' || ts::text FROM instants",
                                   rows -> rows.getString(1))
                         .await()
                         .unwrap();

        assertThat(stored).as("timestamptz read as its UTC wall clock | timestamp wall clock")
                          .isEqualTo(UTC_WALL + "|" + UTC_WALL);
    }
}
