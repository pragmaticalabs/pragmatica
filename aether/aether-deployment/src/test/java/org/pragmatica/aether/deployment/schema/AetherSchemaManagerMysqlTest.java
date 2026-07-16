// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.MigrationStatus;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

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

/// Real-MySQL proof of Increment B (#338) on the canonical AUTOCOMMIT dialect. MySQL's DDL
/// self-commits (`ddlTransactional=false` in [MigrationDialects#MYSQL]), so it exercises the
/// checkpoint/resume path that PostgreSQL's transactional path never touches — making MySQL the
/// highest-value non-PostgreSQL validation for the B2 autocommit-checkpoint feature. The connector
/// below runs migrations against a real MySQL engine: each operation borrows a fresh autocommit JDBC
/// connection, and `transactional(...)` borrows one connection and commits/rolls back (MySQL DDL
/// won't actually roll back, which is exactly why the autocommit checkpoint path exists).
///
/// - [Evolution] proves the B1 history-table self-evolution reaches internal version 2 on real MySQL,
///   the `status`/`statements_completed` columns present with their `SUCCESS`/`0` NOT-NULL defaults
///   (fresh bootstrap AND existing-cluster upgrade with a pre-existing row preserved).
/// - [AutocommitLifecycle] proves a clean multi-statement migration runs through the autocommit path,
///   finalizing the history row `SUCCESS` at `statements_completed = N` with all DDL objects created.
/// - [Resume] proves a partially-applied migration RESUMES: a seeded `IN_PROGRESS` checkpoint at
///   `statements_completed = K` makes the versioned gate skip 1..K, apply K+1..N, and finalize
///   `SUCCESS` at `statements_completed = N`.
///
/// Tagged [@Tag] `heavy-db` so it stays OUT of the default build (it needs a real MySQL container and
/// amd64); the untagged PostgreSQL tests still run by default. Self-skips via
/// [org.junit.jupiter.api.Assumptions#assumeTrue] when no Docker daemon is reachable.
@Tag("heavy-db")
@Testcontainers
class AetherSchemaManagerMysqlTest {
    private static final SchemaPolicy POLICY = SchemaPolicy.schemaPolicy();
    private static final String NODE_ID = "node-1";
    private static final long CHECKSUM = 7L;

    private static final String THREE_STATEMENTS = """
        CREATE TABLE mysql_clean_a (id INT);
        CREATE TABLE mysql_clean_b (id INT);
        CREATE TABLE mysql_clean_c (id INT);
        """;

    private static final String RESUME_THREE_STATEMENTS = """
        CREATE TABLE mysql_resume_a (id INT);
        CREATE TABLE mysql_resume_b (id INT);
        CREATE TABLE mysql_resume_c (id INT);
        """;

    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startContainer() {
        assumeTrue(isDockerAvailable(), "Docker is not available - skipping real-MySQL migration test");
        mysql = new MySQLContainer<>("mysql:8.0");
        mysql.start();
    }

    @AfterAll
    static void stopContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void cleanSchema() {
        var connector = newConnector();
        connector.execUpdate("DROP TABLE IF EXISTS aether_schema_history");
        connector.execUpdate("DROP TABLE IF EXISTS aether_schema_history_meta");
        dropMigrationTables(connector);
    }

    @Nested
    class Evolution {

        /// A fresh bootstrap reaches internal version 2 and creates the history table with both new
        /// columns carrying their B2 defaults — MySQL stores `COLUMN_DEFAULT` for a string literal
        /// UNQUOTED, so the default reads back as bare `SUCCESS` and `0`.
        @Test
        void bootstrap_reachesVersion2WithDefaultedColumns_forFreshCluster() {
            var connector = newConnector();

            repository().bootstrap(connector).await().onFailure(cause -> fail("bootstrap failed: " + cause.message()));

            assertThat(connector.metaVersion()).isEqualTo(2);
            assertThat(connector.columnDefault("status")).isEqualTo("SUCCESS");
            assertThat(connector.columnDefault("statements_completed")).isEqualTo("0");
            assertThat(connector.columnNotNull("status")).isTrue();
            assertThat(connector.columnNotNull("statements_completed")).isTrue();
        }

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
    class AutocommitLifecycle {

