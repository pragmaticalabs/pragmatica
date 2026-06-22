// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.AppliedMigration;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.RowMapper.RowAccessor;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.AppliedMigration.appliedMigration;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Real-PostgreSQL proof of Increment B1 (#338): the `aether_schema_history` table is
/// internally-versioned and self-evolving. [SchemaHistoryRepository#bootstrap] creates a fixed
/// meta table tracking the history table's own internal schema version and runs ordered,
/// version-gated DDL steps to add the B2 columns (`status`, `statements_completed`) portably.
///
/// - [FreshBootstrap] proves a brand-new cluster reaches internal version 2 with both new columns
///   defaulted (`SUCCESS`, `0`).
/// - [ExistingCluster] proves the migration path: a pre-existing 8-column table with NO meta table
///   gains the new columns, its sample row preserved with the defaulted new values, meta set to 2.
/// - [Idempotent] proves a re-bootstrap on a cluster already at version 2 runs no further DDL and
///   leaves the columns intact.
/// - [RecordingWorksOnEvolvedTable] proves `recordMigration`/`queryApplied` keep working unchanged
///   against the evolved table, the new columns taking their defaults on a normally-recorded row.
///
/// Self-skips via [org.junit.jupiter.api.Assumptions#assumeTrue] when no Docker daemon is reachable.
class SchemaHistoryEvolutionPgTest {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainer() {
        assumeTrue(isDockerAvailable(), "Docker is not available - skipping real-PostgreSQL schema-evolution test");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void cleanSchema() {
        var connector = newConnector();
        connector.execUpdate("DROP TABLE IF EXISTS aether_schema_history");
        connector.execUpdate("DROP TABLE IF EXISTS aether_schema_history_meta");
    }

    @Nested
    class FreshBootstrap {

        /// A fresh bootstrap reaches internal version 2 and creates the history table with both new
        /// columns carrying their B2 defaults.
        @Test
        void bootstrap_reachesVersion2WithDefaultedColumns_forFreshCluster() {
            var connector = newConnector();

            repository().bootstrap(connector).await().onFailure(cause -> fail("bootstrap failed: " + cause.message()));

            assertThat(connector.metaVersion()).isEqualTo(2);
            assertThat(connector.columnDefault("status")).isEqualTo("'SUCCESS'::character varying");
            assertThat(connector.columnDefault("statements_completed")).isEqualTo("0");
            assertThat(connector.columnNotNull("status")).isTrue();
            assertThat(connector.columnNotNull("statements_completed")).isTrue();
        }
    }

    @Nested
    class ExistingCluster {

        /// Simulates an EXISTING cluster: the 8-column table and a sample row exist, but NO meta
        /// table. Bootstrap adds the two new columns, preserves the sample row with the defaulted
        /// new values, and sets the internal version to 2.
        @Test
        void bootstrap_addsColumnsAndPreservesRow_forExistingCluster() {
            var connector = newConnector();
            connector.execUpdate("""
                CREATE TABLE aether_schema_history (
                    version        INTEGER      NOT NULL,
                    type           VARCHAR(16)  NOT NULL DEFAULT 'VERSIONED',
                    description    VARCHAR(256) NOT NULL,
                    script         VARCHAR(512) NOT NULL,
                    checksum       BIGINT       NOT NULL,
                    applied_by     VARCHAR(128) NOT NULL,
                    applied_at     BIGINT       NOT NULL,
                    execution_ms   INTEGER      NOT NULL,
                    PRIMARY KEY (version, type)
                )""");
            connector.execUpdate("INSERT INTO aether_schema_history "
                                 + "(version, type, description, script, checksum, applied_by, applied_at, execution_ms) "
                                 + "VALUES (7, 'VERSIONED', 'legacy', 'V7__legacy.sql', 99, 'old-node', 1000, 5)");

            repository().bootstrap(connector).await().onFailure(cause -> fail("bootstrap failed: " + cause.message()));

            assertThat(connector.metaVersion()).isEqualTo(2);
            assertThat(connector.hasColumn("status")).isTrue();
            assertThat(connector.hasColumn("statements_completed")).isTrue();
            assertThat(connector.scalarLong("SELECT count(*) AS c FROM aether_schema_history WHERE version = 7")).isEqualTo(1);
            assertThat(connector.scalarString("SELECT status AS s FROM aether_schema_history WHERE version = 7")).isEqualTo("SUCCESS");
            assertThat(connector.scalarLong("SELECT statements_completed AS c FROM aether_schema_history WHERE version = 7")).isZero();
        }
    }

    @Nested
    class Idempotent {

