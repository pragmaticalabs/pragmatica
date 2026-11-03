// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Cause;
import org.pragmatica.postgres.PgConnection;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.Listening;
import org.pragmatica.postgres.net.Transaction;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


final class PgAsyncSqlConnector implements AsyncSqlConnector {
    private static final System.Logger LOG = System.getLogger(PgAsyncSqlConnector.class.getName());

    private final DatabaseConnectorConfig config;
    private final Connectible connectible;

    private PgAsyncSqlConnector(DatabaseConnectorConfig config, Connectible connectible) {
        this.config = config;
        this.connectible = connectible;
    }

    static PgAsyncSqlConnector pgAsyncSqlConnector(DatabaseConnectorConfig config, Connectible connectible) {
        return new PgAsyncSqlConnector(config, connectible);
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .flatMap(rs -> extractSingleRow(rs, mapper, sql));
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .flatMap(rs -> extractOptionalRow(rs, mapper));
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .flatMap(rs -> extractAllRows(rs, mapper));
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return connectible.completeQuery(convertPlaceholders(sql),
                                         params)
                          .map(PgResultSet::affectedRows);
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        if (paramsList.isEmpty()) {
            return Promise.success(new int[0]);
        }

        return connectible.getConnection()
                          .flatMap(conn -> executeBatch(conn,
                                                        convertPlaceholders(sql),
                                                        paramsList));
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return connectible.getConnection()
                          .flatMap(conn -> runTransaction(conn, callback));
    }

    /// postgres-async has no per-statement timeout, so `statementTimeout` is not enforced here: the bound is the
    /// caller's own promise timeout. A `TRANSACTION`-mode pool lends a physical connection per query, which cannot hold
    /// a session, so it is refused rather than silently unpinned.
    @Override
    public <T> Promise<T> pinned(Duration statementTimeout,
                                 TransactionCallback<T> body,
                                 TransactionCallback<Unit> release) {
        return connectible.getConnection()
                          .flatMap(conn -> runPinned(conn, config, body, release));
    }

    @Override
    public Promise<Listening> subscribe(String channel, Consumer<String> onNotification) {
        return connectible.getConnection()
                          .flatMap(conn -> conn.subscribe(channel, onNotification));
    }

    @Override
    public DatabaseConnectorConfig config() {
        return config;
    }

    @Override
    public DatabaseType databaseType() {
        return config.effectiveType(DatabaseConnectorConfig.Transport.ASYNC);
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return connectible.completeQuery("SELECT 1")
                          .map(_ -> true)
                          .recover(_ -> false);
    }

    @Override
    public Promise<Unit> stop() {
        return connectible.close();
    }

    private static <T> Promise<T> extractSingleRow(PgResultSet rs, RowMapper<T> mapper, String sql) {
        if (rs.size() == 0) {
            return DatabaseConnectorError.ResultNotFound.INSTANCE.promise();
        }

        if (rs.size() > 1) {
            return DatabaseConnectorError.multipleResults(rs.size()).promise();
        }

        return mapRow(rs.index(0), mapper);
    }

    private static <T> Promise<Option<T>> extractOptionalRow(PgResultSet rs, RowMapper<T> mapper) {
        if (rs.size() == 0) {
            return Promise.success(none());
        }

        return mapRow(rs.index(0), mapper).map(v -> some(v));
    }

    private static <T> Promise<List<T>> extractAllRows(PgResultSet rs, RowMapper<T> mapper) {
        var results = new ArrayList<Result<T>>(rs.size());

        for (var row : rs) {
            results.add(mapper.map(new PgRowAccessor(row)));
        }

        return Result.firstFailureOf(results).async();
    }

    private static <T> Promise<T> mapRow(PgRow row, RowMapper<T> mapper) {
        return mapper.map(new PgRowAccessor(row))
                     .async();
    }

    private static Promise<int[]> executeBatch(Connection conn, String sql, List<Object[]> paramsList) {
        return conn.begin()
                   .flatMap(tx -> executeBatchInTransaction(tx,
                                                            sql,
                                                            paramsList,
                                                            new int[paramsList.size()],
                                                            0));
    }

    private static Promise<int[]> executeBatchInTransaction(Transaction tx,
                                                            String sql,
                                                            List<Object[]> paramsList,
                                                            int[] counts,
                                                            int index) {
        if (index >= paramsList.size()) {
            return tx.commit()
                     .map(_ -> counts);
        }

        return tx.completeQuery(sql,
                                paramsList.get(index))
                 .map(rs -> recordAffectedRows(counts, index, rs))
                 .flatMap(_ -> executeBatchInTransaction(tx, sql, paramsList, counts, index + 1))
                 .fold(result -> rollbackOnFailure(tx, result));
    }

    private static int recordAffectedRows(int[] counts, int index, PgResultSet rs) {
        counts[index] = rs.affectedRows();

        return rs.affectedRows();
    }

    private static <T> Promise<T> rollbackOnFailure(Transaction tx, Result<T> result) {
        return result.fold(cause -> tx.rollback()
                                      .flatMap(_ -> cause.promise()),
                           Promise::success);
    }

    private <T> Promise<T> runTransaction(Connection conn, TransactionCallback<T> callback) {
        return runTransaction(conn, config, callback);
    }

    private static <T> Promise<T> runTransaction(Connection conn,
                                                 DatabaseConnectorConfig config,
                                                 TransactionCallback<T> callback) {
        return conn.begin()
                   .flatMap(tx -> executeInTransaction(tx, config, callback));
    }

