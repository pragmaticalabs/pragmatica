// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.db.migration.MigrationScript;
import org.pragmatica.db.migration.SchemaMigrations;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testing.DockerRequirement;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2787: two Terra instances start against ONE live PostgreSQL 16 database, and the migration runs exactly once.
///
/// Each instance has its own datasource provider (its own pool and its own pinned migration session), reached through
/// the real datasource SPI with a production transport (Hikari JDBC, pg-async). Instance A runs through this JVM's
/// migration queue; instance B runs outside it, as a second process would (a process has a queue of its own), so the only
/// exclusion between them is the database fence. The interleaving is forced by locks, never by sleeps: A is parked
/// inside its script on a `gate` table the test holds ACCESS EXCLUSIVE, with the fence held; B is started only then, and
/// the gate is released only after B has met the fence twice. A rollback-proof counter (`nextval`) counts entries into
/// the script. Self-skips without Docker unless `-Daether.requireDocker=true`.
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class TerraMigrationsFenceTest {
    private static final int FENCE_CLASS = 0x41455448;
    private static final int FENCE_OBJECT = 1;
    private static final String RUNNER_ROLE = "terra_runner";
    private static final String RUNNER_PASSWORD = "terra_runner";
    private static final String DATASOURCE = "database";
    private static final TimeSpan GENEROUS = timeSpan(60).seconds();
    private static final long WAIT_MS = 30_000;
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String SCRIPT = """
        INSERT INTO entry_log VALUES (nextval('entries'));
        INSERT INTO gate VALUES (1);
        CREATE TABLE once_done (id INT);
        """;
    private static PostgreSQLContainer<?> postgres;

    private final List<Connection> held = new ArrayList<>();
    private String database;

    enum Transport { JDBC, ASYNC }

    @BeforeAll
    static void startPostgres() throws SQLException {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL two-instance migration test");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE ROLE " + RUNNER_ROLE + " LOGIN PASSWORD '" + RUNNER_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        held.forEach(TerraMigrationsFenceTest::closeQuietly);
        held.clear();

        if (database != null) {
            try (var admin = superuser(postgres.getDatabaseName())) {
                execute(admin, "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    void apply_twoInstancesStartAgainstOneDatabase_theMigrationRunsExactlyOnce(Transport transport) throws Exception {
        freshDatabase();
        var gate = holdGate();
        var helds = new AtomicInteger();
        var secondHeld = Promise.<Unit>promise();

        var first = instance(transport, _ -> timeSpan(50).millis()).apply();

        awaitFenceHolderParkedOnTheGate();

        var second = instance(transport, count -> observeHeld(helds, secondHeld, count)).migrateAll();

        assertThat(secondHeld.await(GENEROUS).isSuccess()).as("the second instance met the fence held by the first, twice").isTrue();
        assertThat(second.isResolved()).as("a held fence is no failure: the second instance is still waiting").isFalse();
        assertThat(entries()).as("only the first instance entered the script so far").isEqualTo(1);

        gate.rollback();

        var firstReports = first.await(GENEROUS).unwrap();
        var secondReports = second.await(GENEROUS).unwrap();

        assertThat(firstReports).hasSize(1);
        assertThat(firstReports.getFirst().result().appliedCount()).as("the first instance applied the migration").isEqualTo(1);
        assertThat(secondReports).hasSize(1);
        assertThat(secondReports.getFirst().result().appliedCount()).as("the second instance found it applied").isZero();
        assertThat(secondReports.getFirst().result().currentVersion()).isEqualTo(1);
        assertThat(entries()).as("the script was entered exactly once across both instances").isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM aether_schema_history WHERE version = 1")).as("one history row").isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM pg_class WHERE relname = 'once_done'")).isEqualTo(1);
        assertThat(helds.get()).isGreaterThanOrEqualTo(2);
    }

    private static TimeSpan observeHeld(AtomicInteger helds, Promise<Unit> secondHeld, int count) {
        if (helds.incrementAndGet() >= 2) {
            secondHeld.succeed(Unit.unit());
        }

        return timeSpan(50).millis();
    }

    /// One Terra instance: its own provider, hence its own pool and its own pinned migration session.
    private MigrationRun instance(Transport transport, Fn1<TimeSpan, Integer> heldBackoff) {
        var config = connectorConfig(transport);
        var provider = DatasourceConnectionProvider.datasourceConnectionProvider((_, _) -> Result.success(config));
        var scripts = List.of(MigrationScript.migrationScript("V001__once.sql", SCRIPT, SCRIPT.hashCode()));

        return MigrationRun.migrationRun("g:app",
                                         List.of(MigrationTask.migrationTask(DATASOURCE, scripts)),
                                         provider,
                                         SchemaMigrations.schemaMigrations(TerraMigrations.STATEMENT_TIMEOUT),
                                         heldBackoff);
    }

    private DatabaseConnectorConfig connectorConfig(Transport transport) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(2).build().unwrap();
        var builder = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                      .withUsername(RUNNER_ROLE)
                                                      .withPassword(RUNNER_PASSWORD)
                                                      .withPoolConfig(pool);
        var hostPort = postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;

        return switch (transport) {
            case JDBC -> builder.withJdbcUrl(jdbcUrl(database)).build().unwrap();
            case ASYNC -> builder.withAsyncUrl("postgresql://" + hostPort).build().unwrap();
        };
    }

    private void freshDatabase() throws SQLException {
        database = "terra_fence_" + DATABASES.incrementAndGet();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE DATABASE " + database + " OWNER " + RUNNER_ROLE);
        }

        try (var owner = DriverManager.getConnection(jdbcUrl(database), RUNNER_ROLE, RUNNER_PASSWORD)) {
            execute(owner, "CREATE SEQUENCE entries");
            execute(owner, "CREATE TABLE entry_log (id BIGINT)");
            execute(owner, "CREATE TABLE gate (id INT)");
        }
    }

    private Connection holdGate() throws SQLException {
        var gate = superuser(database);

        held.add(gate);
        gate.setAutoCommit(false);
        execute(gate, "LOCK TABLE gate IN ACCESS EXCLUSIVE MODE");

        return gate;
    }

    /// The first instance holds the fence (granted advisory lock) and its migration session waits on the gate.
    private void awaitFenceHolderParkedOnTheGate() throws Exception {
        awaitTrue("SELECT count(*) = 1 FROM pg_locks WHERE locktype = 'advisory' AND granted AND classid = " + FENCE_CLASS
                  + " AND objid = " + FENCE_OBJECT);
        awaitTrue("SELECT count(*) = 1 FROM pg_stat_activity WHERE datname = current_database() AND application_name LIKE 'aether-mig:%'"
                  + " AND wait_event_type = 'Lock'");
    }

    private void awaitTrue(String sql) throws Exception {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (scalar("SELECT CASE WHEN (" + sql + ") THEN 1 ELSE 0 END") == 1) {
                return;
            }

            Thread.sleep(25);
        }

        throw new AssertionError("condition never held: " + sql);
    }

    private long entries() throws SQLException {
        return scalar("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM entries");
    }

    private long scalar(String sql) throws SQLException {
        try (var conn = superuser(database); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection superuser(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db;
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (var statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // the test's own gate connection; closing it is cleanup only
        }
    }
}