        /// Re-bootstrapping a cluster already at version 2 runs no further DDL, raises no error, and
        /// leaves the table and its version intact.
        @Test
        void bootstrap_isNoOp_whenAlreadyAtLatestVersion() {
            var connector = newConnector();
            repository().bootstrap(connector).await().onFailure(cause -> fail("first bootstrap failed: " + cause.message()));

            repository().bootstrap(connector).await().onFailure(cause -> fail("second bootstrap failed: " + cause.message()));

            assertThat(connector.metaVersion()).isEqualTo(2);
            assertThat(connector.scalarLong("SELECT count(*) AS c FROM aether_schema_history_meta")).isEqualTo(1);
            assertThat(connector.hasColumn("status")).isTrue();
            assertThat(connector.hasColumn("statements_completed")).isTrue();
        }
    }

    @Nested
    class RecordingWorksOnEvolvedTable {

        /// The existing 8-column `recordMigration` INSERT and `queryApplied` keep working against the
        /// evolved table: a normally-recorded row reads back with the new columns at their defaults.
        @Test
        void recordMigration_andQueryApplied_workOnEvolvedTable() {
            var connector = newConnector();
            repository().bootstrap(connector).await().onFailure(cause -> fail("bootstrap failed: " + cause.message()));

            var migration = appliedMigration(3, MigrationType.VERSIONED, "create users", "V3__users.sql", 42L, "node-1", 2000L, 11);
            repository().recordMigration(connector, migration).await().onFailure(cause -> fail("recordMigration failed: " + cause.message()));

            var applied = repository().queryApplied(connector).await();
            applied.onFailure(cause -> fail("queryApplied failed: " + cause.message()));
            applied.onSuccess(SchemaHistoryEvolutionPgTest::assertSingleRecordedMigration);

            assertThat(connector.scalarString("SELECT status AS s FROM aether_schema_history WHERE version = 3")).isEqualTo("SUCCESS");
            assertThat(connector.scalarLong("SELECT statements_completed AS c FROM aether_schema_history WHERE version = 3")).isZero();
        }
    }

    private static void assertSingleRecordedMigration(List<AppliedMigration> migrations) {
        assertThat(migrations).hasSize(1);
        var row = migrations.getFirst();
        assertThat(row.version()).isEqualTo(3);
        assertThat(row.type()).isEqualTo(MigrationType.VERSIONED);
        assertThat(row.description()).isEqualTo("create users");
        assertThat(row.checksum()).isEqualTo(42L);
    }

    private static SchemaHistoryRepository repository() {
        return SchemaHistoryRepository.schemaHistoryRepository();
    }

    private static JdbcPgConnector newConnector() {
        return new JdbcPgConnector(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), none());
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /// Minimal real-PostgreSQL [SqlConnector] for the schema-evolution test, plus helper queries to
    /// inspect the evolved table via `information_schema`. Each operation runs against a freshly
    /// borrowed JDBC connection in autocommit; `transactional(...)` is unused by `bootstrap` but is
    /// provided for completeness.
    static final class JdbcPgConnector implements SqlConnector {
        private static final DatabaseConnectorConfig CONFIG = pgConfig();

        private final String url;
        private final String user;
        private final String password;
        private final Option<Connection> bound;

        JdbcPgConnector(String url, String user, String password, Option<Connection> bound) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.bound = bound;
        }