    private static <T> Promise<T> runPinned(Connection conn,
                                            DatabaseConnectorConfig config,
                                            TransactionCallback<T> body,
                                            TransactionCallback<Unit> release) {
        if (conn instanceof PgConnection) {
            return runPinnedSession(conn, new PinnedSqlConnector(config, conn), body, release);
        }

        return conn.close()
                   .flatMap(_ -> DatabaseConnectorError.PinnedSessionUnsupported.FACTORY.apply("the pool lends a physical connection per query (TRANSACTION pool mode); a session cannot be held").promise());
    }

    private static <T> Promise<T> runPinnedSession(Connection conn,
                                                   SqlConnector session,
                                                   TransactionCallback<T> body,
                                                   TransactionCallback<Unit> release) {
        return invoke(body, session).fold(bodyResult -> releaseThenFinish(conn, session, release, bodyResult));
    }

    private static <T> Promise<T> releaseThenFinish(Connection conn,
                                                    SqlConnector session,
                                                    TransactionCallback<Unit> release,
                                                    Result<T> bodyResult) {
        return invoke(release, session).fold(releaseResult -> finishPinned(conn, bodyResult, releaseResult));
    }

    private static <R> Promise<R> invoke(TransactionCallback<R> callback, SqlConnector session) {
        return Promise.lift(DatabaseConnectorError::databaseFailure,
                            () -> callback.execute(session))
                      .flatMap(promise -> promise);
    }

    // BER-by-destruction: an unlock that failed may have left a session lock behind, and the pool does no session
    // reset (no DISCARD, no pg_advisory_unlock_all), so a lock would ride this connection to the next borrower. Nothing
    // is retried; abort() closes the socket and removes the connection from the pool, the backend ends and the lock goes
    // with it. Guarantee earned: no connection returns to the pool unless its release succeeded. The body's outcome is
    // kept; a failure while destroying is absorbed because the connection is unusable either way.
    private static <T> Promise<T> finishPinned(Connection conn, Result<T> bodyResult, Result<Unit> releaseResult) {
        return releaseResult.fold(cause -> abortAndResolve(conn, bodyResult, cause),
                                  _ -> closeAndResolve(conn, bodyResult));
    }

    private static <T> Promise<T> abortAndResolve(Connection conn, Result<T> bodyResult, Cause releaseFailure) {
        LOG.log(System.Logger.Level.WARNING,
                "Pinned session release failed, aborting the physical connection: " + releaseFailure.message());

        return conn.abort()
                   .fold(_ -> Promise.resolved(bodyResult));
    }

    private static <T> Promise<T> closeAndResolve(Connection conn, Result<T> bodyResult) {
        return conn.close()
                   .fold(_ -> Promise.resolved(bodyResult));
    }

    private static <T> Promise<T> executeInTransaction(Transaction tx,
                                                       DatabaseConnectorConfig config,
                                                       TransactionCallback<T> callback) {
        var txConnector = new TransactionalSqlConnector(config, tx);

        return callback.execute(txConnector)
                       .fold(result -> resolveTransaction(tx, result));
    }

    private static <T> Promise<T> resolveTransaction(Transaction tx, Result<T> result) {
        return result.fold(cause -> tx.rollback()
                                      .flatMap(_ -> cause.promise()),
                           value -> tx.commit()
                                      .map(_ -> value));
    }

    static String convertPlaceholders(String sql) {
        if (!sql.contains("?")) {
            return sql;
        }

        var sb = new StringBuilder(sql.length() + 16);
        int index = 1;

        for (int i = 0; i < sql.length(); i++) {
            var ch = sql.charAt(i);

            if (ch == '?') {
                sb.append('$').append(index++);
            } else {
                sb.append(ch);
            }
        }

        return sb.toString();
    }

    /// The pinned session: statements run directly on the one physical connection (autocommit), and `transactional`
    /// begins and commits on that SAME connection instead of borrowing another.
    private record PinnedSqlConnector(DatabaseConnectorConfig config, Connection connection) implements SqlConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .flatMap(rs -> extractSingleRow(rs, mapper, sql));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .flatMap(rs -> extractOptionalRow(rs, mapper));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .flatMap(rs -> extractAllRows(rs, mapper));
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return connection.completeQuery(convertPlaceholders(sql),
                                            params)
                             .map(PgResultSet::affectedRows);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            if (paramsList.isEmpty()) {
                return Promise.success(new int[0]);
            }

            return executeBatch(connection, convertPlaceholders(sql), paramsList);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return runTransaction(connection, config, callback);
        }

        @Override
        public DatabaseType databaseType() {
            return config.effectiveType(DatabaseConnectorConfig.Transport.ASYNC);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }

    private record TransactionalSqlConnector(DatabaseConnectorConfig config, Transaction transaction) implements SqlConnector {
        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .flatMap(rs -> extractSingleRow(rs, mapper, sql));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .flatMap(rs -> extractOptionalRow(rs, mapper));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .flatMap(rs -> extractAllRows(rs, mapper));
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return transaction.completeQuery(convertPlaceholders(sql),
                                             params)
                              .map(PgResultSet::affectedRows);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            if (paramsList.isEmpty()) {
                return Promise.success(new int[0]);
            }

            return executeBatchInline(convertPlaceholders(sql), paramsList, new int[paramsList.size()], 0);
        }

        private Promise<int[]> executeBatchInline(String sql, List<Object[]> paramsList, int[] counts, int index) {
            if (index >= paramsList.size()) {
                return Promise.success(counts);
            }

            return transaction.completeQuery(sql,
                                             paramsList.get(index))
                              .map(rs -> recordAffectedRows(counts, index, rs))
                              .flatMap(_ -> executeBatchInline(sql, paramsList, counts, index + 1));
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(unit());
        }
    }
}
