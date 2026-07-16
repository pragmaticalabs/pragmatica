// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import static org.pragmatica.aether.slice.blueprint.MigrationEntry.migrationEntry;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Real-PostgreSQL proof of Increment A (#338): the migration DDL and its schema-history row are
/// one atomic unit. The connector below runs migrations against a real PostgreSQL engine with a
/// genuine `transactional` that COMMITs on success and ROLLs BACK on any failure — exactly the
/// contract a production JDBC connector provides.
///
/// - [AtomicRollback#migrate_rollsBackDdlAndHistory_whenLastStatementFails] proves the atomicity
///   fix: a migration whose LAST statement errors leaves NEITHER the earlier `CREATE TABLE` NOR a
///   history row — both were rolled back together.
/// - [AtomicCommit#migrate_persistsDdlAndHistory_whenSuccessful] proves the happy path: a
///   successful migration leaves BOTH the created table and its history row.
///
/// Self-skips via [org.junit.jupiter.api.Assumptions#assumeTrue] when no Docker daemon is
/// reachable, so it never breaks a Docker-less build.
class AetherSchemaManagerPgAtomicityTest {
    private static final SchemaPolicy POLICY = SchemaPolicy.schemaPolicy();
    private static final String NODE_ID = "node-1";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainer() {
        assumeTrue(isDockerAvailable(), "Docker is not available - skipping real-PostgreSQL atomicity test");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Nested
    class AtomicRollback {

        /// A migration whose final statement is invalid: the first statement creates a table, the
        /// second references a non-existent column and errors. Because the DDL and the history
        /// INSERT share one transaction, the failure rolls BOTH back — the table must not exist
        /// and no history row may remain.
        @Test
        void migrate_rollsBackDdlAndHistory_whenLastStatementFails() {
            var connector = newConnector();
            var sql = """
                      CREATE TABLE atomic_rollback (id INT);
                      INSERT INTO atomic_rollback (no_such_column) VALUES (1);
                      """;

            schemaManager().migrate("ds", List.of(migrationEntry("V1__rollback.sql", sql, 1L)), connector, NODE_ID)
                           .await()
                           .onSuccess(_ -> fail("Expected migration to fail on the invalid last statement"));

            assertThat(tableExists(connector, "atomic_rollback")).isFalse();
            assertThat(historyRowCount(connector, 1)).isZero();
        }
    }

    @Nested
    class AtomicCommit {

        /// A successful migration leaves BOTH the created table and its single history row — the
        /// DDL and history committed together.
        @Test
        void migrate_persistsDdlAndHistory_whenSuccessful() {
            var connector = newConnector();
            var sql = """
                      CREATE TABLE atomic_commit (id INT);
                      """;

            schemaManager().migrate("ds", List.of(migrationEntry("V2__commit.sql", sql, 2L)), connector, NODE_ID)
                           .await()
                           .onFailure(cause -> fail("Migration failed: " + cause.message()));

            assertThat(tableExists(connector, "atomic_commit")).isTrue();
            assertThat(historyRowCount(connector, 2)).isEqualTo(1);
        }
    }

    private static AetherSchemaManager schemaManager() {
        return AetherSchemaManager.aetherSchemaManager(POLICY);
    }

    private static JdbcPgConnector newConnector() {
        return new JdbcPgConnector(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), none());
    }

    private static boolean tableExists(JdbcPgConnector connector, String table) {
        return connector.scalarCount("SELECT count(*) AS c FROM information_schema.tables WHERE table_name = ?", table) > 0;
    }

    private static long historyRowCount(JdbcPgConnector connector, int version) {
        return connector.scalarCount("SELECT count(*) AS c FROM aether_schema_history WHERE version = ?", version);
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /// Minimal real-PostgreSQL [SqlConnector] for the atomicity test. Each operation runs against a
    /// freshly borrowed JDBC connection in autocommit, EXCEPT when the connector is bound to a
    /// transaction connection (`bound` is present), in which case it reuses that connection.
    /// `transactional(...)` borrows one connection, disables autocommit, runs the callback on a
    /// bound connector, then COMMITs on success or ROLLs BACK on failure — the genuine atomic
    /// semantics the fix relies on.
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
            return Causes.cause("batch not supported in atomicity test").promise();
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return runTransaction(callback).async();
        }

        private long scalarCount(String sql, Object param) {
            return queryList(sql, row -> row.getLong("c"), param).await()
                                                                 .fold(JdbcPgConnector::failQuery, JdbcPgConnector::firstCount);
        }

        private static long failQuery(Cause cause) {
            throw new IllegalStateException("count query failed: " + cause.message());
        }

        private static long firstCount(List<Long> counts) {
            return counts.stream().findFirst().orElse(0L);
        }

        private Result<Integer> runUpdate(String sql, Object[] params) {
            return bound.fold(() -> onNewConnection(connection -> updateOn(connection, sql, params)),
                              connection -> Result.lift(Causes::fromThrowable, () -> updateOn(connection, sql, params)));
        }

        private <T> Result<List<T>> runQuery(String sql, RowMapper<T> mapper, Object[] params) {
            return bound.fold(() -> onNewConnection(connection -> queryOn(connection, sql, mapper, params)),
                              connection -> Result.lift(Causes::fromThrowable, () -> queryOn(connection, sql, mapper, params)));
        }

        /// Runs the transaction synchronously: borrow a connection, disable autocommit, run the
        /// callback on a bound connector, then commit on success or rollback on failure. The
        /// callback's [Promise] resolves synchronously here (the connector is non-blocking), so
        /// awaiting it to learn commit-vs-rollback is safe.
        private <T> Result<T> runTransaction(TransactionCallback<T> callback) {
            try (var connection = openConnection()) {
                connection.setAutoCommit(false);
                var txConnector = new JdbcPgConnector(url, user, password, some(connection));

                return callback.execute(txConnector).await().fold(cause -> rollback(connection, cause),
                                                                  value -> commit(connection, value));
            } catch (Exception e) {
                return Causes.fromThrowable(e).result();
            }
        }

        private static <T> Result<T> commit(Connection connection, T value) {
            return Result.lift(Causes::fromThrowable, () -> doCommit(connection, value));
        }

        private static <T> T doCommit(Connection connection, T value) throws Exception {
            connection.commit();
            return value;
        }

        private static <T> Result<T> rollback(Connection connection, Cause cause) {
            return Result.lift(Causes::fromThrowable, () -> doRollback(connection)).flatMap(_ -> cause.result());
        }

        private static Object doRollback(Connection connection) throws Exception {
            connection.rollback();
            return Boolean.TRUE;
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