        @Override
        public DatabaseConnectorConfig config() {
            return CONFIG;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return runUpdate(sql, params).async();
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return runQuery(sql, mapper, params).async();
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return queryList(sql, mapper, params).map(JdbcPgConnector::firstOf);
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return queryOptional(sql, mapper, params).flatMap(JdbcPgConnector::requireOne);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Causes.cause("batch not supported in schema-evolution test").promise();
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        private void execUpdate(String sql) {
            update(sql).await().onFailure(JdbcPgConnector::throwSetup);
        }

        private int metaVersion() {
            return (int) scalarLong("SELECT schema_version AS c FROM aether_schema_history_meta");
        }

        private boolean hasColumn(String column) {
            return scalarLong("SELECT count(*) AS c FROM information_schema.columns "
                              + "WHERE table_name = 'aether_schema_history' AND column_name = '" + column + "'") > 0;
        }

        private String columnDefault(String column) {
            return scalarString("SELECT column_default AS s FROM information_schema.columns "
                                + "WHERE table_name = 'aether_schema_history' AND column_name = '" + column + "'");
        }

        private boolean columnNotNull(String column) {
            return "NO".equals(scalarString("SELECT is_nullable AS s FROM information_schema.columns "
                                            + "WHERE table_name = 'aether_schema_history' AND column_name = '" + column + "'"));
        }

        private long scalarLong(String sql) {
            return queryList(sql, row -> row.getLong("c")).await()
                                                          .fold(JdbcPgConnector::failQuery, JdbcPgConnector::firstLong);
        }

        private String scalarString(String sql) {
            return queryList(sql, row -> row.getString("s")).await()
                                                            .fold(JdbcPgConnector::failQueryString, JdbcPgConnector::firstString);
        }

        private static long failQuery(Cause cause) {
            throw new IllegalStateException("count query failed: " + cause.message());
        }

        private static String failQueryString(Cause cause) {
            throw new IllegalStateException("string query failed: " + cause.message());
        }

        private static long firstLong(List<Long> values) {
            return values.stream().findFirst().orElse(0L);
        }

        private static String firstString(List<String> values) {
            return values.stream().findFirst().orElse("");
        }

        private static void throwSetup(Cause cause) {
            throw new IllegalStateException("setup statement failed: " + cause.message());
        }

        private Result<Integer> runUpdate(String sql, Object[] params) {
            return bound.fold(() -> onNewConnection(connection -> updateOn(connection, sql, params)),
                              connection -> Result.lift(Causes::fromThrowable, () -> updateOn(connection, sql, params)));
        }

        private <T> Result<List<T>> runQuery(String sql, RowMapper<T> mapper, Object[] params) {
            return bound.fold(() -> onNewConnection(connection -> queryOn(connection, sql, mapper, params)),
                              connection -> Result.lift(Causes::fromThrowable, () -> queryOn(connection, sql, mapper, params)));
        }

        private Connection openConnection() throws Exception {
            return DriverManager.getConnection(url, user, password);
        }

        private <T> Result<T> onNewConnection(ConnectionFn<T> action) {
            return Result.lift(Causes::fromThrowable, () -> applyOnNewConnection(action));
        }

        private <T> T applyOnNewConnection(ConnectionFn<T> action) throws Exception {
            try (var connection = openConnection()) {
                return action.apply(connection);
            }
        }

        private static Integer updateOn(Connection connection, String sql, Object[] params) throws Exception {
            try (var statement = connection.prepareStatement(sql)) {
                bindParams(statement, params);
                return statement.executeUpdate();
            }
        }

        private static <T> List<T> queryOn(Connection connection, String sql, RowMapper<T> mapper, Object[] params) throws Exception {
            try (var statement = connection.prepareStatement(sql)) {
                bindParams(statement, params);
                return mapAll(statement, mapper);
            }
        }

        private static <T> List<T> mapAll(PreparedStatement statement, RowMapper<T> mapper) throws Exception {
            try (var rs = statement.executeQuery()) {
                var out = new ArrayList<T>();

                while (rs.next()) {
                    mapper.map(new ResultSetRow(rs)).onSuccess(out::add).onFailure(JdbcPgConnector::throwMappingFailure);
                }

                return out;
            }
        }

        private static void bindParams(PreparedStatement statement, Object[] params) throws Exception {
            for (var i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
        }

        private static <T> Option<T> firstOf(List<T> rows) {
            return rows.isEmpty() ? none() : some(rows.getFirst());
        }

        private static <T> Promise<T> requireOne(Option<T> opt) {
            return opt.async(Causes.cause("no row"));
        }

        private static void throwMappingFailure(Cause cause) {
            throw new IllegalStateException(cause.message());
        }

        private static DatabaseConnectorConfig pgConfig() {
            return new DatabaseConnectorConfig(none(),
                                               some(DatabaseType.POSTGRESQL),
                                               some("localhost"),
                                               none(),
                                               some("test"),
                                               none(),
                                               none(),
                                               PoolConfig.DEFAULT,
                                               Map.of(),
                                               none(),
                                               none(),
                                               none());
        }

        @FunctionalInterface
        private interface ConnectionFn<T> {
            T apply(Connection connection) throws Exception;
        }
    }

    private record ResultSetRow(ResultSet rs) implements RowAccessor {
        @Override
        public Result<String> getString(String column) {
            return Result.lift(Causes::fromThrowable, () -> rs.getString(column));
        }

        @Override
        public Result<Integer> getInt(String column) {
            return Result.lift(Causes::fromThrowable, () -> rs.getInt(column));
        }

        @Override
        public Result<Long> getLong(String column) {
            return Result.lift(Causes::fromThrowable, () -> rs.getLong(column));
        }

        @Override
        public Result<Double> getDouble(String column) {
            return Result.lift(Causes::fromThrowable, () -> rs.getDouble(column));
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return Result.lift(Causes::fromThrowable, () -> rs.getBoolean(column));
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return Result.lift(Causes::fromThrowable, () -> rs.getBytes(column));
        }

        @Override
        public <V> Result<V> getObject(String column, Class<V> type) {
            return Result.lift(Causes::fromThrowable, () -> rs.getObject(column, type));
        }
    }
}