        /// A clean 3-statement MySQL migration runs through the autocommit checkpoint path: the row
        /// finalizes `SUCCESS` at `statements_completed = 3`, and all three created tables exist —
        /// proving the recordInProgress → updateProgress → finalizeSuccess SQL is valid MySQL.
        @Test
        void migrate_finalizesSuccessAtStatementCount_forCleanMysqlRun() {
            var connector = newConnector();

            schemaManager().migrate("ds", List.of(migrationEntry("V1__clean.sql", THREE_STATEMENTS, CHECKSUM)), connector, NODE_ID)
                           .await()
                           .onFailure(cause -> fail("Migration failed: " + cause.message()));

            assertThat(connector.scalarString("SELECT status AS s FROM aether_schema_history WHERE version = 1")).isEqualTo("SUCCESS");
            assertThat(connector.scalarLong("SELECT statements_completed AS c FROM aether_schema_history WHERE version = 1")).isEqualTo(3);
            assertThat(connector.tableExists("mysql_clean_a")).isTrue();
            assertThat(connector.tableExists("mysql_clean_b")).isTrue();
            assertThat(connector.tableExists("mysql_clean_c")).isTrue();
        }
    }

    @Nested
    class Resume {

        /// A partially-applied migration RESUMES: statements 1..K are applied manually and an
        /// `IN_PROGRESS` checkpoint at `statements_completed = K` is seeded with the SAME checksum the
        /// migration carries (so the resume gate's checksum re-validation passes). Invoking `migrate`
        /// then skips 1..K, applies K+1..N, and finalizes `SUCCESS` at `statements_completed = N` —
        /// the boundary statement re-runs idempotency-free here because only K+1..N are applied.
        @Test
        void migrate_resumesFromCheckpoint_forPartiallyAppliedMysqlMigration() {
            var connector = newConnector();
            repository().bootstrap(connector).await().onFailure(cause -> fail("bootstrap failed: " + cause.message()));
            connector.execUpdate("CREATE TABLE mysql_resume_a (id INT)");
            seedInProgressCheckpoint(connector, 1, 1);

            schemaManager().migrate("ds", List.of(migrationEntry("V1__resume.sql", RESUME_THREE_STATEMENTS, CHECKSUM)), connector, NODE_ID)
                           .await()
                           .onFailure(cause -> fail("Migration failed: " + cause.message()));

            assertThat(connector.scalarString("SELECT status AS s FROM aether_schema_history WHERE version = 1")).isEqualTo("SUCCESS");
            assertThat(connector.scalarLong("SELECT statements_completed AS c FROM aether_schema_history WHERE version = 1")).isEqualTo(3);
            assertThat(connector.tableExists("mysql_resume_a")).isTrue();
            assertThat(connector.tableExists("mysql_resume_b")).isTrue();
            assertThat(connector.tableExists("mysql_resume_c")).isTrue();
        }
    }

    /// Seeds an `IN_PROGRESS` checkpoint row for a versioned migration exactly as
    /// [SchemaHistoryRepository#recordInProgress] + [SchemaHistoryRepository#updateProgress] would
    /// have left it after a partial run: the stored `checksum` matches [#CHECKSUM] so the resume
    /// gate's re-validation passes, and `statements_completed = completed` is where the resume picks
    /// up. Inserted directly against the evolved 10-column history table.
    private static void seedInProgressCheckpoint(JdbcMysqlConnector connector, int version, int completed) {
        connector.execUpdate("INSERT INTO aether_schema_history "
                             + "(version, type, description, script, checksum, applied_by, applied_at, execution_ms, status, statements_completed) "
                             + "VALUES (" + version + ", 'VERSIONED', 'resume', 'V1__resume.sql', " + CHECKSUM
                             + ", '" + NODE_ID + "', 0, 0, '" + MigrationStatus.IN_PROGRESS + "', " + completed + ")");
    }

    private static void dropMigrationTables(JdbcMysqlConnector connector) {
        connector.execUpdate("DROP TABLE IF EXISTS mysql_clean_a");
        connector.execUpdate("DROP TABLE IF EXISTS mysql_clean_b");
        connector.execUpdate("DROP TABLE IF EXISTS mysql_clean_c");
        connector.execUpdate("DROP TABLE IF EXISTS mysql_resume_a");
        connector.execUpdate("DROP TABLE IF EXISTS mysql_resume_b");
        connector.execUpdate("DROP TABLE IF EXISTS mysql_resume_c");
    }

    private static AetherSchemaManager schemaManager() {
        return AetherSchemaManager.aetherSchemaManager(POLICY);
    }

