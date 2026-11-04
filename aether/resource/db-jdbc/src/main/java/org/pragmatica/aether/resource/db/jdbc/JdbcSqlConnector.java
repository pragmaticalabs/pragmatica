// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.PinnedSessionBound;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


@SuppressWarnings("JBCT-EX-01")
public final class JdbcSqlConnector implements SqlConnector {
    private static final String TRANSACTION = "TRANSACTION";
    private static final String PINNED = "PINNED SESSION";
    private static final System.Logger LOG = System.getLogger(JdbcSqlConnector.class.getName());

    private final DatabaseConnectorConfig config;
    private final DataSource dataSource;

    private JdbcSqlConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        this.config = config;
        this.dataSource = dataSource;
    }

    public static JdbcSqlConnector jdbcSqlConnector(DatabaseConnectorConfig config, DataSource dataSource) {
        return new JdbcSqlConnector(config, dataSource);
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runQueryOne(sql, params, mapper)).flatMap(Result::async);
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runQueryOptional(sql, params, mapper)).flatMap(Result::async);
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runQueryList(sql, params, mapper)).flatMap(Result::async);
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return Promise.lift(e -> mapException(e, sql), () -> runUpdate(sql, params));
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return Promise.lift(e -> mapException(e, sql), () -> runBatch(sql, paramsList));
    }

    @Override
    public <T> Promise<T> transactional(SqlConnector.TransactionCallback<T> callback) {
        return Promise.lift(e -> mapException(e, TRANSACTION), () -> runTransaction(callback)).flatMap(Result::async);
    }

    @Override
    public <T> Promise<T> pinned(Duration statementTimeout,
                                 SqlConnector.TransactionCallback<T> body,
                                 SqlConnector.TransactionCallback<Unit> release) {
        // The client-side query timeout cancels a hung statement; the server-side bounds additionally replace a role or
        // database default `lock_timeout`/`statement_timeout` on the migration session (PinnedSessionBound).
        return Promise.lift(e -> mapException(e, PINNED), this::openDedicated).flatMap(conn -> runPinned(conn,
                                                                                                         pinnedSession(conn,
                                                                                                                       statementTimeout),
                                                                                                         PinnedSessionBound.serverBounded(statementTimeout,
                                                                                                                                          body),
                                                                                                         release));
    }

    @Override
    public DatabaseConnectorConfig config() {
        return config;
    }

    @Override
    public DatabaseType databaseType() {
        return config.effectiveType(DatabaseConnectorConfig.Transport.JDBC);
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.lift(DatabaseConnectorError::databaseFailure, this::checkHealth).recover(_ -> false);
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.lift(DatabaseConnectorError::databaseFailure, this::closeDataSource);
    }

    private <T> Result<T> runQueryOne(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = buildStatement(conn, sql, params);
             var rs = stmt.executeQuery()) {
            return mapSingleRow(rs, mapper);
        }
    }

    private <T> Result<Option<T>> runQueryOptional(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = buildStatement(conn, sql, params);
             var rs = stmt.executeQuery()) {
            return mapOptionalRow(rs, mapper);
        }
    }

    private <T> Result<List<T>> runQueryList(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = buildStatement(conn, sql, params);
             var rs = stmt.executeQuery()) {
            return mapAllRows(rs, mapper);
        }
    }

    private int runUpdate(String sql, Object[] params) throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = buildStatement(conn, sql, params)) {
            return stmt.executeUpdate();
        }
    }

    private int[] runBatch(String sql, List<Object[]> paramsList) throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.prepareStatement(sql)) {
            for (var params : paramsList) {
                applyParameters(stmt, params);
                stmt.addBatch();
            }

            return stmt.executeBatch();
        }
    }

    private <T> Result<T> runTransaction(SqlConnector.TransactionCallback<T> callback) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                var transactionalConnector = new TransactionalJdbcConnector(config,
                                                                            conn,
                                                                            (int) config.poolConfig()
                                                                                        .connectionTimeout()
                                                                                        .toSeconds());
                var result = callback.execute(transactionalConnector).await();

                return resolveTransaction(conn, result);
            } catch (Exception e) {
                rollbackSilently(conn);

                throw e;
            }
        }
    }

    /// A DEDICATED physical connection from the driver, never from the Hikari pool: closing it ends the server session,
    /// and with it every session-scoped lock, with no pool in between to keep it alive.
    private Connection openDedicated() throws SQLException {
        var properties = new Properties();

        config.effectiveUsername(DatabaseConnectorConfig.Transport.JDBC)
              .onPresent(u -> properties.setProperty("user", u));
        config.effectivePassword(DatabaseConnectorConfig.Transport.JDBC)
              .onPresent(p -> properties.setProperty("password", p));
        var conn = DriverManager.getConnection(config.effectiveJdbcUrl(), properties);

        try {
            conn.setAutoCommit(true);

            return conn;
        } catch (SQLException e) {
            closeQuietly(conn);

            throw e;
        }
    }

    private SqlConnector pinnedSession(Connection conn, Duration statementTimeout) {
        var statements = new TransactionalJdbcConnector(config, conn, queryTimeoutSeconds(statementTimeout));

        return new PinnedJdbcConnector(statements, conn);
    }

    private static int queryTimeoutSeconds(Duration statementTimeout) {
        return statementTimeout.isZero() || statementTimeout.isNegative()
               ? 0
               : (int) Math.min(Integer.MAX_VALUE,
                                Math.max(1, statementTimeout.toSeconds()));
    }

    private <T> Promise<T> runPinned(Connection conn,
                                     SqlConnector session,
                                     SqlConnector.TransactionCallback<T> body,
                                     SqlConnector.TransactionCallback<Unit> release) {
        return invoke(body, session).fold(bodyResult -> releaseThenClose(conn, session, release, bodyResult));
    }

    private <T> Promise<T> releaseThenClose(Connection conn,
                                            SqlConnector session,
                                            SqlConnector.TransactionCallback<Unit> release,
                                            Result<T> bodyResult) {
        return invoke(release, session).fold(_ -> closeAndResolve(conn, bodyResult));
    }

    private static <R> Promise<R> invoke(SqlConnector.TransactionCallback<R> callback, SqlConnector session) {
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> callback.execute(session))
                      .flatMap(promise -> promise);
    }

    // Close is the release (design 2.1 rule 2): closing the dedicated connection ends the server session, which drops
    // every session-scoped lock. The explicit `release` step is a courtesy that ran just before; its outcome changes
    // nothing here, so a failure of it is absorbed (design-out: no pool exists to carry the session on). Chained to
    // the INNER promise, so an outer timeout leaves the session, and its fence, open until the statement ends.
    private <T> Promise<T> closeAndResolve(Connection conn, Result<T> bodyResult) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> closeQuietly(conn)).fold(_ -> Promise.resolved(bodyResult));
    }

    private static Unit closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Pinned session close failed", e);
        }

        return unit();
    }

    private <T> Result<T> resolveTransaction(Connection conn, Result<T> result) {
        return result.fold(cause -> rollbackAndFail(conn, cause), value -> commitAndReturn(conn, value));
    }

    private <T> Result<T> rollbackAndFail(Connection conn, Cause cause) {
        rollbackSilently(conn);

        return cause.result();
    }

    private <T> Result<T> commitAndReturn(Connection conn, T value) {
        return Result.lift(e -> mapException(e, "COMMIT"), conn::commit).map(_ -> value);
    }

    private PreparedStatement buildStatement(Connection conn, String sql, Object[] params) throws SQLException {
        var stmt = conn.prepareStatement(sql);

        stmt.setQueryTimeout((int) config.poolConfig().connectionTimeout().toSeconds());
        applyParameters(stmt, params);

        return stmt;
    }

    private void applyParameters(PreparedStatement stmt, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            org.pragmatica.jdbc.JdbcParameters.bind(stmt, i + 1, params[i]);
        }
    }

    private static <T> Result<T> mapSingleRow(ResultSet rs, RowMapper<T> mapper) throws SQLException {
        if (!rs.next()) {
            return DatabaseConnectorError.ResultNotFound.INSTANCE.result();
        }

        var result = mapper.map(new JdbcRowAccessor(rs));

        if (rs.next()) {
            return DatabaseConnectorError.multipleResults(countRemaining(rs) + 2).result();
        }

        return result;
    }

    private static <T> Result<Option<T>> mapOptionalRow(ResultSet rs, RowMapper<T> mapper) throws SQLException {
        if (!rs.next()) {
            return Result.success(none());
        }

        return mapper.map(new JdbcRowAccessor(rs))
                     .map(Option::some);
    }

    private static <T> Result<List<T>> mapAllRows(ResultSet rs, RowMapper<T> mapper) throws SQLException {
        var results = new ArrayList<Result<T>>();
        var accessor = new JdbcRowAccessor(rs);

        while (rs.next()) {
            results.add(mapper.map(accessor));
        }

        return Result.firstFailureOf(results);
    }

    private static int countRemaining(ResultSet rs) throws SQLException {
        int count = 0;

        while (rs.next()) {
            count++;
        }

        return count;
    }

    private Unit rollbackSilently(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.DEBUG, "Rollback failed", e);
        }

        return unit();
    }

    private boolean checkHealth() throws Exception {
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(5);
        }
    }

    private Unit closeDataSource() throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }

        return unit();
    }

    private static DatabaseConnectorError mapException(Throwable throwable, String sql) {
        return switch (throwable) {
            case SQLTimeoutException _ -> DatabaseConnectorError.timeout(sql);
            case SQLIntegrityConstraintViolationException e -> DatabaseConnectorError.constraintViolation("unknown",
                                                                                                          e.getMessage(),
                                                                                                          option(e.getSQLState()));
            case SQLTransactionRollbackException e -> DatabaseConnectorError.transactionRollback(e.getMessage(),
                                                                                                 option(e.getSQLState()));
            case SQLException e -> DatabaseConnectorError.fromSqlState(option(e.getSQLState()),
                                                                       sql,
                                                                       e.getMessage(),
                                                                       some(e));
            default -> DatabaseConnectorError.databaseFailure(throwable);
        };
    }

    /// The pinned session: statements run on the delegate (autocommit, bounded by the pinned timeout), and
    /// `transactional` begins and commits on the SAME connection instead of borrowing another.
    private record PinnedJdbcConnector(TransactionalJdbcConnector statements, Connection conn) implements SqlConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return statements.queryOne(sql, mapper, params);
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return statements.queryOptional(sql, mapper, params);
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return statements.queryList(sql, mapper, params);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return statements.update(sql, params);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return statements.batch(sql, paramsList);
        }

        @Override
        public <T> Promise<T> transactional(SqlConnector.TransactionCallback<T> callback) {
            return Promise.lift(e -> mapException(e, TRANSACTION), () -> runPinnedTransaction(callback)).flatMap(Result::async);
        }

        @Override
        public DatabaseConnectorConfig config() {
            return statements.config();
        }

        @Override
        public DatabaseType databaseType() {
            return config().effectiveType(DatabaseConnectorConfig.Transport.JDBC);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return statements.isHealthy();
        }

        private <T> Result<T> runPinnedTransaction(SqlConnector.TransactionCallback<T> callback) throws Exception {
            conn.setAutoCommit(false);
            try {
                return resolvePinnedTransaction(callback.execute(statements).await());
            } catch (Exception e) {
                rollbackQuietly();

                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        private <T> Result<T> resolvePinnedTransaction(Result<T> result) {
            return result.fold(this::rollbackAndFail, this::commitAndReturn);
        }

        private <T> Result<T> rollbackAndFail(Cause cause) {
            rollbackQuietly();

            return cause.result();
        }

        private <T> Result<T> commitAndReturn(T value) {
            return Result.lift(e -> mapException(e, "COMMIT"), conn::commit).map(_ -> value);
        }

        private void rollbackQuietly() {
            try {
                conn.rollback();
            } catch (SQLException e) {
                LOG.log(System.Logger.Level.DEBUG, "Rollback failed", e);
            }
        }
    }

    private record TransactionalJdbcConnector(DatabaseConnectorConfig config,
                                              Connection conn,
                                              int queryTimeoutSeconds) implements SqlConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txQueryOne(sql, params, mapper)).flatMap(Result::async);
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txQueryOptional(sql, params, mapper)).flatMap(Result::async);
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txQueryList(sql, params, mapper)).flatMap(Result::async);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return Promise.lift(e -> mapException(e, sql), () -> txUpdate(sql, params));
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.lift(e -> mapException(e, sql), () -> txBatch(sql, paramsList));
        }

        @Override
        public <T> Promise<T> transactional(SqlConnector.TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.lift(DatabaseConnectorError::databaseFailure,
                                () -> conn.isValid(5))
                          .recover(_ -> false);
        }

        private <T> Result<T> txQueryOne(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
            try (var stmt = txBuildStatement(sql, params); var rs = stmt.executeQuery()) {
                return mapSingleRow(rs, mapper);
            }
        }

        private <T> Result<Option<T>> txQueryOptional(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
            try (var stmt = txBuildStatement(sql, params); var rs = stmt.executeQuery()) {
                return mapOptionalRow(rs, mapper);
            }
        }

        private <T> Result<List<T>> txQueryList(String sql, Object[] params, RowMapper<T> mapper) throws Exception {
            try (var stmt = txBuildStatement(sql, params); var rs = stmt.executeQuery()) {
                return mapAllRows(rs, mapper);
            }
        }

        private int txUpdate(String sql, Object[] params) throws Exception {
            try (var stmt = txBuildStatement(sql, params)) {
                return stmt.executeUpdate();
            }
        }

        private int[] txBatch(String sql, List<Object[]> paramsList) throws Exception {
            try (var stmt = conn.prepareStatement(sql)) {
                for (var params : paramsList) {
                    txApplyParameters(stmt, params);
                    stmt.addBatch();
                }

                return stmt.executeBatch();
            }
        }

        private PreparedStatement txBuildStatement(String sql, Object[] params) throws SQLException {
            var stmt = conn.prepareStatement(sql);

            stmt.setQueryTimeout(queryTimeoutSeconds);
            txApplyParameters(stmt, params);

            return stmt;
        }

        private void txApplyParameters(PreparedStatement stmt, Object[] params) throws SQLException {
            for (int i = 0; i < params.length; i++) {
                org.pragmatica.jdbc.JdbcParameters.bind(stmt, i + 1, params[i]);
            }
        }
    }
}