    private static SchemaHistoryRepository repository() {
        return SchemaHistoryRepository.schemaHistoryRepository();
    }

    private static JdbcMysqlConnector newConnector() {
        return new JdbcMysqlConnector(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), mysql.getDatabaseName(), none());
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /// Minimal real-MySQL [SqlConnector] for the migration test, plus helper probes that inspect the
    /// evolved table via MySQL `information_schema` (scoped to the container's single test database via
    /// `table_schema = ?`, since `information_schema` is shared across schemas). Each operation runs
    /// against a freshly borrowed JDBC connection in autocommit, EXCEPT when the connector is bound to
    /// a transaction connection (`bound` is present), in which case it reuses that connection.
    /// `transactional(...)` borrows one connection, disables autocommit, runs the callback on a bound
    /// connector, then commits on success or rolls back on failure — MySQL DDL self-commits so the
    /// rollback is a no-op for DDL, which is exactly why the autocommit checkpoint path exists.
    static final class JdbcMysqlConnector implements SqlConnector {
        private final String url;
        private final String user;
        private final String password;
        private final String database;
        private final Option<Connection> bound;
        private final DatabaseConnectorConfig config;

        JdbcMysqlConnector(String url, String user, String password, String database, Option<Connection> bound) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.database = database;
            this.bound = bound;
            this.config = mysqlConfig();
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
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
            return queryList(sql, mapper, params).map(JdbcMysqlConnector::firstOf);
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return queryOptional(sql, mapper, params).flatMap(JdbcMysqlConnector::requireOne);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Causes.cause("batch not supported in MySQL migration test").promise();
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return runTransaction(callback).async();
        }

        private void execUpdate(String sql) {
            update(sql).await().onFailure(JdbcMysqlConnector::throwSetup);
        }

        private int metaVersion() {
            return (int) scalarLong("SELECT schema_version AS c FROM aether_schema_history_meta");
        }

        private boolean tableExists(String table) {
            return scalarLong("SELECT count(*) AS c FROM information_schema.tables "
                              + "WHERE table_schema = '" + database + "' AND table_name = '" + table + "'") > 0;
        }

        private boolean hasColumn(String column) {
            return scalarLong("SELECT count(*) AS c FROM information_schema.columns "
                              + "WHERE table_schema = '" + database + "' AND table_name = 'aether_schema_history' "
                              + "AND column_name = '" + column + "'") > 0;
        }

        private String columnDefault(String column) {
            return scalarString("SELECT column_default AS s FROM information_schema.columns "
                                + "WHERE table_schema = '" + database + "' AND table_name = 'aether_schema_history' "
                                + "AND column_name = '" + column + "'");
        }

        private boolean columnNotNull(String column) {
            return "NO".equals(scalarString("SELECT is_nullable AS s FROM information_schema.columns "
                                            + "WHERE table_schema = '" + database + "' AND table_name = 'aether_schema_history' "
                                            + "AND column_name = '" + column + "'"));
        }

        private long scalarLong(String sql) {
            return queryList(sql, row -> row.getLong("c")).await()
                                                          .fold(JdbcMysqlConnector::failQuery, JdbcMysqlConnector::firstLong);
        }

        private String scalarString(String sql) {
            return queryList(sql, row -> row.getString("s")).await()
                                                            .fold(JdbcMysqlConnector::failQueryString, JdbcMysqlConnector::firstString);
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

        /// Runs the transaction synchronously: borrow a connection, disable autocommit, run the
        /// callback on a bound connector, then commit on success or rollback on failure. MySQL DDL
        /// self-commits, so a rollback is a no-op for the DDL itself — the connector still honors the
        /// contract a production JDBC connector provides. The callback's [Promise] resolves
        /// synchronously here, so awaiting it to learn commit-vs-rollback is safe.
        private <T> Result<T> runTransaction(TransactionCallback<T> callback) {
            try (var connection = openConnection()) {
                connection.setAutoCommit(false);
                var txConnector = new JdbcMysqlConnector(url, user, password, database, some(connection));

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
                    mapper.map(new ResultSetRow(rs)).onSuccess(out::add).onFailure(JdbcMysqlConnector::throwMappingFailure);
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

        private static DatabaseConnectorConfig mysqlConfig() {
            return new DatabaseConnectorConfig(none(),
                                               some(DatabaseType.MYSQL),
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
